package com.skeler.scanely.core.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class DesignRecoveryTest {
    private fun pending(): Response<ResponseBody> = Response.error(400,
        """{"error":{"code":"bad_request","reason":"duplicate_request","message":"Already processing"}}"""
            .toResponseBody())

    @Test fun waitsForExistingDesignToFinish() = runBlocking {
        var calls = 0
        val pdf = Response.success("pdf".toResponseBody())
        val result = awaitDesignResponse(pollDelayMs = 1) {
            if (++calls < 3) pending() else pdf
        }
        assertSame(pdf, result)
        assertEquals(3, calls)
    }

    @Test fun ordinaryBadRequestIsNotPolled() = runBlocking {
        var calls = 0
        try {
            awaitDesignResponse(pollDelayMs = 1) {
                calls++
                Response.error(400,
                    """{"error":{"code":"bad_request","message":"Invalid prompt"}}""".toResponseBody())
            }
            fail("Expected an actionable error")
        } catch (e: FatalAiException) {
            assertEquals("Invalid prompt", e.result.message)
        }
        assertEquals(1, calls)
    }

    @Test fun stoppingCancelsPolling() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            awaitDesignResponse {
                calls++
                started.complete(Unit)
                pending()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }
}
