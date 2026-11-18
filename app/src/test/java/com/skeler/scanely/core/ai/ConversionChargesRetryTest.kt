package com.skeler.scanely.core.ai

import com.skeler.scanely.core.network.ConversionRefundRequest
import com.skeler.scanely.core.network.ConversionRefundResponse
import com.skeler.scanely.core.network.ScanlyApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException

/**
 * A refund is the user's credits for a read a failed conversion paid for. One the server could not
 * answer for good (offline, a server error, or `not_found` for a read still running there) used to
 * be dropped, and the credits with it. It is now kept on disk and asked again — later, at the next
 * tool run, after the app restarts — until the server answers or its refund window is over. The
 * server flips a read to refunded once, so asking again never refunds twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversionChargesRetryTest {
    @get:Rule val folder = TemporaryFolder()

    /** What each refund call does, in order; past the end, the last one repeats. */
    private var answers: List<() -> Any> = emptyList()
    private val sent = mutableListOf<String>()

    private val api = mock(ScanlyApi::class.java) { call: InvocationOnMock ->
        if (call.method.name != "refundConversion") return@mock null
        sent += (call.arguments[0] as ConversionRefundRequest).key
        answers[minOf(sent.size, answers.size) - 1]()
    }
    private val file by lazy { File(folder.root, "pending-conversion-refunds") }

    private val offline: () -> Any = { throw IOException("offline") }
    private val notFound: () -> Any = { ConversionRefundResponse(0, "not_found") }
    private fun refunded(pages: Int): () -> Any = { ConversionRefundResponse(pages) }
    private fun http(code: Int): () -> Any = { throw HttpException(Response.error<Any>(code, "".toResponseBody())) }

    /** A fresh app process over the same disk; whatever it does on start has run. */
    private fun TestScope.start(): ConversionCharges =
        ConversionCharges(api, file, { START + testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
            .also { runCurrent() }

    @Test fun `a refund that could not be sent is sent at the next tool run`() = runTest {
        answers = listOf(offline, refunded(2))
        val charges = start()
        assertEquals(0, charges.refund("k1"))
        charges.clear()
        runCurrent()
        assertEquals(listOf("k1", "k1"), sent)
        assertFalse("nothing left to ask", file.exists())
    }

    @Test fun `a refund kept on disk is sent when the app starts again`() = runTest {
        answers = listOf(offline, refunded(1))
        start().refund("k1")
        start()
        assertEquals(listOf("k1", "k1"), sent)
    }

    @Test fun `a refund is on disk while it is being sent, so a kill mid-call does not lose it`() = runTest {
        var onDiskDuringCall = false
        answers = listOf({
            onDiskDuringCall = file.exists() && file.readText().contains("k1")
            ConversionRefundResponse(2)
        })
        assertEquals(2, start().refund("k1"))
        assertTrue("kept before the call", onDiskDuringCall)
        assertFalse("dropped once answered", file.exists())
    }

    @Test fun `after a restart a refund the server cannot answer yet is asked again while the app runs`() = runTest {
        // Kept by an earlier process (killed before its own retries ran); this process only reads the file.
        file.writeText("k1\t$START\n")
        answers = listOf(notFound, notFound, refunded(3))
        start()
        advanceTimeBy(RETRY_DELAY_MS * 3)
        runCurrent()
        assertEquals(listOf("k1", "k1", "k1"), sent)
        assertFalse(file.exists())
    }

    @Test fun `a refund for a read still running on the server is asked again until the server has it`() = runTest {
        answers = listOf(notFound, notFound, refunded(3))
        val charges = start()
        charges.refund("k1")
        advanceTimeBy(RETRY_DELAY_MS * 3)
        runCurrent()
        assertEquals(listOf("k1", "k1", "k1"), sent)
        assertFalse(file.exists())
    }

    @Test fun `a server error is asked again, a refused request is not`() = runTest {
        answers = listOf(http(503), refunded(1))
        val charges = start()
        charges.refund("k1")
        charges.clear()
        runCurrent()
        assertEquals(listOf("k1", "k1"), sent)

        sent.clear()
        answers = listOf(http(400))
        charges.refund("k2")
        charges.clear()
        advanceTimeBy(RETRY_DELAY_MS * 2)
        runCurrent()
        assertEquals(listOf("k2"), sent)
    }

    @Test fun `a refund the server answered for good is not sent again`() = runTest {
        for (reason in listOf("already_refunded", "expired", "refund_limit")) {
            sent.clear()
            answers = listOf { ConversionRefundResponse(0, reason) }
            val charges = start()
            charges.refund("k-$reason")
            advanceTimeBy(RETRY_DELAY_MS * 2)
            charges.clear()
            runCurrent()
            assertEquals(reason, listOf("k-$reason"), sent)
        }
        start()
        assertEquals(1, sent.size)
    }

    @Test fun `a kept refund is given up once the server's window is over`() = runTest {
        answers = listOf(offline)
        start().refund("k1")
        advanceTimeBy(REFUND_WINDOW_MS + RETRY_DELAY_MS)
        runCurrent()
        val tries = sent.size
        start()
        assertEquals("not asked after the window", tries, sent.size)
        assertFalse(file.exists())
    }

    @Test fun `refundAll keeps every refund it could not send`() = runTest {
        answers = listOf(offline, offline, refunded(1))
        val charges = start()
        charges.record("a")
        charges.record("b")
        assertEquals(0, charges.refundAll())
        start()
        assertEquals(listOf("a", "b", "a", "b"), sent)
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
