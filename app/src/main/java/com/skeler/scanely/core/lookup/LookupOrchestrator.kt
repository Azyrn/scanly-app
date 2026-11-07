package com.skeler.scanely.core.lookup

import android.content.Context
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.network.NetworkObserver
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context,
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
            return@coroutineScope LookupResult.NotFound(context.getString(R.string.lk_no_engines))
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
        failures.isEmpty() -> LookupResult.NotFound(
            context.resources.getQuantityString(R.plurals.lk_searched, searched.size, searched.size)
        )
        // Someone answered "not found"; the product may still be in a source that did not
        // answer, so say which rather than calling the search complete.
        failures.size < searched.size -> LookupResult.NotFound(
            context.resources.getQuantityString(
                R.plurals.lk_searched_partial,
                searched.size,
                searched.size,
                failures.joinToString { it.first }
            )
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
            networkFailure && !networkObserver.isCurrentlyOnline() -> context.getString(R.string.net_no_internet)
            failures.all { it is IOException || it is TimeoutCancellationException } ->
                context.getString(R.string.lk_unreachable)
            else -> context.getString(R.string.lk_server_problem)
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
}
