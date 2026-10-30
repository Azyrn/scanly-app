package com.skeler.scanely.core.network

import retrofit2.HttpException

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
