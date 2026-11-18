package com.skeler.scanely.core.pdftools.pdf2office.exact

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactHeadingsTest {

    private fun line(text: String, baseline: Float, size: Float = 10f, bold: Boolean = false): Segment {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = bold, italic = false, sizePt = size, argb = 0)
        val tokens = text.split(' ').mapIndexed { i, word -> Token(word, style, 72f + i * 40f, 72f + i * 40f + 30f, i > 0, 0f) }
        return Segment(tokens, baseline, size, false, 0)
    }

    private val body = (0 until 6).map { line("body text set in the size most letters use on this page", 600f - it * 12f) }

    @Test
    fun largerOrBoldShortBlocksAreHeadingsAndBodyIsNot() {
        val title = ExactLayout.block(listOf(line("Annual report", 760f, size = 18f)))
        val section = ExactLayout.block(listOf(line("Introduction", 700f, bold = true)))
        val sentence = ExactLayout.block(listOf(line("A bold sentence ends with a full stop.", 680f, bold = true)))
        val text = ExactLayout.block(body)
        val levels = ExactHeadings.levels(ExactPageLayout(595f, 842f, listOf(title, section, sentence, text)))
        assertEquals(mapOf(title to 0, section to 1), levels)
    }

    @Test
    fun boldIsNoHeadingWhereTheBodyIsBoldItself() {
        val bodyBold = (0 until 6).map { line("bold body text on a page set all in bold", 600f - it * 12f, bold = true) }
        val short = ExactLayout.block(listOf(line("Introduction", 700f, bold = true)))
        assertEquals(emptyMap<Block, Int>(), ExactHeadings.levels(ExactPageLayout(595f, 842f, listOf(short, ExactLayout.block(bodyBold)))))
    }
}
