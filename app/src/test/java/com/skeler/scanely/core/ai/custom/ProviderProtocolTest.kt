package com.skeler.scanely.core.ai.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MODEL = "claude-haiku-4-5-20251001"

private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

class ProviderProtocolTest {

    // ---- auth headers -------------------------------------------------------

    @Test
    fun `bearer is the only style that decorates the value`() {
        assertEquals(
            mapOf("Authorization" to "Bearer sk-123"),
            AuthStyle.BEARER.headersFor("sk-123", "")
        )
        assertEquals(mapOf("x-api-key" to "sk-123"), AuthStyle.X_API_KEY.headersFor("sk-123", ""))
        assertEquals(mapOf("api-key" to "sk-123"), AuthStyle.API_KEY.headersFor("sk-123", ""))
        assertEquals(
            mapOf("x-goog-api-key" to "sk-123"),
            AuthStyle.GOOG_API_KEY.headersFor("sk-123", "")
        )
    }

    @Test
    fun `a custom style uses the name the user supplied`() {
        assertEquals(
            mapOf("X-Gateway-Token" to "sk-123"),
            AuthStyle.CUSTOM.headersFor("sk-123", "X-Gateway-Token")
        )
    }

    @Test
    fun `a custom style with no header name sends nothing rather than a broken header`() {
        assertTrue(AuthStyle.CUSTOM.headersFor("sk-123", "   ").isEmpty())
    }

    @Test
    fun `each protocol defaults to the auth header its vendor documents`() {
        assertEquals(AuthStyle.BEARER, ProviderProtocol.OPENAI.defaultAuth)
        assertEquals(AuthStyle.X_API_KEY, ProviderProtocol.ANTHROPIC.defaultAuth)
        assertEquals(AuthStyle.GOOG_API_KEY, ProviderProtocol.GEMINI.defaultAuth)
    }

    @Test
    fun `anthropic calls always carry the required version header`() {
        val config = CustomProviderConfig(
            url = "https://api.anthropic.com/v1/messages",
            apiKey = "sk-ant",
            model = MODEL,
            protocol = ProviderProtocol.ANTHROPIC,
            authStyle = AuthStyle.X_API_KEY
        )
        assertEquals("2023-06-01", config.headers()["anthropic-version"])
        assertEquals("sk-ant", config.headers()["x-api-key"])
    }

    @Test
    fun `the version header is anthropic-only`() {
        val config = CustomProviderConfig(
            url = "https://api.groq.com/openai/v1/chat/completions",
            apiKey = "gsk",
            model = "llama",
            protocol = ProviderProtocol.OPENAI
        )
        assertNull(config.headers()["anthropic-version"])
    }

    // ---- request bodies -----------------------------------------------------

    @Test
    fun `the openai probe names the model in the body`() {
        val body = ProviderRequest.probe(ProviderProtocol.OPENAI, MODEL)
        assertTrue(body.toString().contains(MODEL))
        assertTrue(body.containsKey("messages"))
    }

    @Test
    fun `the anthropic probe carries the mandatory max_tokens`() {
        val body = ProviderRequest.probe(ProviderProtocol.ANTHROPIC, MODEL)
        assertTrue("max_tokens is required by the Anthropic API", body.containsKey("max_tokens"))
        assertTrue(body.containsKey("messages"))
    }

    @Test
    fun `the gemini probe omits the model, because the URL carries it`() {
        val body = ProviderRequest.probe(ProviderProtocol.GEMINI, MODEL)
        assertTrue(body.containsKey("contents"))
        assertNull(body["model"])
    }

    @Test
    fun `each scan body carries the images in that protocol's own shape`() {
        val image = "BASE64DATA"

        val openai = ProviderRequest.completion(ProviderProtocol.OPENAI, MODEL, "sys", "go", listOf(image))
        assertTrue(openai.toString().contains("image_url"))
        assertTrue(openai.toString().contains("data:image/jpeg;base64,$image"))

        val anthropic =
            ProviderRequest.completion(ProviderProtocol.ANTHROPIC, MODEL, "sys", "go", listOf(image))
        assertTrue(anthropic.toString().contains("\"type\":\"image\""))
        assertTrue(anthropic.toString().contains("media_type"))
        assertTrue("raw base64, no data URI", anthropic.toString().contains("\"data\":\"$image\""))

        val gemini = ProviderRequest.completion(ProviderProtocol.GEMINI, MODEL, "sys", "go", listOf(image))
        assertTrue(gemini.toString().contains("inline_data"))
        assertTrue(gemini.toString().contains("mime_type"))
    }

