package com.skeler.scanely.core.pdftools.pdf2office.exact

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExactDocxSchemaTest {
    @Test
    fun bidiPrecedesSpacingAndIndentInParagraphProperties() {
        val style = TextStyle(OfficeFamily.ARIAL, true, false, false, 12f, 0xFF000000.toInt())
        val segment = Segment(listOf(Token("سلام", style, 72f, 100f, false, 0f)), 700f, 12f, true, 0)
        val measurer = TextMeasurer { text, _, _, _, _, _ -> text.length * 6f }
        val writer = ExactDocxWriter(ByteArrayOutputStream(), measurer)
        val xml = writer.textBox(Block(listOf(segment), 20f, 0f), 842f)
        writer.finish()
        val paragraph = xml.substringAfter("<w:pPr>").substringBefore("</w:pPr>")
        assertTrue(paragraph.indexOf("<w:bidi") >= 0)
        assertTrue(paragraph.indexOf("<w:bidi") < paragraph.indexOf("<w:spacing"))
    }
}
