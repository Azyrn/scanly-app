package com.skeler.scanely.core.network

import java.lang.reflect.Proxy
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BackendAuthInterceptorTest {
    private fun chain(statuses: List<Int>, requests: MutableList<Request>): Interceptor.Chain {
        val request = Request.Builder().url("https://example.invalid/v1/ocr")
            .header("X-Idempotency-Key", "scan-1").build()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    val sent = args!![0] as Request
                    requests += sent
                    Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                        .code(statuses[requests.lastIndex]).message("fixture")
                        .body("".toResponseBody()).build()
                }
                else -> error(method.name)
            }
        } as Interceptor.Chain
    }

    @Test fun `401 forces one fresh token and preserves idempotency key`() {
        val requests = mutableListOf<Request>()
        val refreshed = mutableListOf<Boolean>()
        chain(listOf(401, 200), requests).proceedWithToken {
            refreshed += it
            if (it) "fresh" else "cached"
        }.use { assertEquals(200, it.code) }
        assertEquals(listOf(false, true), refreshed)
        assertEquals(listOf("Bearer cached", "Bearer fresh"), requests.map { it.header("Authorization") })
        assertTrue(requests.all { it.header("X-Idempotency-Key") == "scan-1" })
    }

    @Test fun `a second 401 is returned without looping`() {
        val requests = mutableListOf<Request>()
        chain(listOf(401, 401), requests).proceedWithToken { "token" }.use { assertEquals(401, it.code) }
        assertEquals(2, requests.size)
    }

    @Test fun `quota and server failures are not replayed by auth`() {
        for (status in listOf(200, 402, 429, 500)) {
            val requests = mutableListOf<Request>()
            chain(listOf(status), requests).proceedWithToken { "token" }.close()
            assertEquals(1, requests.size)
        }
    }
}
