package com.skeler.scanely.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the Worker on 127.0.0.1: a real HTTP server, so every call the app makes crosses
 * a socket and lands in [log] — the request log the call-count tests read.
 */
class LocalWorker : AutoCloseable {

    private class Route(val delayMs: Long, val answer: (call: Int) -> Pair<Int, String>) {
        val calls = AtomicInteger()
    }

    private val routes = ConcurrentHashMap<String, Route>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            log += "${exchange.requestMethod} $path"
            val route = routes[path]
            val (status, body) = route?.let {
                Thread.sleep(it.delayMs)
                it.answer(it.calls.incrementAndGet())
            } ?: (404 to """{"error":{"code":"not_found","message":"no route"}}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    /** Every request received, as "METHOD /path", in arrival order. */
    val log = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/"

    fun route(path: String, delayMs: Long = 0, answer: (call: Int) -> Pair<Int, String>) {
        routes[path] = Route(delayMs, answer)
    }

    fun count(path: String): Int = log.count { it.endsWith(" $path") }

    /** Returns once no request has arrived for [quietMs], so counts are final rather than sampled. */
    fun awaitQuiet(quietMs: Long = 1_000, maxMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + maxMs
        var seen = -1
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (log.size != seen) {
                seen = log.size
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince >= quietMs) {
                return
            }
            Thread.sleep(50)
        }
        error("still receiving requests after ${maxMs}ms: $log")
    }

    /** ScanlyApi over a plain client, with the Json settings the production Retrofit uses. */
    fun api(client: OkHttpClient = OkHttpClient()): ScanlyApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }.asConverterFactory("application/json".toMediaType())
        )
        .build()
        .create(ScanlyApi::class.java)

    override fun close() = server.stop(0)

    companion object {
        fun me(rewardsRemainingToday: Int = 5): Pair<Int, String> = 200 to """
            {"tier":"free","pagesRemainingMonth":10,"pagesRemainingDay":3,"rewardPagesPerAd":2,
             "rewardsRemainingToday":$rewardsRemainingToday,"pagesPerMonth":30,"maxPagesPerDoc":5,
             "maxUploadBytes":10000000,"allowsOffice":false,"allowsTranslation":false,
             "periodResetsAt":1790000000000}
        """.trimIndent()

        val licenseActive: Pair<Int, String> =
            200 to """{"tier":"pro","status":"active","grantsAccess":true}"""
    }
}
