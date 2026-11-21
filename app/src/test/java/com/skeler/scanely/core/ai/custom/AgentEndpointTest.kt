package com.skeler.scanely.core.ai.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the document assistant is given of the user's endpoint (`ownModel`, worker/src/own-model.ts). */
class AgentEndpointTest {

    private fun config(url: String, protocol: ProviderProtocol, auth: AuthStyle = protocol.defaultAuth, header: String = "") =
        CustomProviderConfig(url = url, apiKey = "sk-own", model = "my-model", protocol = protocol, authStyle = auth,
            customAuthHeader = header)

    @Test
    fun `an OpenAI-compatible endpoint goes as it was tested, with its key header`() {
        val result = AgentEndpoint.from(config("https://api.groq.com/openai/v1/chat/completions", ProviderProtocol.OPENAI))
        assertEquals(
            AgentEndpoint.Result.Usable(
                OwnModelDto("openai", "https://api.groq.com/openai/v1/chat/completions", "my-model",
                    mapOf("Authorization" to "Bearer sk-own"))
            ),
            result
        )
    }

    @Test
    fun `an Azure or self-chosen header is kept, and Anthropic gets no version header of ours`() {
        val azure = AgentEndpoint.from(config("https://x.openai.azure.com/openai/deployments/d/chat/completions?api-version=1",
            ProviderProtocol.OPENAI, AuthStyle.API_KEY)) as AgentEndpoint.Result.Usable
        assertEquals(mapOf("api-key" to "sk-own"), azure.model.headers)
        val anthropic = AgentEndpoint.from(config("https://api.anthropic.com/v1/messages", ProviderProtocol.ANTHROPIC))
            as AgentEndpoint.Result.Usable
        assertEquals("anthropic", anthropic.model.protocol)
        assertEquals(mapOf("x-api-key" to "sk-own"), anthropic.model.headers)
    }

    @Test
    fun `Gemini goes to its OpenAI-compatible route with a bearer key, because the agent needs tool calls`() {
        val result = AgentEndpoint.from(config(
            "https://generativelanguage.googleapis.com/v1beta/models/my-model:generateContent", ProviderProtocol.GEMINI
        )) as AgentEndpoint.Result.Usable
        assertEquals(
            OwnModelDto("openai", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "my-model",
                mapOf("Authorization" to "Bearer sk-own")),
            result.model
        )
    }

    @Test
    fun `an endpoint on this phone's network or on plain http cannot be reached by Scanly's server`() {
        for (url in listOf(
            "http://192.168.1.20:11434/v1/chat/completions",
            "https://192.168.1.20/v1/chat/completions",
            "https://localhost:1234/v1/chat/completions",
            "https://ollama.local/v1/chat/completions",
            "http://api.example.com/v1/chat/completions"
        )) {
            assertEquals(url, AgentEndpoint.Result.NotReachableFromServer, AgentEndpoint.from(config(url, ProviderProtocol.OPENAI)))
        }
    }
}
