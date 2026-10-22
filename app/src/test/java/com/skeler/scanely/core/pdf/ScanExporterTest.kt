package com.skeler.scanely.core.pdf

import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.text.MarkdownParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanExporterTest {

    private val multiPageText =
        "Invoice 2024-01-08\nTotal: 42\n\n--- Page 2 ---\n\n- item one\nThank you"

    @Test
    fun `word keeps the markdown preview's headings, emphasis and table grid`() {
        val document = ScanExporter.wordMarkdownDocument(
            MarkdownParser.parse("# Invoice\n\nDue **now**\n\n| Item | Cost |\n| --- | --- |\n| Pen | 2 |")
        )

        assertTrue(document.contains("<w:sz w:val=\"36\"/>"))
        assertTrue(document.contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">now</w:t>"))
        assertTrue(document.contains("<w:tbl>"))
        assertTrue(document.contains(">Cost</w:t>"))
        assertTrue(document.contains(">Pen</w:t>"))
        // The markers themselves never reach the document.
        assertFalse(document.contains("**"))
        assertFalse(document.contains("| Item"))
    }

    /**
     * `w:tbl` is `(w:tblPr, w:tblGrid, rows…)` in the schema, and a reader that finds no grid may
     * drop the table outright — Android's Word viewer did, leaving the headings around nothing.
     */
    @Test
    fun `word tables carry the grid the schema requires`() {
        val document = ScanExporter.wordMarkdownDocument(
            MarkdownParser.parse("| Item | Cost |\n| --- | --- |\n| Pen | 2 |")
        )

        val grid = document.substringAfter("<w:tblGrid>", "").substringBefore("</w:tblGrid>")
        assertEquals(2, Regex("<w:gridCol ").findAll(grid).count())
        // The grid comes between the properties and the first row, where the schema puts it.
        assertTrue(document.indexOf("<w:tblGrid>") > document.indexOf("</w:tblPr>"))
        assertTrue(document.indexOf("<w:tblGrid>") < document.indexOf("<w:tr>"))
    }

    /**
     * `w:br` is a run element. Emitted straight into the paragraph it is invalid and gets dropped,
     * which ran three labelled lines together into one.
     */
    @Test
    fun `word keeps the line breaks inside a paragraph`() {
        val document = ScanExporter.wordMarkdownDocument(
            MarkdownParser.parse("**Client:** Acme\n**Date:** today\n**Project:** kitchen")
        )

        assertEquals(2, Regex("<w:br/>").findAll(document).count())
        assertFalse("w:br must never be a direct child of w:p", document.contains("</w:r><w:br/>"))
        assertTrue(document.contains("<w:r><w:br/></w:r>"))
    }

    @Test
    fun `a document's headings and tables export without colour`() {
        val markdown = "# Invoice\n\n| Item | Cost |\n| --- | --- |\n| Pen | 2 |"

        val plain = ScanExporter.wordMarkdownDocument(MarkdownParser.parse(markdown))

        assertFalse(plain.contains("<w:color"))
        assertTrue(plain.contains("w:fill=\"EEEEEE\""))
    }

    @Test
    fun `json carries per-line box and confidence grouped by page`() {
        val blocks = listOf(
            TextBlockData("Invoice", 10, 20, 110, 45, confidence = 0.98f, page = 1),
            TextBlockData("Thank you", 12, 22, 130, 48, confidence = 0.91f, page = 2)
        )

        val json = JSONObject(ScanExporter.buildJson(multiPageText, blocks))
        val pages = json.getJSONArray("pages")

        assertEquals(multiPageText, json.getString("text"))
        assertEquals(2, pages.length())

        val firstLine = pages.getJSONObject(0).getJSONArray("lines").getJSONObject(0)
        assertEquals(1, pages.getJSONObject(0).getInt("page"))
        assertEquals("Invoice", firstLine.getString("text"))
        assertEquals(0.98, firstLine.getDouble("confidence"), 0.001)
        assertEquals(
            listOf(10, 20, 110, 45),
            firstLine.getJSONArray("box").let { box ->
                (0 until box.length()).map { box.getInt(it) }
            }
        )
        assertEquals(2, pages.getJSONObject(1).getInt("page"))
    }

    @Test
    fun `json without blocks falls back to text lines`() {
        val json = JSONObject(ScanExporter.buildJson(multiPageText, emptyList()))
        val pages = json.getJSONArray("pages")

        assertEquals(2, pages.length())
        val page2Lines = pages.getJSONObject(1).getJSONArray("lines")
        assertEquals("- item one", page2Lines.getJSONObject(0).getString("text"))
        assertTrue(page2Lines.getJSONObject(0).isNull("confidence"))
    }
}
