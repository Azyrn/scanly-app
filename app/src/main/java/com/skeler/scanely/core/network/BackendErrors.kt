package com.skeler.scanely.core.network

import retrofit2.HttpException

/** What to tell the user when the Worker could not be reached at all. */
const val NO_CONNECTION_MESSAGE = "Couldn't reach Scanly. Check your connection and try again."

/**
 * The Worker's error envelope inside an HTTP error, or null when the body is not one.
 *
 * Reads the error body, and Retrofit's error body can be read only once: a second call returns
 * null. Call it once per exception and pass the result along.
 */
fun HttpException.backendError(): BackendError? = try {
    TolerantJson.decodeFromString<BackendErrorEnvelope>(response()?.errorBody()?.string().orEmpty()).error
} catch (_: Exception) {
    null
}
