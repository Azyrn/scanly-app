package com.skeler.scanely.core.network

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.skeler.scanely.core.common.awaitWithin
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Resolves a Firebase token for an interceptor, narrowing every failure to IOException.
 *
 * OkHttp's contract is that `intercept` throws nothing but IOException. `RealCall$AsyncCall`
 * hands anything else to the failure callback and then rethrows it, where it reaches the
 * dispatcher thread's default handler and kills the process — a caller's own try/catch cannot
 * stop it. Firebase throws bare FirebaseExceptions here, so every one of them has to be narrowed.
 */
internal fun <T> awaitToken(failureMessage: String, task: () -> Task<T>): T =
    try {
        runBlocking {
            task().awaitWithin(TOKEN_WAIT_MS) { TokenUnavailableException(TOKEN_TIMEOUT_MESSAGE) }
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Throwable) {
        throw tokenFailure(failureMessage, e)
    }

/**
 * What a failed token fetch is, as far as the caller should treat it.
 *
 * A refresh that failed because the phone is offline is a connection problem and stays one, so the
 * caller's offline handling applies. Everything else is the session itself — a disabled account, a
 * revoked token — which a retry cannot fix and which the user can only fix by signing in again.
 */
internal fun tokenFailure(failureMessage: String, cause: Throwable): IOException =
    if (cause is FirebaseNetworkException) {
        IOException("Token refresh failed: no connection", cause)
    } else {
        TokenUnavailableException(failureMessage, cause)
    }

/**
 * A token failure rather than a transport one. Its message is written for the user, so callers can
 * show it verbatim instead of guessing "check your connection" at someone whose connection is fine.
 */
class TokenUnavailableException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * A cached token is an in-memory read and a refresh is one round trip, so fifteen seconds is a
 * stall, not a slow network — and it sits inside the control client's 20 s callTimeout.
 */
internal const val TOKEN_WAIT_MS = 15_000L

internal const val TOKEN_TIMEOUT_MESSAGE =
    "Signing in to Scanly is taking too long. Check your connection and try again."
