package com.skeler.scanely.core.lookup

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import kotlin.coroutines.resumeWithException

private const val HTTP_TOO_MANY_REQUESTS = 429

/** The source answered 5xx or 429: it is up but struggling, and may answer the next attempt. */
class LookupServerException(source: String, val code: Int, cause: Throwable? = null) :
    Exception("$source answered HTTP $code", cause)

/** The source refused the request itself (a 4xx other than 404). Asking again gets the same answer. */
class LookupRejectedException(source: String, val code: Int, cause: Throwable? = null) :
    Exception("$source refused the request (HTTP $code)", cause)

/** Every source failed. The message is written for the user. */
class LookupFailedException(message: String) : Exception(message)

/** Maps an HTTP status other than 2xx and 404 onto what the retry loop can act on. */
internal fun lookupHttpFailure(source: String, code: Int, cause: Throwable? = null): Exception =
    if (code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_INTERNAL_ERROR) {
        LookupServerException(source, code, cause)
    } else {
        LookupRejectedException(source, code, cause)
    }

/**
 * The body of a 2xx; null for a 404, which every source here uses for "no such product".
 * Anything else throws, so a server that is down is never reported as a product that does not exist.
 */
internal fun Response.lookupBody(source: String): String? = when {
    isSuccessful -> body.string()
    code == HTTP_NOT_FOUND -> null
    else -> throw lookupHttpFailure(source, code)
}

/**
 * [Call.execute] that a coroutine timeout can stop. A blocking `execute()` ignores cancellation, so
 * the orchestrator's per-attempt timeout could not end a stalled call before OkHttp's own read timeout.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
