package com.skeler.scanely.core.ai

import android.os.SystemClock
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.TokenUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generic in the payload so the same retry policy covers text, a scan's parsed response, and the
 * designer's PDF bytes. Only [Success] carries one; the two failures are shaped identically
 * whatever was being fetched.
 */
internal sealed interface AiOutcome<out T> {
    data class Success<T>(val value: T) : AiOutcome<T>

    data class Exhausted(val networkProblem: Boolean = false, val message: String? = null) : AiOutcome<Nothing>
    data class Fatal(val error: AiResult.Error) : AiOutcome<Nothing>
}

internal const val BACKOFF_BASE_MS = 1200L
internal const val BACKOFF_JITTER_MS = 600L

/** Ceiling on a server-supplied wait, so no single retry leaves the user staring. */
internal const val MAX_BACKOFF_MS = 30_000L

/**
 * How long to wait before retrying [attempt], honouring the server's own hint.
 *
 * The hint wins when it is longer, because it knows something the client cannot guess: a Gemini
 * capacity failure clears in tens of seconds, while the local backoff waits about one. Ignoring it
 * spent all three attempts inside four seconds and reported a failure the server had just said
 * would clear.
 *
 * Clamped, because a long hint — our own per-minute limiter answers 60 — would otherwise strand the
 * user watching a spinner. Retrying a little early there costs one attempt; waiting the full minute
 * costs the user.
 */

/**
 * Whether a failure the server hinted at is worth sitting through.
 *
 * No hint, or a short one, means retry. A hint beyond [MAX_BACKOFF_MS] means the server is
 * describing something that clears on a timescale the user is not waiting for — a provider's daily
 * quota rather than a busy moment — and the honest response is to say so now.
 */
internal fun worthRetrying(serverRetryAfterSeconds: Int?): Boolean =
    serverRetryAfterSeconds == null || serverRetryAfterSeconds * 1000L <= MAX_BACKOFF_MS

internal fun backoffDelay(attempt: Int, serverRetryAfterSeconds: Int?): Long {
    val local = BACKOFF_BASE_MS * (1L shl (attempt - 1)) + Random.nextLong(BACKOFF_JITTER_MS)
    val server = serverRetryAfterSeconds?.times(1000L)?.coerceAtMost(MAX_BACKOFF_MS) ?: 0L
    return maxOf(local, server)
}

/** Retries transient backend failures without retrying user-actionable errors. */
@Singleton
internal class BackendExecutor @Inject constructor(
    private val networkObserver: NetworkObserver
) {
    suspend fun <T> run(
        emit: suspend (AiEvent) -> Unit,
        retrySafe: Boolean = true,
        attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
        call: suspend () -> T
    ): AiOutcome<T> {
        var lastFailureMessage: String? = null
        var lastFailureWasNetwork = false
        var serverRetryAfterSeconds: Int? = null

        repeat(if (retrySafe) MAX_ATTEMPTS else 1) { attempt ->
            if (attempt > 0) {
                val wait = backoffDelay(attempt, serverRetryAfterSeconds)
                countDown(wait, attempt, lastFailureWasNetwork, emit)
            }

            try {
                emit(AiEvent.Stage(AiStage.UPLOADING))
                val start = SystemClock.elapsedRealtime()
                val result = withTimeout(attemptTimeoutMs) { call() }
                aiDebug { "attempt ${attempt + 1} ok in ${SystemClock.elapsedRealtime() - start} ms" }
                return AiOutcome.Success(result)
            } catch (_: TimeoutCancellationException) {
                aiDebug { "attempt ${attempt + 1} timed out" }
                lastFailureWasNetwork = false
                serverRetryAfterSeconds = null
                lastFailureMessage = "The request took too long. Please try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalAiException) {
                return AiOutcome.Fatal(e.result)
            } catch (e: TransientAiException) {
                aiDebug { "attempt ${attempt + 1} transient: ${e.message}" }
                lastFailureWasNetwork = false
                lastFailureMessage = e.message
                serverRetryAfterSeconds = e.retryAfterSeconds

                // A hint longer than we are ever willing to wait is the server saying this will not
                // clear on any timescale the user is sitting through — a daily provider quota, not a
                // busy minute. Retrying it spends a minute to arrive at the same answer, so the
                // message goes straight through instead.
                if (!worthRetrying(e.retryAfterSeconds)) {
                    return AiOutcome.Fatal(AiResult.Error(e.message ?: UNEXPECTED_MESSAGE))
                }
            } catch (e: TokenUnavailableException) {
                // A session the server rejected. Retrying cannot fix it, and the message is written
                // for the user — it used to be dropped here for "your connection looks unstable".
                aiDebug { "attempt ${attempt + 1} session: ${e.message}" }
                return AiOutcome.Fatal(
                    AiResult.Error(e.message ?: UNEXPECTED_MESSAGE, code = "unauthenticated")
                )
            } catch (e: IOException) {
                aiDebug { "attempt ${attempt + 1} IO: ${e.message}" }
                lastFailureMessage = null
                serverRetryAfterSeconds = null
                lastFailureWasNetwork = true
                if (!networkObserver.isCurrentlyOnline()) {
                    return AiOutcome.Exhausted(networkProblem = true)
                }
            } catch (e: SerializationException) {
                aiDebug { "attempt ${attempt + 1} unparsable body: ${e.message}" }
                return AiOutcome.Fatal(AiResult.Error(BAD_RESPONSE_MESSAGE))
            } catch (e: Exception) {
                aiDebug { "attempt ${attempt + 1} ${e::class.simpleName}: ${e.message}" }
                return AiOutcome.Fatal(AiResult.Error(UNEXPECTED_MESSAGE))
            }
        }

        return AiOutcome.Exhausted(networkProblem = lastFailureWasNetwork, message = lastFailureMessage)
    }

    /**
     * Emits a ticking remainder rather than one message for the whole wait. A static "retrying in
     * 30 s" sits unchanged long enough to read as a hung request, which is the thing the retry was
     * meant to avoid.
     */
    private suspend fun countDown(
        waitMs: Long,
        attempt: Int,
        networkProblem: Boolean,
        emit: suspend (AiEvent) -> Unit
    ) {
        var remaining = waitMs
        while (remaining > 0) {
            val seconds = (remaining + 999) / 1000
            val lead = if (networkProblem) {
                "Connection trouble"
            } else {
                "Temporary service issue"
            }
            emit(
                AiEvent.Stage(
                    AiStage.PROCESSING,
                    "$lead. Retrying in ${seconds}s (${attempt + 1}/$MAX_ATTEMPTS)."
                )
            )
            val step = minOf(TICK_MS, remaining)
            delay(step)
            remaining -= step
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        private const val BAD_RESPONSE_MESSAGE =
            "$AI_PROVIDER_NAME sent back something this app can't read. Try again."
        private const val UNEXPECTED_MESSAGE =
            "Something went wrong talking to $AI_PROVIDER_NAME. Try again."

        private const val TICK_MS = 1_000L

        private const val ATTEMPT_TIMEOUT_MS = 300_000L
    }
}
