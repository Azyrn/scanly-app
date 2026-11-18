package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.docx.FontBox
import com.skeler.scanely.core.pdftools.office2pdf.docx.LineBoxes
import com.skeler.scanely.core.pdftools.office2pdf.docx.LineRule
import com.skeler.scanely.core.pdftools.office2pdf.docx.PageRules
import com.skeler.scanely.core.pdftools.office2pdf.docx.ParagraphStyle
import com.skeler.scanely.core.pdftools.office2pdf.docx.TabKind
import com.skeler.scanely.core.pdftools.office2pdf.docx.TabPlan
import com.skeler.scanely.core.pdftools.office2pdf.docx.TabStop
import com.skeler.scanely.core.pdftools.office2pdf.docx.TabStops
import com.skeler.scanely.core.pdftools.office2pdf.docx.WordGrid
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of Word→PDF layout: Word's grid, line boxes, tab stops, page breaks and paragraph spacing. */
class DocxLineRulesTest {

    @Test
    fun `sizes snap to Word's 600 dpi grid`() {
        assertEquals(9.96f, WordGrid.fontSize(10f), 0.001f)
        assertEquals(12f, WordGrid.fontSize(12f), 0.001f)
        // 10 pt Times New Roman: ascent 74, descent 18, leading 4 dots = 11.52 pt single-spaced.
        assertEquals(74 * 0.12f, WordGrid.snap(8.91f), 0.001f)
        assertEquals("never zero", 0.12f, WordGrid.fontSize(0f), 0.001f)
    }

    @Test
    fun `line heights follow the line rule`() {
        val natural = 11.52f
        assertEquals(12f, LineBoxes.height(LineRule.EXACT, 240, natural), 0.001f)
        assertEquals(6f, LineBoxes.height(LineRule.EXACT, 120, natural), 0.001f)
        assertEquals(12f, LineBoxes.height(LineRule.AT_LEAST, 240, natural), 0.001f)
        assertEquals("the font wins when taller", natural, LineBoxes.height(LineRule.AT_LEAST, 200, natural), 0.001f)
        assertEquals(natural, LineBoxes.height(LineRule.AUTO, 240, natural), 0.001f)
        assertEquals(natural * 1.5f, LineBoxes.height(LineRule.AUTO, 360, natural), 0.001f)
    }

    @Test
    fun `auto lines hang the text from the top, exact lines centre it`() {
        val font = FontBox(ascent = 8.88f, descent = 2.16f, leading = 0.48f)
        assertEquals(9.36f, LineBoxes.baseline(LineRule.AUTO, 17.28f, font), 0.001f)
        // (12 - 8.88 - 2.16) / 2 + 8.88
        assertEquals(9.36f, LineBoxes.baseline(LineRule.EXACT, 12f, font), 0.001f)
        assertEquals(10.86f, LineBoxes.baseline(LineRule.AT_LEAST, 15f, font), 0.001f)
    }

    @Test
    fun `tab stops add up through the style chain`() {
        val base = TabStops(listOf(TabStop(720, TabKind.START), TabStop(1440, TabKind.START)))
        val own = TabStops(listOf(TabStop(720, TabKind.CLEAR), TabStop(1440, TabKind.END), TabStop(2000, TabKind.CENTER)))

        val merged = own.over(base).stops

        assertEquals(listOf(TabStop(1440, TabKind.END), TabStop(2000, TabKind.CENTER)), merged)
        assertEquals("nothing set keeps the base", base.stops, TabStops(emptyList()).over(base).stops)
    }

    @Test
    fun `a paragraph's stops are its own, the hanging indent, then every half inch`() {
        val stops = TabPlan.stops(listOf(TabStop(1440, TabKind.END)), hanging = 18f, width = 200f)

        assertEquals(listOf(18f, 72f, 108f, 144f, 180f, 216f), stops.map { it.pos })
        assertEquals(TabKind.END, stops[1].kind)
        assertEquals(TabKind.START, stops[0].kind)
        assertEquals(listOf(36f, 72f), TabPlan.stops(emptyList(), null, 50f).map { it.pos })
    }

    @Test
    fun `widow and orphan control never leaves a line alone`() {
        val widows = ParagraphStyle()
        assertEquals("all fit", 10, PageRules.breakAt(10, widows, 0, 10))
        assertEquals(6, PageRules.breakAt(10, widows, 0, 6))
        assertEquals("one line would go over alone", 8, PageRules.breakAt(10, widows, 0, 9))
        assertEquals("one line would stay alone", 0, PageRules.breakAt(10, widows, 0, 1))
        assertEquals("three lines move whole", 0, PageRules.breakAt(3, widows, 0, 2))
        assertEquals(9, PageRules.breakAt(10, widows.copy(widowControl = false), 0, 9))
        assertEquals(1, PageRules.breakAt(10, widows.copy(widowControl = false), 0, 1))
        assertEquals("kept lines move whole", 0, PageRules.breakAt(10, widows.copy(keepLines = true), 0, 9))
    }

    @Test
    fun `the least of a paragraph that may stand at a page foot`() {
        val widows = ParagraphStyle()
        assertEquals(2, PageRules.leastAtFoot(10, widows))
        assertEquals(3, PageRules.leastAtFoot(3, widows))
        assertEquals(1, PageRules.leastAtFoot(1, widows))
        assertEquals(1, PageRules.leastAtFoot(10, widows.copy(widowControl = false)))
        assertEquals(10, PageRules.leastAtFoot(10, widows.copy(keepLines = true)))
    }

    @Test
    fun `space between paragraphs adds up, contextual spacing drops it, automatic spacing collapses`() {
        val body = ParagraphStyle(styleId = "Body")
        val list = ParagraphStyle(styleId = "List", contextualSpacing = true)
        assertEquals(18f, PageRules.spaceBetween(body, body, 6f, 12f), 0.001f)
        assertEquals("different styles keep both", 18f, PageRules.spaceBetween(body, list, 6f, 12f), 0.001f)
        assertEquals("same style drops the contextual side", 0f, PageRules.spaceBetween(list, list, 6f, 12f), 0.001f)
        val html = ParagraphStyle(styleId = "Html", autoBefore = true, autoAfter = true)
        assertEquals(14f, PageRules.spaceBetween(html, html, 14f, 14f), 0.001f)
        // Measured on ca_dfo_wave_fetch_a: an automatic side collapses with a plain one too (14 apart, not 22).
        assertEquals("one automatic side collapses", 14f, PageRules.spaceBetween(body, html, 8f, 14f), 0.001f)
        assertEquals("one automatic side collapses", 14f, PageRules.spaceBetween(html, body, 14f, 12f), 0.001f)
    }
}
