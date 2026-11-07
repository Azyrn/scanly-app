package com.skeler.scanely.core.ai.custom

import com.skeler.scanely.core.network.LenientJson
import com.skeler.scanely.core.network.TolerantJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Maps connection-test failures to user-facing diagnostics. */
internal object ConnectionDiagnosis {

    private val lenientJson = LenientJson

    private val strictJson = TolerantJson

    fun validate(
        rawUrl: String,
        apiKey: String,
        model: String,
        protocol: ProviderProtocol = ProviderProtocol.DEFAULT,
        authStyle: AuthStyle = AuthStyle.DEFAULT,
        customAuthHeader: String = ""
    ): ConnectionFailure? {
        val trimmedUrl = rawUrl.trim()
        if (trimmedUrl.isEmpty()) return ConnectionFailure.EmptyUrl
        if (EndpointUrl.normalize(trimmedUrl, protocol, model.trim()) == null) {
            return ConnectionFailure.MalformedUrl(trimmedUrl)
        }
        if (apiKey.isBlank()) return ConnectionFailure.EmptyKey
        if (model.isBlank()) return ConnectionFailure.EmptyModel
        if (authStyle == AuthStyle.CUSTOM && customAuthHeader.isBlank()) {
            return ConnectionFailure.EmptyAuthHeader
        }
        return null
    }

    fun fromException(error: Throwable, host: String): ConnectionFailure = when (error) {
        is UnknownHostException -> ConnectionFailure.UnknownHost(host)
        is SocketTimeoutException, is InterruptedIOException -> ConnectionFailure.Timeout(host)
        is ConnectException, is NoRouteToHostException -> ConnectionFailure.Unreachable(host)
        is SSLException -> ConnectionFailure.NetworkError(host, "The secure connection failed.")
        is IOException -> ConnectionFailure.NetworkError(host, error.message)
        else -> ConnectionFailure.NetworkError(host, error.message)
    }

    fun fromHttpStatus(status: Int, body: String?, model: String): ConnectionFailure {
        val detail = extractErrorDetail(body)

        // OpenAI-compatible APIs may report both bad models and paths as 404.
        if (indicatesModelProblem(detail, body)) return ConnectionFailure.ModelRejected(model, detail)

        return when (status) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ConnectionFailure.InvalidKey(status)
            HTTP_NOT_FOUND -> ConnectionFailure.WrongEndpoint
            HTTP_TOO_MANY_REQUESTS -> ConnectionFailure.RateLimited
            else -> ConnectionFailure.ProviderError(status, detail)
        }
    }

    fun fromSuccessBody(
        body: String?,
        model: String,
        protocol: ProviderProtocol = ProviderProtocol.DEFAULT
    ): ConnectionFailure? {
        val root = parseSuccessObject(body) { return it }

        if (root.containsKey("error")) {
            val detail = extractErrorDetail(body)
            return if (indicatesModelProblem(detail, body)) {
                ConnectionFailure.ModelRejected(model, detail)
            } else {
                ConnectionFailure.ProviderError(HTTP_OK, detail)
            }
        }

        return if (ProviderResponse.matchesSchema(protocol, root)) {
            null
        } else {
            ConnectionFailure.UnexpectedSchema
        }
    }

    /** [body] as a JSON object, or [fail] with why it is not one. */
    private inline fun parseSuccessObject(body: String?, fail: (ConnectionFailure) -> Nothing): JsonObject {
        if (body.isNullOrBlank()) fail(ConnectionFailure.UnexpectedSchema)

        // The parser accepts bare tokens, so reject non-object responses before parsing.
        when (body.trimStart().firstOrNull()) {
            '{' -> Unit
            '[' -> fail(ConnectionFailure.UnexpectedSchema)
            else -> fail(ConnectionFailure.MalformedJson)
        }

        val root = try {
            strictJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            fail(ConnectionFailure.MalformedJson)
        }

        return root as? JsonObject ?: fail(ConnectionFailure.UnexpectedSchema)
    }

    fun extractErrorDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null

        val root = try {
            lenientJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return null
        }
        if (root !is JsonObject) return null

        val messages = buildList {
            addAll(errorFieldMessages(root["error"]))
            (root["message"] as? JsonPrimitive)?.let { add(it.content) }
            (root["detail"] as? JsonPrimitive)?.let { add(it.content) }
        }.filter { it.isNotBlank() }

        return messages.distinct().joinToString(" · ").ifBlank { null }
    }

    /** The texts an `error` field carries: itself when a string, else its message, code and type. */
    private fun errorFieldMessages(error: JsonElement?): List<String> = when (error) {
        is JsonPrimitive -> listOf(error.content)
        is JsonObject -> listOf("message", "code", "type").mapNotNull { (error[it] as? JsonPrimitive)?.content }
        else -> emptyList()
    }

    private fun indicatesModelProblem(detail: String?, body: String?): Boolean {
        val haystack = (detail ?: body)?.lowercase() ?: return false
        if (MODEL_CODES.any { it in haystack }) return true
        if ("model" !in haystack) return false
        return REJECTION_WORDS.any { it in haystack }
    }

    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429

    private val MODEL_CODES = listOf(
        "model_not_found",
        "model_not_supported",
        "unknown_model",
        "invalid_model"
    )

    private val REJECTION_WORDS = listOf(
        "not found",
        "does not exist",
        "doesn't exist",
        "not exist",
        "unknown",
        "invalid",
        "unsupported",
        "not supported",
        "not a valid",
        "no access",
        "does not have access",
        "unavailable",
        "decommissioned",
        "deprecated"
    )
}
