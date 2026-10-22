package com.skeler.scanely.core.ai.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private const val MODEL = "llama-3.3-70b-versatile"
private const val URL = "https://api.groq.com/openai/v1/chat/completions"
private const val KEY = "gsk_secret"

class ConnectionDiagnosisTest {

    @Test
    fun `empty url is reported as empty, not malformed`() {
        assertEquals(ConnectionFailure.EmptyUrl, ConnectionDiagnosis.validate("   ", KEY, MODEL))
    }

    @Test
    fun `malformed url names the value the user typed`() {
        val failure = ConnectionDiagnosis.validate("not a url", KEY, MODEL)
        assertTrue(failure is ConnectionFailure.MalformedUrl)
        assertTrue(failure!!.message.contains("not a url"))
    }

    @Test
    fun `a self-hosted localhost endpoint passes validation`() {
        assertNull(ConnectionDiagnosis.validate("http://localhost:11434/v1", KEY, MODEL))
        assertNull(ConnectionDiagnosis.validate("192.168.1.50:8000/v1", KEY, MODEL))
    }

    @Test
    fun `empty key and empty model are distinct failures`() {
        assertEquals(ConnectionFailure.EmptyKey, ConnectionDiagnosis.validate(URL, "  ", MODEL))
        assertEquals(ConnectionFailure.EmptyModel, ConnectionDiagnosis.validate(URL, KEY, ""))
    }

    @Test
    fun `a complete configuration passes pre-flight validation`() {
        assertNull(ConnectionDiagnosis.validate(URL, KEY, MODEL))
    }

    @Test
    fun `scheme-less host is accepted and normalized rather than rejected`() {
        assertNull(ConnectionDiagnosis.validate("api.groq.com/openai/v1/chat/completions", KEY, MODEL))
    }

    @Test
    fun `dns failure is reported as an unknown host`() {
        val failure = ConnectionDiagnosis.fromException(UnknownHostException("api.typo.com"), "api.typo.com")
        assertEquals(ConnectionFailure.UnknownHost("api.typo.com"), failure)
        assertTrue(failure.message.contains("api.typo.com"))
    }

    @Test
    fun `connection timeout is distinct from an unreachable host`() {
        assertEquals(
            ConnectionFailure.Timeout("api.groq.com"),
            ConnectionDiagnosis.fromException(SocketTimeoutException(), "api.groq.com")
        )
        assertEquals(
            ConnectionFailure.Unreachable("api.groq.com"),
            ConnectionDiagnosis.fromException(ConnectException(), "api.groq.com")
        )
    }

    @Test
    fun `tls and generic io failures still name the host`() {
        val tls = ConnectionDiagnosis.fromException(SSLHandshakeException("bad cert"), "api.groq.com")
        assertTrue(tls is ConnectionFailure.NetworkError)
        assertTrue(tls.message.contains("api.groq.com"))

        val io = ConnectionDiagnosis.fromException(IOException("socket closed"), "api.groq.com")
        assertTrue(io is ConnectionFailure.NetworkError)
    }

    @Test
    fun `401 and 403 are both reported as an invalid api key`() {
        listOf(401, 403).forEach { status ->
            val failure = ConnectionDiagnosis.fromHttpStatus(status, """{"error":{"message":"Invalid Authentication"}}""", MODEL)
            assertEquals(ConnectionFailure.InvalidKey(status), failure)
            assertTrue(failure.message.contains("API key"))
        }
    }

    @Test
    fun `404 with no model complaint is a wrong endpoint path`() {
        val failure = ConnectionDiagnosis.fromHttpStatus(404, "<html>Not Found</html>", MODEL)
        assertEquals(ConnectionFailure.WrongEndpoint, failure)
        assertTrue(failure.message.contains("/v1/chat/completions"))
    }

    @Test
    fun `429 is reported as rate limited and reassures the key is fine`() {
        val failure = ConnectionDiagnosis.fromHttpStatus(429, """{"error":{"message":"slow down"}}""", MODEL)
        assertEquals(ConnectionFailure.RateLimited, failure)
        assertTrue(failure.message.contains("429"))
    }

    @Test
    fun `other 4xx surfaces the status code and the provider detail`() {
        val failure = ConnectionDiagnosis.fromHttpStatus(422, """{"error":{"message":"messages required"}}""", MODEL)
        assertTrue(failure is ConnectionFailure.ProviderError)
        assertEquals(422, (failure as ConnectionFailure.ProviderError).status)
        assertTrue(failure.message.contains("422"))
        assertTrue(failure.message.contains("messages required"))
    }

    @Test
    fun `5xx is attributed to the provider and shows the status code`() {
        val failure = ConnectionDiagnosis.fromHttpStatus(503, """{"error":{"message":"overloaded"}}""", MODEL)
        assertTrue(failure is ConnectionFailure.ProviderError)
        assertTrue(failure.message.contains("503"))
        assertTrue(failure.message.contains("server error"))
    }

    @Test
    fun `unknown model is distinguished from a wrong path despite both being 404`() {
        val body = """{"error":{"message":"The model `$MODEL` does not exist","code":"model_not_found"}}"""
        val failure = ConnectionDiagnosis.fromHttpStatus(404, body, MODEL)
        assertTrue(failure is ConnectionFailure.ModelRejected)
        assertEquals(MODEL, (failure as ConnectionFailure.ModelRejected).model)
        assertTrue(failure.message.contains(MODEL))
    }

