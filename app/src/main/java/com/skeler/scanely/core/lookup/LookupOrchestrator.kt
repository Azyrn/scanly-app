package com.skeler.scanely.core.lookup

import android.util.Log
import com.skeler.scanely.core.network.NetworkObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LookupOrchestrator"
private const val ENGINE_TIMEOUT_MS = 10_000L

@Singleton
class LookupOrchestrator @Inject constructor(
    private val engines: Set<@JvmSuppressWildcards LookupEngine>,
    private val networkObserver: NetworkObserver
) {
    private val retryConfig = RetryConfig(
        maxAttempts = 2,
        initialDelayMs = 500,
        maxDelayMs = 2000
    )

    suspend fun lookup(barcode: String): LookupResult = coroutineScope {
        val supportingEngines = engines
            .filter { it.supports(barcode) }
            .sortedBy { it.priority }

        if (supportingEngines.isEmpty()) {
            Log.d(TAG, "No engines support barcode: $barcode")
            return@coroutineScope LookupResult.NotFound("No compatible engines")
        }

        Log.d(
            TAG,
            "Looking up $barcode with ${supportingEngines.size} engines: " +
                supportingEngines.joinToString { it.name }
        )

        val jobs = supportingEngines.map { engine ->
            async { lookupWithRetry(engine, barcode) }
        }

        val failures = mutableListOf<Pair<String, Exception>>()

        jobs.forEachIndexed { index, job ->
            when (val result = job.await()) {
                is LookupResult.Found -> {
                    Log.d(TAG, "Found in ${supportingEngines[index].name}: ${result.product.name}")
                    jobs.forEach { it.cancel() }
                    return@coroutineScope result
                }
                is LookupResult.NotFound -> Unit
                is LookupResult.Error -> failures += result.source to result.exception
            }
        }

        conclude(supportingEngines, failures)
    }

    private fun conclude(
        searched: List<LookupEngine>,
        failures: List<Pair<String, Exception>>
    ): LookupResult = when {
        failures.isEmpty() -> LookupResult.NotFound("Searched ${searched.size} sources")
        // Someone answered "not found"; the product may still be in a source that did not
        // answer, so say which rather than calling the search complete.
        failures.size < searched.size -> LookupResult.NotFound(
            "Searched ${searched.size} sources. Couldn't reach ${failures.joinToString { it.first }}"
        )
        else -> LookupResult.Error(
            searched.joinToString { it.name },
            LookupFailedException(failureMessage(failures.map { it.second }))
        )
    }

    /**
     * Why nothing answered, in the user's terms. An exception below HTTP is only "no internet" if
     * the device agrees it is offline; a source that answered with an error status is not the
     * user's connection at all.
     */
    private fun failureMessage(failures: List<Exception>): String {
        val networkFailure = failures.any { it is IOException || it is TimeoutCancellationException }
        return when {
            networkFailure && !networkObserver.isCurrentlyOnline() -> NO_INTERNET
            failures.all { it is IOException || it is TimeoutCancellationException } -> UNREACHABLE
            else -> SERVER_PROBLEM
        }
    }

    private suspend fun lookupWithRetry(engine: LookupEngine, barcode: String): LookupResult {
        return try {
            withRetry(retryConfig) {
                withTimeout(ENGINE_TIMEOUT_MS) {
                    engine.lookup(barcode)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${engine.name} timed out after ${ENGINE_TIMEOUT_MS}ms")
            LookupResult.Error(engine.name, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${engine.name} failed after retries: ${e.message}")
            LookupResult.Error(engine.name, e)
        }
    }

    private companion object {
        const val NO_INTERNET = "No internet connection."
        const val UNREACHABLE =
            "Couldn't reach the product databases. Your connection looks fine — try again in a few minutes."
        const val SERVER_PROBLEM =
            "Couldn't reach the product databases: their servers are having problems. Try again later."
    }
}
