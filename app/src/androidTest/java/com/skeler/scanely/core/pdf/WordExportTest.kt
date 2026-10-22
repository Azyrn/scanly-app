package com.skeler.scanely.core.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes a real .docx through the real export path, so the package a Word reader actually opens is
 * what gets checked — not just the XML string the document builder returns.
 */
@RunWith(AndroidJUnit4::class)
class WordExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val quote = """
        # Kitchen Renovation Quote

        **Client:** [Client name]
        **Date:** [Date]
        **Project:** Full kitchen renovation

        ## Materials

        | Item | Quantity | Unit Price | Total |
        | --- | --- | --- | --- |
        | Custom shaker-style cabinetry (maple) | 20 linear ft | ${'$'}450 | ${'$'}9,000 |
        | Quartz countertops (Cambria Brittanica) | 35 sq ft | ${'$'}75 | ${'$'}2,625 |
    """.trimIndent()

    @Test
    fun theExportedPackageOpensWithItsTablesAndLineBreaks() = runBlocking {
        val uri = ScanExporter.exportTextWord(context, quote, asMarkdown = true, baseName = "wordcheck")
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        // A .docx is a zip; anything else is not openable at all.
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)

        val document = entry(bytes, "word/document.xml")

        // Every table carries the grid the schema requires, or readers may drop it.
        val tables = Regex("<w:tbl>").findAll(document).count()
        assertEquals(tables, Regex("<w:tblGrid>").findAll(document).count())
        assertTrue(tables > 0)

        // Line breaks survive as run-level elements, so the labelled lines stay separate.
        assertTrue(document.contains("<w:r><w:br/></w:r>"))
        assertTrue(!document.contains("</w:r><w:br/>"))
        assertTrue(document.contains(">Quantity</w:t>"))
    }

    private fun entry(zip: ByteArray, name: String): String =
        java.util.zip.ZipInputStream(zip.inputStream()).use { stream ->
            generateSequence { stream.nextEntry }
                .firstOrNull { it.name == name }
                ?.let { stream.readBytes().decodeToString() }
                ?: error("$name missing from the package")
        }
}
