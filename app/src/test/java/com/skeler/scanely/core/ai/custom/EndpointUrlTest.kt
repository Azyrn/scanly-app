package com.skeler.scanely.core.ai.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlTest {

    @Test
    fun `a scheme-less endpoint is made absolute rather than left relative`() {
        // The invariant that matters: a relative URL would be resolved against the client's base
        // URL, sending the user's key to the wrong host.
        val normalized = EndpointUrl.normalize("api.groq.com/openai/v1/chat/completions")
        assertEquals("https://api.groq.com/openai/v1/chat/completions", normalized)
        assertTrue(normalized!!.startsWith("https://"))
    }

    @Test
    fun `a bare v1 base gets the chat completions path appended`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointUrl.normalize("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointUrl.normalize("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `a full chat completions url is left alone`() {
        val url = "https://openrouter.ai/api/v1/chat/completions"
        assertEquals(url, EndpointUrl.normalize(url))
    }

    @Test
    fun `trailing whitespace and slashes are tolerated`() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            EndpointUrl.normalize("  https://api.groq.com/openai/v1/chat/completions/  ")
        )
    }

    @Test
    fun `unusable input is rejected rather than guessed at`() {
        assertNull(EndpointUrl.normalize(""))
        assertNull(EndpointUrl.normalize("   "))
        assertNull(EndpointUrl.normalize("not a url"))
        assertNull(EndpointUrl.normalize("ftp://files.example.com/v1"))
    }

    @Test
    fun `a self-hosted server on localhost defaults to http, not https`() {
        // Guessing https here would fail every Ollama and LM Studio setup.
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            EndpointUrl.normalize("localhost:11434/v1")
        )
    }

    @Test
    fun `a private LAN address also defaults to http`() {
        assertEquals(
            "http://192.168.1.50:8000/v1/chat/completions",
            EndpointUrl.normalize("192.168.1.50:8000/v1")
        )
    }

    @Test
    fun `a public host still defaults to https`() {
        assertTrue(EndpointUrl.normalize("api.groq.com/v1")!!.startsWith("https://"))
    }

    @Test
    fun `only loopback and RFC1918 ranges count as local`() {
        assertTrue(EndpointUrl.looksLocal("localhost:11434"))
        assertTrue(EndpointUrl.looksLocal("127.0.0.1"))
        assertTrue(EndpointUrl.looksLocal("10.0.2.2"))
        assertTrue(EndpointUrl.looksLocal("192.168.1.50"))
        assertTrue(EndpointUrl.looksLocal("172.16.0.9"))
        assertTrue(EndpointUrl.looksLocal("http://my-box.local/v1"))

        assertTrue(!EndpointUrl.looksLocal("api.groq.com"))
        assertTrue(!EndpointUrl.looksLocal("172.32.0.1"))
        assertTrue(!EndpointUrl.looksLocal("11.0.0.1"))
        assertTrue(!EndpointUrl.looksLocal("193.168.1.1"))
    }

    @Test
    fun `an anthropic base gets the messages path`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            EndpointUrl.normalize("https://api.anthropic.com/v1", ProviderProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `gemini builds the model into the path from a bare host`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            EndpointUrl.normalize(
                "https://generativelanguage.googleapis.com",
                ProviderProtocol.GEMINI,
                "gemini-2.0-flash"
            )
        )
    }

    @Test
    fun `gemini rewrites a pasted URL to use the model the user typed`() {
        // Otherwise the model field would silently disagree with the URL.
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
            EndpointUrl.normalize(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.0:generateContent",
                ProviderProtocol.GEMINI,
                "gemini-2.5-pro"
            )
        )
    }

    @Test
    fun `a cloudflare workers ai url is recognised so the account id hint can be shown`() {
        assertTrue(
            EndpointUrl.looksLikeCloudflare(
                "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1/chat/completions"
            )
        )
        assertTrue(!EndpointUrl.looksLikeCloudflare("https://api.groq.com/openai/v1/chat/completions"))
    }

    @Test
    fun `the host is extracted for error messages`() {
        assertEquals(
            "api.groq.com",
            EndpointUrl.hostOf("https://api.groq.com/openai/v1/chat/completions")
        )
    }
}
