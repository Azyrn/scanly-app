package com.skeler.scanely.core.di

import com.skeler.scanely.core.ai.custom.AuthStyle
import com.sun.net.httpserver.HttpServer
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * N14: OkHttp follows a redirect to another host and drops only `Authorization` on the way
 * (RetryAndFollowUpInterceptor.buildRedirectRequest). A custom endpoint whose key travels in any
 * other header — x-api-key, x-goog-api-key, Azure's api-key, or the user's own header name — sent
 * that key to whatever host the endpoint redirected to. A redirect to the same origin keeps it.
 */
class CustomProviderRedirectTest {
    private val seen = ConcurrentHashMap<String, Map<String, String?>>()

    private fun server(name: String, answer: (com.sun.net.httpserver.HttpExchange) -> Unit) =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seen[name + exchange.requestURI.path] = AUTH_HEADERS.associateWith { exchange.requestHeaders.getFirst(it) }
                answer(exchange)
            }
            start()
        }

    private val ok: (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    private val elsewhere = server("other", ok)
    private val endpoint = server("endpoint") { exchange ->
        if (exchange.requestURI.path == "/v1/final") return@server ok(exchange)
        val target = if (exchange.requestURI.path == "/v1/away") {
            "http://127.0.0.1:${elsewhere.address.port}/v1/final"
        } else {
            "/v1/final"
        }
        exchange.responseHeaders.add("Location", target)
        exchange.sendResponseHeaders(307, -1)
        exchange.close()
    }

    @After fun tearDown() {
        endpoint.stop(0)
        elsewhere.stop(0)
    }

    private val api = CustomProviderModule.provideCustomProviderApi(Lazy { OkHttpClient() })
    private fun url(path: String) = "http://127.0.0.1:${endpoint.address.port}$path"

    @Test fun `a key in a non-Authorization header is not sent to the host a redirect points at`() = runBlocking {
        for (style in AuthStyle.entries) {
            seen.clear()
            val headers = style.headersFor(KEY, "X-Gateway-Token")
            val response = api.post(url("/v1/away"), headers, JsonObject(emptyMap()))
            assertEquals(style.name, 200, response.code())
            val leaked = seen["other/v1/final"]!!.filterValues { it != null && KEY in it }
            assertEquals("${style.name} sent its key to the other host", emptyMap<String, String?>(), leaked)
        }
    }

    @Test fun `a redirect within the same endpoint keeps the key`() = runBlocking {
        val headers = AuthStyle.X_API_KEY.headersFor(KEY, "")
        api.post(url("/v1/here"), headers, JsonObject(emptyMap()))
        assertEquals(KEY, seen["endpoint/v1/final"]!!["x-api-key"])
        assertNull(seen["other/v1/final"])
    }

    private companion object {
        const val KEY = "sk-secret-123"
        val AUTH_HEADERS = listOf("Authorization", "x-api-key", "x-goog-api-key", "api-key", "X-Gateway-Token")
    }
}
