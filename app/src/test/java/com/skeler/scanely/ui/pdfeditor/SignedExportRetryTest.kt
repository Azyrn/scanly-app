package com.skeler.scanely.ui.pdfeditor

import com.skeler.scanely.core.pdfeditor.ExportRetry
import com.skeler.scanely.core.pdfeditor.canSave
import com.skeler.scanely.core.pdfeditor.exportSigned
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * H10, the signing half: a signed PDF is adopted as the working document, which leaves nothing
 * staged. When its write to Downloads failed, nothing remembered it, Save stayed disabled, and the
 * only way to get the file out was to sign it again — a second signature.
 *
 * The failure is real: a copy into a directory the process may not write to.
 */
class SignedExportRetryTest {

    private val root = Files.createTempDirectory("signed-export").toFile()
    private val signed = File(root, "signed.pdf").apply { writeText("%PDF-1.7 signed") }
    private val downloads = File(root, "Downloads").apply { mkdirs() }

    @After fun tearDown() {
        downloads.setWritable(true)
        root.deleteRecursively()
    }

    private val toDownloads: suspend (File) -> File? = { file -> file.copyTo(File(downloads, file.name)) }

    @Test
    fun `a signed file that could not reach Downloads is left for Save to try again`() = runBlocking {
        val retry = ExportRetry()
        downloads.setWritable(false)

        val result = exportSigned(retry, signed, toDownloads)

        assertNull(result.saved)
        assertEquals(signed, retry.unexported)
        assertTrue("Save is enabled with nothing staged", canSave(isDirty = false, retry))
        assertTrue(result.message, result.message.contains("Tap Save to try again"))
    }

    @Test
    fun `Save exports the signed file once Downloads is writable, without signing again`() = runBlocking {
        val retry = ExportRetry()
        downloads.setWritable(false)
        exportSigned(retry, signed, toDownloads)

        downloads.setWritable(true)
        val exported = retry.export(retry.unexported!!, toDownloads)

        assertEquals("%PDF-1.7 signed", exported!!.readText())
        assertNull(retry.unexported)
    }
}