    @Test
    fun `the system instruction reaches every protocol, in its own slot`() {
        val sys = "SYSTEM_MARKER"
        assertTrue(
            ProviderRequest.completion(ProviderProtocol.OPENAI, MODEL, sys, "go", emptyList())
                .toString().contains(sys)
        )
        assertTrue(
            ProviderRequest.completion(ProviderProtocol.ANTHROPIC, MODEL, sys, "go", emptyList())
                .toString().contains("\"system\":\"$sys\"")
        )
        assertTrue(
            ProviderRequest.completion(ProviderProtocol.GEMINI, MODEL, sys, "go", emptyList())
                .toString().contains("systemInstruction")
        )
    }

    // ---- responses ----------------------------------------------------------

    @Test
    fun `text is extracted from each protocol's success envelope`() {
        assertEquals(
            "pong",
            ProviderResponse.extractText(
                ProviderProtocol.OPENAI,
                obj("""{"choices":[{"message":{"content":"pong"}}]}""")
            )
        )
        assertEquals(
            "pong",
            ProviderResponse.extractText(
                ProviderProtocol.ANTHROPIC,
                obj("""{"content":[{"type":"text","text":"pong"}]}""")
            )
        )
        assertEquals(
            "pong",
            ProviderResponse.extractText(
                ProviderProtocol.GEMINI,
                obj("""{"candidates":[{"content":{"parts":[{"text":"pong"}]}}]}""")
            )
        )
    }

    @Test
    fun `anthropic non-text blocks are skipped`() {
        val body = obj(
            """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"real"}]}"""
        )
        assertEquals("real", ProviderResponse.extractText(ProviderProtocol.ANTHROPIC, body))
    }

    @Test
    fun `gemini reasoning parts are skipped`() {
        val body = obj(
            """{"candidates":[{"content":{"parts":[
               {"text":"secret","thought":true},{"text":"real"}]}}]}"""
        )
        assertEquals("real", ProviderResponse.extractText(ProviderProtocol.GEMINI, body))
    }

    @Test
    fun `a body from the wrong protocol fails that protocol's schema check`() {
        val openAiBody = obj("""{"choices":[{"message":{"content":"pong"}}]}""")
        assertTrue(ProviderResponse.matchesSchema(ProviderProtocol.OPENAI, openAiBody))
        assertTrue(!ProviderResponse.matchesSchema(ProviderProtocol.ANTHROPIC, openAiBody))
        assertTrue(!ProviderResponse.matchesSchema(ProviderProtocol.GEMINI, openAiBody))
    }

    @Test
    fun `pointing an anthropic config at an openai endpoint is reported as a schema mismatch`() {
        val failure = ConnectionDiagnosis.fromSuccessBody(
            """{"choices":[{"message":{"content":"pong"}}]}""",
            MODEL,
            ProviderProtocol.ANTHROPIC
        )
        assertEquals(ConnectionFailure.UnexpectedSchema, failure)
    }

    @Test
    fun `a matching protocol and body is the success case`() {
        assertNull(
            ConnectionDiagnosis.fromSuccessBody(
                """{"content":[{"type":"text","text":"pong"}]}""",
                MODEL,
                ProviderProtocol.ANTHROPIC
            )
        )
        assertNull(
            ConnectionDiagnosis.fromSuccessBody(
                """{"candidates":[{"content":{"parts":[{"text":"pong"}]}}]}""",
                MODEL,
                ProviderProtocol.GEMINI
            )
        )
    }

    @Test
    fun `a custom auth style with no header name is caught before any request goes out`() {
        val failure = ConnectionDiagnosis.validate(
            rawUrl = "https://gateway.example.com/v1/chat/completions",
            apiKey = "sk-1",
            model = "m",
            protocol = ProviderProtocol.OPENAI,
            authStyle = AuthStyle.CUSTOM,
            customAuthHeader = ""
        )
        assertEquals(ConnectionFailure.EmptyAuthHeader, failure)
    }
}
