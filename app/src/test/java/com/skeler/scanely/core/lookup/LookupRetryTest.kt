package com.skeler.scanely.core.lookup

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.di.LookupModule
import com.skeler.scanely.core.di.NetworkModule
import com.skeler.scanely.core.food.OpenFoodFactsApi
import com.skeler.scanely.core.lookup.engines.OpenBeautyFactsEngine
import com.skeler.scanely.core.lookup.engines.OpenFoodFactsEngine
import com.skeler.scanely.core.network.NetworkObserver
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Barcode lookup against real failures, through the real engines, orchestrator and retry loop.
 *
 * The transport is real: the production lookup OkHttpClient, with one interceptor that sends the
 * engines' hard-coded hosts to this machine instead. A refused connection is a closed local port,
 * a server error is a JDK HttpServer answering 503, a stall is a socket that accepts and never
 * answers. Only connectivity is stubbed — whether the phone has a network is an input here.
 */
class LookupRetryTest {

    private val barcode = "3017620422003"
    private var server: HttpServer? = null
    private var stalled: ServerSocket? = null

    /** Requests the server received, by engine. Counts what reached the wire, not what was called. */
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    @After fun tearDown() {
        server?.stop(0)
        stalled?.close()
    }

    /** Serves `/food/...` and `/beauty/...`; each script entry is one response, the last repeats. */
    private fun serve(food: List<Pair<Int, String>>, beauty: List<Pair<Int, String>>): Int {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, script) in listOf("food" to food, "beauty" to beauty)) {
            http.createContext("/$path/") { exchange ->
                val n = hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val (status, body) = script[minOf(n, script.size) - 1]
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong().coerceAtLeast(1))
                exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) else it.write(' '.code) }
            }
        }
        http.start()
        server = http
        return http.address.port
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /** Accepts connections and never writes a byte back. */
    private fun stallingPort(): Int {
        val socket = ServerSocket(0)
        stalled = socket
        Thread {
            val held = mutableListOf<java.net.Socket>()
            while (!socket.isClosed) runCatching { held += socket.accept() }
        }.apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** The production client, re-pointed at 127.0.0.1:[port]. Food and beauty keep separate paths. */
    private fun client(port: Int, attempts: AtomicInteger = AtomicInteger()): OkHttpClient =
        LookupModule.provideOkHttpClient(NetworkModule.provideBaseHttpClient()).newBuilder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                val original = chain.request().url
                val prefix = if (original.host.contains("openbeautyfacts")) "beauty" else "food"
                val url = original.newBuilder()
                    .scheme("http").host("127.0.0.1").port(port)
                    .encodedPath("/$prefix${original.encodedPath}")
                    .build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
            .build()

    private fun engines(client: OkHttpClient): Set<LookupEngine> {
        val api = Retrofit.Builder()
            .baseUrl(OpenFoodFactsApi.BASE_URL)
            .client(client)
            .addConverterFactory(LookupJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenFoodFactsApi::class.java)
        return setOf(OpenFoodFactsEngine(api), OpenBeautyFactsEngine(client))
    }

    private fun lookup(client: OkHttpClient, online: Boolean = true): LookupResult = runBlocking {
        val network = mock(NetworkObserver::class.java)
        `when`(network.isCurrentlyOnline()).thenReturn(online)
        LookupOrchestrator(engines(client), network).lookup(barcode)
    }

    private fun LookupResult.errorMessage(): String {
        assertTrue("expected an error, got $this", this is LookupResult.Error)
        return (this as LookupResult.Error).exception.message.orEmpty()
    }

    private val notFound = 404 to """{"status":"failure","product":null}"""
    private val unavailable = 503 to "Service Unavailable"
    private val soap = 200 to """{"status":"success","product":{"product_name":"Test Soap"}}"""

    @Test fun `a refused connection is retried, then reported as the server being unreachable`() {
        val attempts = AtomicInteger()
        val message = lookup(client(closedPort(), attempts), online = true).errorMessage()

        assertEquals("two engines, two attempts each", 4, attempts.get())
        assertFalse("told an online user they are offline: \"$message\"", message.contains("internet", true))
        assertTrue("does not say what failed: \"$message\"", message.contains("couldn't reach", true))
    }

    @Test fun `the same refusal with no network is reported as no internet`() {
        val message = lookup(client(closedPort()), online = false).errorMessage()
        assertEquals("No internet connection.", message)
    }

    @Test fun `a 503 is retried and the second answer is used`() {
        val port = serve(food = listOf(notFound), beauty = listOf(unavailable, soap))
        val result = lookup(client(port))

        assertEquals(2, hits["beauty"]?.get())
        assertTrue("expected the product, got $result", result is LookupResult.Found)
        assertEquals("Test Soap", (result as LookupResult.Found).product.name)
    }

    @Test fun `a source that keeps answering 503 is retried once, then reported as a server problem`() {
        val port = serve(food = listOf(unavailable), beauty = listOf(unavailable))
        val message = lookup(client(port)).errorMessage()

        assertEquals(2, hits["food"]?.get())
        assertEquals(2, hits["beauty"]?.get())
        assertFalse(message.contains("internet", true))
        assertTrue("does not blame the server: \"$message\"", message.contains("server", true))
    }

    @Test fun `a 404 is a real not found and is asked once`() {
        val port = serve(food = listOf(notFound), beauty = listOf(notFound))
        val result = lookup(client(port))

        assertTrue("expected not found, got $result", result is LookupResult.NotFound)
        assertEquals(1, hits["food"]?.get())
        assertEquals(1, hits["beauty"]?.get())
    }

    @Test fun `a 403 is not retried`() {
        val port = serve(food = listOf(403 to "{}"), beauty = listOf(403 to "{}"))
        lookup(client(port)).errorMessage()

        assertEquals(1, hits["food"]?.get())
        assertEquals(1, hits["beauty"]?.get())
    }

    @Test fun `not found in one source while another is down names the one that could not answer`() {
        val port = serve(food = listOf(notFound), beauty = listOf(unavailable))
        val result = lookup(client(port))

        assertTrue("expected not found, got $result", result is LookupResult.NotFound)
        val source = (result as LookupResult.NotFound).source
        assertTrue("unreachable source not named: \"$source\"", source.contains("Open Beauty Facts"))
        assertFalse("a failed source listed as searched cleanly: \"$source\"", source.contains("503"))
    }

    @Test fun `a stalled server times out per attempt and is retried, not held for minutes`() {
        val attempts = AtomicInteger()
        val started = System.nanoTime()
        val message = lookup(client(stallingPort(), attempts)).errorMessage()
        val seconds = (System.nanoTime() - started) / 1e9

        assertEquals("two engines, two attempts each", 4, attempts.get())
        assertTrue("took ${seconds}s", seconds < 25)
        assertTrue("does not say what failed: \"$message\"", message.contains("couldn't reach", true))
    }
}
