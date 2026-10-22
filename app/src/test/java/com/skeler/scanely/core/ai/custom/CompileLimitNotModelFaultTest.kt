package com.skeler.scanely.core.ai.custom

import android.content.Context
import com.skeler.scanely.core.network.CustomProviderApi
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.ocr.PdfRendererHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import retrofit2.Response

/**
 * H7: a compile that ran out of the container's time or memory is billed. It used to come back
 * looking like a free markup rejection, so the designer sent a repair — a second billed compile of
 * a document that was still too heavy — and then blamed the user's model. Real HTTP to a local
 * stand-in for the Worker; the user's endpoint is a fake that writes the same Typst every time.
 */
class CompileLimitNotModelFaultTest {

    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    private var endpointCalls = 0
    private val endpoint = object : CustomProviderApi {
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: JsonObject
        ): Response<ResponseBody> {
            endpointCalls++
            val completion = """{"choices":[{"message":{"role":"assistant","content":"= Report\nBody"}}]}"""
            return Response.success(completion.toResponseBody("application/json".toMediaType()))
        }
    }

    private val designer = CustomDesigner(
        worker.api(),
        CustomScanner(mock(Context::class.java), endpoint, mock(PdfRendererHelper::class.java))
    )
    private val config = CustomProviderConfig(
        url = "https://models.example/v1/chat/completions",
        apiKey = "user-key",
        model = "user-model"
    )

    private fun design() = runBlocking {
        designer.design("a report", null, null, null, null, config) {}
    }

    @Test
    fun `a billed over-limit compile stops, and does not blame the model`() {
        worker.route("/v1/design/prompt") { 200 to """{"system":"s","user":"u"}""" }
        worker.route("/v1/design/compile") {
            200 to """{"ok":false,"reason":"resources","billed":true,"pagesBilled":1,"diagnostics":"error: out of time"}"""
        }

        val result = design()

        assertEquals("no repair after a billed limit failure", 1, worker.count("/v1/design/compile"))
        val message = (result as CustomDesignResult.Failure).message
        assertTrue(message, message.contains("too heavy"))
        assertTrue(message, message.contains("1 credit"))
        assertFalse(message, message.contains("user-model"))
    }

    @Test
    fun `a markup rejection is still repaired once`() {
        worker.route("/v1/design/prompt") { 200 to """{"system":"s","user":"u"}""" }
        worker.route("/v1/design/compile") {
            200 to """{"ok":false,"reason":"markup","billed":false,"diagnostics":"error: unknown variable"}"""
        }

        val result = design()

        assertEquals(2, worker.count("/v1/design/compile"))
        assertEquals(2, endpointCalls)
        assertTrue((result as CustomDesignResult.Failure).message.contains("user-model"))
    }
}
