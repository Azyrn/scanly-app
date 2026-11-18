package com.skeler.scanely.core.ai

import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.OcrBlockDto
import com.skeler.scanely.core.network.OcrPageDto
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.ToolDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.invocation.InvocationOnMock
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * An online read is billed on the server the moment it succeeds there, so the phone has to name it
 * before it asks, not after the answer arrives: an answer lost on the way back (a timeout, a dropped
 * connection) used to leave a charge nothing knew about. A read the phone gives up on is either
 * collected by asking again with the SAME idempotency key (the server replays a finished one, and
 * answers duplicate_request while it still runs) or given back.
 */
class BackendPageReaderChargeTest {
    @get:Rule val folder = TemporaryFolder()

    /** What each call to the server does, in order; past the end, the last one repeats. */
    private var answers: List<(String) -> Any> = emptyList()
    private val keysSent = mutableListOf<String>()
    private val events = mutableListOf<String>()

    private val client = mock(BackendClient::class.java) { call: InvocationOnMock ->
        if (call.method.name != "ocrBlocks") return@mock null
        val key = call.arguments[1] as String
        keysSent += key
        events += "send:$key"
        answers[minOf(keysSent.size, answers.size) - 1](key)
    }
    private val qpdf = mock(QpdfEngine::class.java) { QpdfResult.Ok }
    private val charges = mock(ConversionCharges::class.java) { call: InvocationOnMock ->
        val key = call.arguments.firstOrNull() as? String
        events += "${call.method.name}:$key"
        if (call.method.name.startsWith("refund")) 0 else null
    }
    private val prompt = mock(OnlineReadingPrompt::class.java) { call: InvocationOnMock -> if (call.method.name == "ask") true else null }
    private val auth = mock(AuthRepository::class.java) { true }
    private val network = mock(NetworkObserver::class.java) { true }
    private val reader = BackendPageReader(client, qpdf, charges, prompt, auth, network)

    private fun document() = ToolDocument(folder.newFile("a.pdf"), "a.pdf", 1, 5, "", false)
    private val page = OcrPageDto(index = 0, blocks = listOf(OcrBlockDto("text", listOf(0f, 0f, 1f, 1f), "Hello")))

    @Test fun `the read is named before it is sent`() = runTest {
        answers = listOf({ _ -> listOf(page) })
        val read = reader.read(document(), listOf(0), folder.root)
        assertEquals(1, read.size)
        val key = keysSent.single()
        assertTrue("recorded before the request: $events", events.indexOf("record:$key") in 0 until events.indexOf("send:$key"))
    }

    @Test fun `a lost answer is collected by asking again with the same key`() = runTest {
        answers = listOf({ _ -> throw SocketTimeoutException("read timed out") }, { _ -> listOf(page) })
        val read = reader.read(document(), listOf(0), folder.root)
        assertEquals("the replayed answer is used", 1, read.size)
        assertEquals(2, keysSent.size)
        assertEquals("the same key both times", 1, keysSent.toSet().size)
        assertTrue("nothing to give back: $events", events.none { it.startsWith("refund") })
    }

    @Test fun `a read still running on the server is waited for, not sent again as new`() = runTest {
        answers = listOf(
            { _ -> throw IOException("connection reset") },
            { _ -> throw TransientAiException("still processing", 5) },
            { _ -> listOf(page) }
        )
        assertEquals(1, reader.read(document(), listOf(0), folder.root).size)
        assertEquals(1, keysSent.toSet().size)
    }

    @Test fun `a read given up on is refunded under the key that was sent`() = runTest {
        answers = listOf({ _ -> throw SocketTimeoutException("read timed out") })
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), listOf(0), folder.root))
        val key = keysSent.toSet().single()
        assertTrue("refunded under the key it was billed under: $events", "refund:$key" in events)
    }

    // N8: the user chose "Use credits"; a read that comes back with nothing must say so, not end silently.
    @Test fun `a paid read that fails is reported to the tool screen`() = runTest {
        answers = listOf({ _ -> throw SocketTimeoutException("read timed out") })
        reader.read(document(), listOf(0), folder.root)
        verify(prompt).reportFailure()
    }

    @Test fun `a paid read that comes back short is reported too`() = runTest {
        answers = listOf({ _ -> listOf(page, page) })
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), listOf(0), folder.root))
        verify(prompt).reportFailure()
    }

    @Test fun `a read that succeeds reports nothing`() = runTest {
        answers = listOf({ _ -> listOf(page) })
        reader.read(document(), listOf(0), folder.root)
        verify(prompt, never()).reportFailure()
    }
}
