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

        val response = try {
            api.post(url, config.headers(), ProviderRequest.probe(protocol, trimmedModel))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ConnectionTestResult.Failure(
                ConnectionDiagnosis.fromException(e, host)
            )
        }

        val failure = if (response.isSuccessful) {
            ConnectionDiagnosis.fromSuccessBody(response.body()?.string(), trimmedModel, protocol)
        } else {
            ConnectionDiagnosis.fromHttpStatus(
                response.code(),
                response.errorBody()?.string(),
                trimmedModel
            )
        }

        failure?.let { ConnectionTestResult.Failure(it) } ?: ConnectionTestResult.Success
    }
}
