package com.skeler.scanely.core.ai

import com.skeler.scanely.core.network.DesignRequest
import com.skeler.scanely.core.network.ScanlyApi
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import retrofit2.Response

class DesignResponseContractTest {
    @Test fun `PDF response preserves document bytes owned ID and server billing`() = runBlocking {
        // worker/src/routes/design.ts: pdfResponse, also used by explicit source compile.
        val bytes = "%PDF-1.4\nfixture".toByteArray()
        val headers = Headers.headersOf("Content-Type", "application/pdf", "X-Design-Id", "owned-id", "X-Usage-Pages", "3")
        val api = mock(ScanlyApi::class.java)
        // The app locale travels with every design: it labels asked fields and sets the digit style.
        `when`(api.design(DesignRequest("invoice", locale = java.util.Locale.getDefault().toLanguageTag()), "action-key"))
            .thenReturn(Response.success(bytes.toResponseBody(), headers))
        val result = BackendClient(api).design("invoice", null, null, null, null, "action-key")
        assertArrayEquals(bytes, result.pdf)
        assertEquals("owned-id", result.designId)
        assertEquals(3, result.pagesBilled)
    }

    @Test fun `an in flight design polls the same operation until its PDF arrives`() = runBlocking {
        // Exact error shape from worker/src/idempotency.ts.
        var calls = 0
        val response = awaitDesignResponse(pollDelayMs = 0) {
            if (++calls == 1) Response.error(400,
                """{"error":{"code":"bad_request","message":"This scan is already being processed. Check your history in a moment.","reason":"duplicate_request"}}""".toResponseBody())
            else Response.success("%PDF-fixture".toResponseBody())
        }
        response.body()!!.close()
        assertEquals(2, calls)
    }

    @Test fun `a background rebuild answers 202 then the PDF, and the job is announced once`() = runBlocking {
        // worker/src/routes/design.ts: 202 {jobId, deadlineSeconds}; GET /v1/design/jobs/:id.
        val api = mock(ScanlyApi::class.java)
        val request = DesignRequest("rebuild", document = "doc", locale = java.util.Locale.getDefault().toLanguageTag())
        `when`(api.design(request, "key")).thenReturn(
            Response.success(202, """{"jobId":"job-1","deadlineSeconds":300}""".toResponseBody())
        )
        val pdf = Headers.headersOf("Content-Type", "application/pdf", "X-Design-Id", "job-1", "X-Usage-Pages", "4")
        var polls = 0
        `when`(api.designJob("job-1")).thenAnswer {
            if (++polls < 3) Response.success(202, """{"state":"running"}""".toResponseBody())
            else Response.success("%PDF-job".toByteArray().toResponseBody(), pdf)
        }
        val announced = mutableListOf<String>()
        val client = BackendClient(api)
        val result = kotlinx.coroutines.withTimeout(60_000) {
            client.design("rebuild", null, null, null, "doc", "key", onBackground = { announced += it })
        }
        assertEquals(listOf("job-1"), announced)
        assertEquals("job-1", result.designId)
        assertEquals(4, result.pagesBilled)
        assertEquals(3, polls)
    }

    @Test fun `a failed background job is final and carries the server's not-charged word`() = runBlocking {
        val failed = """{"error":{"code":"provider_error","message":"The designer took too long. Try again.","notCharged":true}}"""
        val error = try {
            awaitDesignJob(300, pollDelayMs = 0) { Response.error(502, failed.toResponseBody()) }
            null
        } catch (e: FatalAiException) { e.result }
        assertEquals("provider_error", error!!.code)
        assertTrue(error.notCharged)
        // A failure the server did not vouch for says nothing about billing.
        val silent = try {
            awaitDesignJob(300, pollDelayMs = 0) { Response.error(500, "{}".toResponseBody()) }
            null
        } catch (e: FatalAiException) { e.result }
        assertFalse(silent!!.notCharged)
    }

    @Test fun `a chat design follows the job's live steps then fetches the PDF once`() = runBlocking {
        // worker/src/design-job.ts: event-stream of step events ending with done; then GET jobs/:id.
        val api = mock(ScanlyApi::class.java)
        val request = DesignRequest("invoice", locale = java.util.Locale.getDefault().toLanguageTag())
        `when`(api.designChat(request, "key")).thenReturn(
            Response.success(202, """{"jobId":"job-2","deadlineSeconds":300}""".toResponseBody())
        )
        val stream = listOf(
            """event: step""", """data: {"type":"step","step":"planning","at":10}""", "",
            """: ping""", "",
            """event: step""", """data: {"type":"step","step":"writing","at":20}""", "",
            """event: done""", """data: {"type":"done","designId":"job-2","pagesBilled":2,"steps":[]}""", ""
        ).joinToString("\n")
        `when`(api.designJobEvents("job-2")).thenReturn(Response.success(stream.toResponseBody()))
        val pdf = Headers.headersOf("Content-Type", "application/pdf", "X-Design-Id", "job-2", "X-Usage-Pages", "2")
        `when`(api.designJob("job-2")).thenReturn(Response.success("%PDF-chat".toByteArray().toResponseBody(), pdf))
        val steps = mutableListOf<String>()
        val result = BackendClient(api).design(
            "invoice", null, null, null, null, "key", chat = true, onStep = { id, _ -> steps += id }
        )
        assertEquals(listOf("planning", "writing"), steps)
        assertEquals("job-2", result.designId)
        verify(api, times(1)).designJob("job-2")
        verify(api, never()).design(request, "key")
        Unit
    }

    @Test fun `without the events stream a chat design still reports steps from polling`() = runBlocking {
        val api = mock(ScanlyApi::class.java)
        val request = DesignRequest("invoice", locale = java.util.Locale.getDefault().toLanguageTag())
        `when`(api.designChat(request, "key")).thenReturn(
            Response.success(202, """{"jobId":"job-3","deadlineSeconds":300}""".toResponseBody())
        )
        `when`(api.designJobEvents("job-3")).thenReturn(Response.error(500, "{}".toResponseBody()))
        val pdf = Headers.headersOf("Content-Type", "application/pdf", "X-Design-Id", "job-3", "X-Usage-Pages", "1")
        var polls = 0
        `when`(api.designJob("job-3")).thenAnswer {
            if (++polls == 1) {
                Response.success(
                    202,
                    """{"state":"running","step":"building","steps":[{"step":"planning","at":1},{"step":"building","at":5}]}"""
                        .toResponseBody()
                )
            } else {
                Response.success("%PDF-poll".toByteArray().toResponseBody(), pdf)
            }
        }
        val steps = mutableListOf<String>()
        val result = kotlinx.coroutines.withTimeout(60_000) {
            BackendClient(api).design("invoice", null, null, null, null, "key", chat = true, onStep = { id, _ -> steps += id })
        }
        assertEquals(listOf("planning", "building"), steps)
        assertEquals("job-3", result.designId)
    }
}
