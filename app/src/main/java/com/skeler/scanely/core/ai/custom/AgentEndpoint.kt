package com.skeler.scanely.core.ai.custom

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The user's endpoint as the document assistant takes it (`ownModel` on `POST /v1/agent/turns`,
 * worker/src/own-model.ts).
 *
 * Unlike a scan, an assistant turn does not run on this phone: it runs on Scanly's agent server,
 * whose proxy calls the endpoint with the key the user gave (and holds no key of Scanly's, so the turn
 * can never be billed to Scanly). Two things follow. The endpoint must be reachable from the internet
 * over https, so a LAN or plain-http one is refused here instead of failing on the server; and the
 * agent needs a tool-calling chat API, which Gemini offers only on its OpenAI-compatible route, so
 * Gemini goes there with a bearer key.
 */
internal object AgentEndpoint {

    sealed interface Result {
        data class Usable(val model: OwnModelDto) : Result

        /** On this phone's network, or plain http: the server cannot (and must not) reach it. */
        data object NotReachableFromServer : Result
    }

    fun from(config: CustomProviderConfig): Result {
        val url = config.url.toHttpUrlOrNull() ?: return Result.NotReachableFromServer
        if (!url.isHttps || EndpointUrl.looksLocal(config.url)) return Result.NotReachableFromServer

        // The key header as the endpoint was tested with; the agent sends its own anthropic-version header.
        val auth = config.authStyle.headersFor(config.apiKey, config.customAuthHeader)
        val model = when (config.protocol) {
            ProviderProtocol.OPENAI -> OwnModelDto(OPENAI, config.url, config.model, auth)
            ProviderProtocol.ANTHROPIC -> OwnModelDto(ANTHROPIC, config.url, config.model, auth)
            ProviderProtocol.GEMINI -> OwnModelDto(
                OPENAI,
                url.newBuilder().encodedPath(GEMINI_OPENAI_PATH).query(null).build().toString(),
                config.model,
                mapOf("Authorization" to "Bearer ${config.apiKey}")
            )
        }
        return Result.Usable(model)
    }

    private const val OPENAI = "openai"
    private const val ANTHROPIC = "anthropic"

    /** Gemini's OpenAI-compatible chat route, which takes tools and a bearer key. */
    private const val GEMINI_OPENAI_PATH = "/v1beta/openai/chat/completions"
}

/** `ownModel` on an assistant turn; [headers] carries the key. Never logged. */
@Serializable
data class OwnModelDto(
    val protocol: String,
    val url: String,
    val model: String,
    val headers: Map<String, String>
)
