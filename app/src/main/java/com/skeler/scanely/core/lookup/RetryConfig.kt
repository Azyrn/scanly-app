package com.skeler.scanely.core.lookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 5000,
    val backoffMultiplier: Double = 2.0
)

suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    shouldRetry: (Exception) -> Boolean = { it.isRetryable() },
    block: suspend () -> T
): T {
    var currentDelay = config.initialDelayMs

    repeat(config.maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (!shouldRetry(e) || attempt == config.maxAttempts - 1) {
                throw e
            }

            delay(currentDelay)
            currentDelay = min(
                (currentDelay * config.backoffMultiplier).toLong(),
                config.maxDelayMs
            )
        }
    }

    // Reachable only with maxAttempts <= 0; the last attempt always rethrows.
    error("Retry failed")
}

fun Exception.isRetryable(): Boolean = retryableByType() ?: retryableByMessage()

/** Whether the exception's type alone decides it, or null when it does not. */
private fun Exception.retryableByType(): Boolean? = when (this) {
    // A per-attempt withTimeout inside the retry block. Any other cancellation is the caller
    // giving up, which must never be retried.
    is TimeoutCancellationException -> true
    is CancellationException -> false
    is LookupServerException -> true
    is LookupRejectedException -> false
    is java.net.SocketTimeoutException,
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.io.InterruptedIOException -> true
    else -> null
}

private val RETRYABLE_MESSAGE_WORDS = listOf("timeout", "connection", "reset", "refused")

private fun Exception.retryableByMessage(): Boolean {
    val message = message?.lowercase() ?: ""
    return RETRYABLE_MESSAGE_WORDS.any { message.contains(it) }
}
