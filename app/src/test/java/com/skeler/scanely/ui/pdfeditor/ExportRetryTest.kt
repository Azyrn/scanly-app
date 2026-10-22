package com.skeler.scanely.ui.pdfeditor

import com.skeler.scanely.core.pdfeditor.ExportRetry
import com.skeler.scanely.core.pdfeditor.canSave
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PDF Studio applies the edits, adopts the result as the working document — which clears the
 * edits — and then writes it to Downloads. When that write failed, Save went disabled with nothing
 * staged, and the edited document could not be exported again.
 *
 * The failure is real: a copy into a directory the process may not write to.
 */
class ExportRetryTest {

    private val root = Files.createTempDirectory("export-retry").toFile()
    private val saved = File(root, "edited.pdf").apply { writeText("%PDF-1.7 edited") }
    private val downloads = File(root, "Downloads").apply { mkdirs() }

    @After fun tearDown() {
        downloads.setWritable(true)
        root.deleteRecursively()
    }

    private val toDownloads: suspend (File) -> File? = { file -> file.copyTo(File(downloads, file.name)) }

    @Test
    fun `a save that could not reach Downloads can be saved again`() = runBlocking {
        val retry = ExportRetry()
        downloads.setWritable(false)

        assertNull(retry.export(saved, toDownloads))

        assertEquals("the edited file is still waiting", saved, retry.unexported)
        assertTrue("Save stays enabled with no edits staged", canSave(isDirty = false, retry))
    }

    @Test
    fun `saving again once Downloads is writable exports it and clears the retry`() = runBlocking {
        val retry = ExportRetry()
        downloads.setWritable(false)
        retry.export(saved, toDownloads)

        downloads.setWritable(true)
        val exported = retry.export(retry.unexported!!, toDownloads)

        assertEquals("%PDF-1.7 edited", exported!!.readText())
        assertNull(retry.unexported)
        assertFalse(canSave(isDirty = false, retry))
    }
}