    @Test
    fun `unknown model reported as 400 is also recognised`() {
        val body = """{"error":{"message":"$MODEL is not a valid model ID"}}"""
        val failure = ConnectionDiagnosis.fromHttpStatus(400, body, MODEL)
        assertTrue(failure is ConnectionFailure.ModelRejected)
    }

    @Test
    fun `a key error that happens to mention models is not misread as a model problem`() {
        val body = """{"error":{"message":"Incorrect API key provided"}}"""
        assertEquals(ConnectionFailure.InvalidKey(401), ConnectionDiagnosis.fromHttpStatus(401, body, MODEL))
    }

    @Test
    fun `non-json success body is reported as malformed json`() {
        assertEquals(
            ConnectionFailure.MalformedJson,
            ConnectionDiagnosis.fromSuccessBody("<html><body>Hello</body></html>", MODEL)
        )
    }

    @Test
    fun `valid json that is not a chat completion is reported as a schema mismatch`() {
        assertEquals(
            ConnectionFailure.UnexpectedSchema,
            ConnectionDiagnosis.fromSuccessBody("""{"result":"ok"}""", MODEL)
        )
        assertEquals(
            ConnectionFailure.UnexpectedSchema,
            ConnectionDiagnosis.fromSuccessBody("""{"choices":[]}""", MODEL)
        )
        assertEquals(
            ConnectionFailure.UnexpectedSchema,
            ConnectionDiagnosis.fromSuccessBody("""{"choices":[{"finish_reason":"stop"}]}""", MODEL)
        )
        assertEquals(
            ConnectionFailure.UnexpectedSchema,
            ConnectionDiagnosis.fromSuccessBody("[1,2,3]", MODEL)
        )
        assertEquals(ConnectionFailure.UnexpectedSchema, ConnectionDiagnosis.fromSuccessBody("", MODEL))
    }

    @Test
    fun `an error envelope returned with http 200 is still a failure`() {
        val failure = ConnectionDiagnosis.fromSuccessBody("""{"error":{"message":"quota exceeded"}}""", MODEL)
        assertTrue(failure is ConnectionFailure.ProviderError)
        assertTrue(failure!!.message.contains("quota exceeded"))
    }

    @Test
    fun `a model rejection returned with http 200 is reported as a model problem`() {
        val body = """{"error":{"message":"unknown model $MODEL"}}"""
        assertTrue(ConnectionDiagnosis.fromSuccessBody(body, MODEL) is ConnectionFailure.ModelRejected)
    }

    @Test
    fun `a well-formed chat completion is the success case`() {
        val body = """
            {"id":"cmpl-1","object":"chat.completion","choices":[
              {"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}
            ]}
        """.trimIndent()
        assertNull(ConnectionDiagnosis.fromSuccessBody(body, MODEL))
    }

    @Test
    fun `a streamed-shaped chat completion is also accepted`() {
        val body = """{"choices":[{"index":0,"delta":{"content":"pong"}}]}"""
        assertNull(ConnectionDiagnosis.fromSuccessBody(body, MODEL))
    }

    @Test
    fun `unknown keys in an otherwise valid completion do not fail the schema check`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"pong","refusal":null}}],"x_extra":1}"""
        assertNull(ConnectionDiagnosis.fromSuccessBody(body, MODEL))
    }

    @Test
    fun `error detail is extracted from the several envelopes providers use`() {
        assertEquals("boom", ConnectionDiagnosis.extractErrorDetail("""{"error":"boom"}"""))
        assertTrue(ConnectionDiagnosis.extractErrorDetail("""{"error":{"message":"boom"}}""")!!.contains("boom"))
        assertEquals("boom", ConnectionDiagnosis.extractErrorDetail("""{"detail":"boom"}"""))
        assertNull(ConnectionDiagnosis.extractErrorDetail("<html>nope</html>"))
        assertNull(ConnectionDiagnosis.extractErrorDetail(null))
    }

    @Test
    fun `every failure carries a non-generic message`() {
        val failures = listOf(
            ConnectionFailure.EmptyUrl,
            ConnectionFailure.MalformedUrl("x"),
            ConnectionFailure.EmptyKey,
            ConnectionFailure.EmptyModel,
            ConnectionFailure.Offline,
            ConnectionFailure.UnknownHost("h"),
            ConnectionFailure.Timeout("h"),
            ConnectionFailure.Unreachable("h"),
            ConnectionFailure.InvalidKey(401),
            ConnectionFailure.WrongEndpoint,
            ConnectionFailure.RateLimited,
            ConnectionFailure.ModelRejected(MODEL, "nope"),
            ConnectionFailure.ProviderError(500, "nope"),
            ConnectionFailure.MalformedJson,
            ConnectionFailure.UnexpectedSchema,
            ConnectionFailure.NetworkError("h", "nope")
        )
        failures.forEach { failure ->
            assertNotNull(failure.message)
            assertTrue("message too short: $failure", failure.message.length > 20)
            assertTrue(
                "generic message: ${failure.message}",
                !failure.message.contains("something went wrong", ignoreCase = true)
            )
        }
        assertEquals("every failure type should be covered", failures.size, failures.distinct().size)
    }
}
