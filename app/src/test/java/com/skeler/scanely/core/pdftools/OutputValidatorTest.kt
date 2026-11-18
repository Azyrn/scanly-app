package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A broken Office result is never offered: the checks that need no pdfium. */
class OutputValidatorTest {
    @get:Rule val folder = TemporaryFolder()

    private val validator = OutputValidator(mock(PdfPageReader::class.java))

    private fun zip(name: String, vararg entries: String): File = folder.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { out.putNextEntry(ZipEntry(it)); out.write("<x/>".toByteArray()); out.closeEntry() }
        }
    }

    private fun check(file: File, mime: String) = runBlocking {
        validator.problem(ToolResult(listOf(ToolOutput(file, file.name, mime, file.length())), 1), checkBlank = false)
    }

    @Test fun `a complete docx passes`() {
        assertNull(check(zip("ok.docx", "[Content_Types].xml", "word/document.xml"), MIME_DOCX))
    }

    @Test fun `a pptx without a slide is broken`() {
        assertNotNull(check(zip("empty.pptx", "[Content_Types].xml", "ppt/presentation.xml"), MIME_PPTX))
        assertNull(check(zip("ok.pptx", "[Content_Types].xml", "ppt/presentation.xml", "ppt/slides/slide1.xml"), MIME_PPTX))
    }

    @Test fun `an xlsx without its workbook is broken`() {
        assertEquals("no xl/workbook.xml",
            check(zip("bad.xlsx", "[Content_Types].xml", "xl/worksheets/sheet1.xml"), MIME_XLSX))
    }

    @Test fun `a file that is not a zip, or is empty, is broken`() {
        val text = folder.newFile("notzip.docx").apply { writeText("hello") }
        assertNotNull(check(text, MIME_DOCX))
        assertEquals("empty file", check(folder.newFile("empty.docx"), MIME_DOCX))
    }

    @Test fun `a result with no outputs is broken`() {
        assertEquals("no outputs", runBlocking { validator.problem(ToolResult(emptyList(), 0), checkBlank = false) })
    }
}
