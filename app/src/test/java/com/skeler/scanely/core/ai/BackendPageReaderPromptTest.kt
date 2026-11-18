package com.skeler.scanely.core.ai

import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdftools.ToolDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * Online reading costs credits, so nothing leaves the phone unless the user chose "Use credits" for
 * exactly the flagged pages. No flagged pages, offline, signed out, or "Convert without": the PDF's
 * own text, no question asked where none applies.
 */
class BackendPageReaderPromptTest {
    @get:Rule val folder = TemporaryFolder()

    private val client = mock(BackendClient::class.java)
    private val qpdf = mock(QpdfEngine::class.java)
    private val prompt = mock(OnlineReadingPrompt::class.java)
    private val auth = mock(AuthRepository::class.java)
    private val network = mock(NetworkObserver::class.java)
    private val reader = BackendPageReader(client, qpdf, mock(ConversionCharges::class.java), prompt, auth, network)
    private fun document() = ToolDocument(folder.newFile("a.pdf"), "a.pdf", 1, 5, "", false)

    private fun online(signedIn: Boolean = true, connected: Boolean = true) {
        `when`(auth.isSignedIn).thenReturn(signedIn)
        `when`(network.isCurrentlyOnline()).thenReturn(connected)
    }

    @Test fun `no flagged pages - free, no question, nothing sent`() = runBlocking {
        online()
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), emptyList(), folder.root))
        verifyNoInteractions(prompt, client, qpdf)
    }

    @Test fun `the question names exactly the flagged pages, and Convert without sends nothing`() = runBlocking {
        online()
        `when`(prompt.ask(3)).thenReturn(false)
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), listOf(0, 2, 4), folder.root))
        assertTrue(mockingDetails(prompt).invocations.any { it.method.name == "ask" && it.arguments[0] == 3 })
        verifyNoInteractions(client, qpdf)
    }

    @Test fun `Use credits - the flagged pages are prepared for sending`() = runBlocking {
        online()
        `when`(prompt.ask(2)).thenReturn(true)
        reader.read(document(), listOf(0, 1), folder.root)
        assertTrue("the pages were cut out to be sent", mockingDetails(qpdf).invocations.isNotEmpty())
    }

    @Test fun `offline - no question, nothing sent`() = runBlocking {
        online(connected = false)
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), listOf(0), folder.root))
        verifyNoInteractions(prompt, client, qpdf)
    }

    @Test fun `signed out - no question, nothing sent`() = runBlocking {
        online(signedIn = false)
        assertEquals(emptyMap<Int, Any>(), reader.read(document(), listOf(0), folder.root))
        verifyNoInteractions(prompt, client, qpdf)
    }
}
