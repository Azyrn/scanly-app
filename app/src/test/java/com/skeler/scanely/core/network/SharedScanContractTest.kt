package com.skeler.scanely.core.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The Worker handler tests consume this very same file. */
class SharedScanContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun allThreeEndpointsDecodeSuccessReplayAndErrors() {
        val fixtures = json.parseToJsonElement(checkNotNull(javaClass.getResource("/scan-responses.json")).readText()).jsonArray
        assertEquals(18, fixtures.size)
        for (element in fixtures) {
            val fixture = element.jsonObject
            val status = fixture.getValue("status").jsonPrimitive.int
            val body = fixture.getValue("body")
            if (status == 200) {
                if (fixture.getValue("endpoint").jsonPrimitive.content == "ocr") {
                    val response = json.decodeFromJsonElement<OcrResponse>(body)
                    assertEquals("Hello 文😀", response.pages.single().markdown)
                    assertNull(response.extraction)
                    assertEquals(1, response.usage.pages)
                } else {
                    val stream = body.jsonPrimitive.content
                    assertTrue(stream.endsWith("data: [DONE]\n\n"))
                    val chunk = stream.lineSequence().first().removePrefix("data: ")
                    assertEquals("Hello 文😀", json.decodeFromString<ChatStreamChunk>(chunk).choices.single().delta?.content)
                }
            } else {
                val error = json.decodeFromJsonElement<BackendErrorEnvelope>(body).error!!
                assertEquals(
                    mapOf(413 to "doc_too_large", 429 to "rate_limited", 502 to "provider_error", 503 to "ocr_unavailable")[status],
                    error.code
                )
                // Busy (429) and failed (502) are retried; a refused provider key (503) is final.
                assertEquals(
                    status == 429 || status == 502,
                    com.skeler.scanely.core.ai.backendFailure(status, error) is com.skeler.scanely.core.ai.TransientAiException
                )
                assertNull(error.requiredTier)
                assertEquals(if (status == 429) 60 else null, error.retryAfterSeconds)
            }
        }
    }
}
