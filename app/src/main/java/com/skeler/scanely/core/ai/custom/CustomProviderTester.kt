package com.skeler.scanely.core.ai.custom

import com.skeler.scanely.core.di.ProviderProbe
import com.skeler.scanely.core.network.CustomProviderApi
import com.skeler.scanely.core.network.NetworkObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val failure: ConnectionFailure) : ConnectionTestResult
}

/** Tests a user-supplied provider endpoint with a minimal request. */
@Singleton
class CustomProviderTester @Inject internal constructor(
    @param:ProviderProbe private val api: CustomProviderApi,
    private val networkObserver: NetworkObserver
) {
    suspend fun test(
        rawUrl: String,
        apiKey: String,
        model: String,
        protocol: ProviderProtocol = ProviderProtocol.DEFAULT,
        authStyle: AuthStyle = AuthStyle.DEFAULT,
        customAuthHeader: String = ""
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        ConnectionDiagnosis.validate(rawUrl, apiKey, model, protocol, authStyle, customAuthHeader)
            ?.let { return@withContext ConnectionTestResult.Failure(it) }

        if (!networkObserver.isCurrentlyOnline()) {
            return@withContext ConnectionTestResult.Failure(ConnectionFailure.Offline)
        }

        val trimmedModel = model.trim()
        val url = EndpointUrl.normalize(rawUrl, protocol, trimmedModel)
            ?: return@withContext ConnectionTestResult.Failure(
                ConnectionFailure.MalformedUrl(rawUrl.trim())
            )
        val host = EndpointUrl.hostOf(url)

        val config = CustomProviderConfig(
            url = url,
            apiKey = apiKey.trim(),
            model = trimmedModel,
            protocol = protocol,
            authStyle = authStyle,
            customAuthHeader = customAuthHeader
        )

        when (val answer = probeOnce(url, config, host)) {
            is Probed.Unreachable -> ConnectionTestResult.Failure(answer.failure)
            is Probed.Answer -> answer.failure(trimmedModel, protocol)?.let { ConnectionTestResult.Failure(it) }
                ?: ConnectionTestResult.Success
        }
    }

    /** One probe; OpenAI's reasoning models refuse `max_tokens` and name the field they take, so they are asked again. */
    private suspend fun probeOnce(url: String, config: CustomProviderConfig, host: String): Probed {
        val first = probe(url, config, config.protocol, completionTokens = false, host)
        val again = first is Probed.Answer && config.protocol == ProviderProtocol.OPENAI && first.wantsCompletionTokens
        return if (again) probe(url, config, config.protocol, completionTokens = true, host) else first
    }

    private suspend fun probe(
        url: String,
        config: CustomProviderConfig,
        protocol: ProviderProtocol,
        completionTokens: Boolean,
        host: String
    ): Probed = try {
        val response = api.post(url, config.headers(), ProviderRequest.probe(protocol, config.model, completionTokens))
        val body = if (response.isSuccessful) response.body()?.string() else response.errorBody()?.string()
        Probed.Answer(response.code(), body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Probed.Unreachable(ConnectionDiagnosis.fromException(e, host))
    }

    private sealed interface Probed {
        data class Unreachable(val failure: ConnectionFailure) : Probed

        data class Answer(val status: Int, val body: String?) : Probed {
            val wantsCompletionTokens: Boolean
                get() = status == HTTP_BAD_REQUEST && body?.contains("max_completion_tokens") == true

            fun failure(model: String, protocol: ProviderProtocol): ConnectionFailure? = if (status in HTTP_OK_RANGE) {
                ConnectionDiagnosis.fromSuccessBody(body, model, protocol)
            } else {
                ConnectionDiagnosis.fromHttpStatus(status, body, model)
            }
        }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        val HTTP_OK_RANGE = 200..299
    }
}
