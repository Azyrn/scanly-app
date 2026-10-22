package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.pdf2office.BlockKind
import com.skeler.scanely.core.pdftools.pdf2office.LayoutAnalyzer
import com.skeler.scanely.core.pdftools.pdf2office.LayoutChar
import com.skeler.scanely.core.pdftools.pdf2office.ParagraphBlock
import com.skeler.scanely.core.pdftools.pdf2office.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout heuristics on synthetic pages whose geometry is known exactly: characters laid out
 * on a fixed advance, as a simple PDF would place them.
 */
class LayoutAnalyzerTest {

    @Test
    fun `lines with even spacing form paragraphs and a wider gap splits them`() {
        val chars = text("The first paragraph starts here and", 50f, 100f) +
            text("carries on over a second line and", 50f, 114f) +
            text("ends on the third.", 50f, 128f) +
            text("A second paragraph follows after", 50f, 156f) +
            text("a blank line between them.", 50f, 170f)

        val blocks = LayoutAnalyzer.analyze(chars).blocks

        assertEquals(2, blocks.size)
        val first = blocks[0] as ParagraphBlock
        assertEquals(BlockKind.BODY, first.kind)
        assertEquals(
            "The first paragraph starts here and carries on over a second line and ends on the third.",
            first.text
        )
        assertEquals(3, first.lines.size)
        assertEquals(
            "A second paragraph follows after a blank line between them.",
            (blocks[1] as ParagraphBlock).text
        )
    }

    @Test
    fun `larger text over the body is a heading`() {
        val chars = text("Annual report", 50f, 60f, size = 24f) +
            text("Section summary", 50f, 90f, size = 15f) +
            text("Body text that is long enough to be the body size", 50f, 120f) +
            text("of this page, set in twelve point type throughout.", 50f, 134f)

        val blocks = LayoutAnalyzer.analyze(chars).blocks.map { it as ParagraphBlock }

        assertEquals(listOf(BlockKind.HEADING_1, BlockKind.HEADING_2, BlockKind.BODY), blocks.map { it.kind })
        assertEquals("Annual report", blocks[0].text)
    }

    @Test
    fun `bullet markers become list items without the glyph`() {
        val chars = text("• first item", 50f, 100f) + text("• second item", 50f, 114f)

        val blocks = LayoutAnalyzer.analyze(chars).blocks.map { it as ParagraphBlock }

        assertEquals(listOf(BlockKind.BULLET, BlockKind.BULLET), blocks.map { it.kind })
        assertEquals(listOf("first item", "second item"), blocks.map { it.text })
    }

    @Test
    fun `a hyphen at a line end joins the word back together`() {
        val chars = text("This is an exam-", 50f, 100f) + text("ple of a hyphen.", 50f, 114f)

        val paragraph = LayoutAnalyzer.analyze(chars).blocks.single() as ParagraphBlock

        assertEquals("This is an example of a hyphen.", paragraph.text)
    }

    @Test
    fun `aligned columns of short values are a table`() {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Pears", "7", "4.25"),
            listOf("Plums", "30", "1.10")
        )
        val chars = grid.flatMapIndexed { row, cells ->
            cells.flatMapIndexed { column, cell -> text(cell, 50f + column * 150f, 100f + row * 16f) }
        }

        val layout = LayoutAnalyzer.analyze(chars)

        val table = layout.blocks.single() as TableBlock
        assertEquals(grid, table.rows)
    }

    @Test
    fun `a table between paragraphs keeps its place in reading order`() {
        val chars = text("Prices this week are listed below.", 50f, 60f) +
            text("Apples", 50f, 100f) + text("12", 200f, 100f) +
            text("Pears", 50f, 116f) + text("7", 200f, 116f) +
            text("Prices may change without notice.", 50f, 160f)

        val blocks = LayoutAnalyzer.analyze(chars).blocks

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ParagraphBlock)
        assertEquals(listOf(listOf("Apples", "12"), listOf("Pears", "7")), (blocks[1] as TableBlock).rows)
        assertTrue(blocks[2] is ParagraphBlock)
    }

    @Test
    fun `two columns of prose are not a table`() {
        val left = "one two three four five six seven eight"
        val right = "nine ten eleven twelve thirteen fourteen fifteen sixteen"
        val chars = (0 until 4).flatMap { row ->
            text(left, 40f, 100f + row * 14f) + text(right, 320f, 100f + row * 14f)
        }

        val layout = LayoutAnalyzer.analyze(chars)

        assertTrue(layout.tables.isEmpty())
        assertTrue(layout.blocks.isNotEmpty())
    }

    @Test
    fun `characters without a box only separate words`() {
        val chars = text("alpha", 50f, 100f) +
            LayoutChar('\r', 0f, 0f, 0f, 0f, 0f) +
            LayoutChar('\n', 0f, 0f, 0f, 0f, 0f) +
            text("beta", 90f, 100f)

        val paragraph = LayoutAnalyzer.analyze(chars).blocks.single() as ParagraphBlock

        assertEquals("alpha beta", paragraph.text)
    }

    @Test
    fun `a page with no glyphs is empty`() {
        val layout = LayoutAnalyzer.analyze(listOf(LayoutChar(' ', 0f, 0f, 0f, 0f, 0f)))

        assertTrue(layout.isEmpty)
        assertTrue(LayoutAnalyzer.analyze(emptyList()).isEmpty)
    }

    @Test
    fun `a page over the cap falls back to plain lines quickly`() {
        val chars = (0..LayoutAnalyzer.MAX_CHARS_PER_PAGE).map { index ->
            val char = if (index % LINE_CHARS == LINE_CHARS - 1) '\n' else 'x'
            val left = (index % LINE_CHARS) * 6f
            val top = (index / LINE_CHARS) * 2f
            LayoutChar(char, left, top, left + 6f, top + 8f, BODY_PT)
        }

        val started = System.nanoTime()
        val layout = LayoutAnalyzer.analyze(chars)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse(layout.positioned)
        assertTrue(layout.simplified)
        // Every newline ends a line, and the characters after the last one make one more.
        assertEquals(chars.size / LINE_CHARS + 1, layout.blocks.size)
        assertTrue("took $elapsedMs ms", elapsedMs < MAX_ANALYSIS_MS)
    }

    @Test
    fun `a page at the cap is analysed without stalling`() {
        // Near-worst case for the geometric path: every line a candidate table row.
        val chars = ArrayList<LayoutChar>()
        var row = 0
        while (chars.size < LayoutAnalyzer.MAX_CHARS_PER_PAGE - 40) {
            (0 until 4).forEach { column ->
                chars += text("v$row$column", 20f + column * 120f, 20f + row * 10f, size = 8f)
            }
            row++
        }

        val started = System.nanoTime()
        val layout = LayoutAnalyzer.analyze(chars)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(layout.positioned)
        assertTrue("took $elapsedMs ms", elapsedMs < MAX_ANALYSIS_MS)
    }

    @Test
    fun `plain lines drop blanks and control characters`() {
        val layout = LayoutAnalyzer.plainLines("first\u0000 line\r\n\n  \nsecond line")

        assertEquals(listOf("first line", "second line"), layout.textLines)
    }

    /** [text] on baseline [baseline] from [x], each character half an em wide. */
    private fun text(text: String, x: Float, baseline: Float, size: Float = BODY_PT): List<LayoutChar> =
        text.mapIndexed { index, char ->
            val left = x + index * size * ADVANCE_EM
            LayoutChar(char, left, baseline - size * HEIGHT_EM, left + size * ADVANCE_EM, baseline, size)
        }

    private companion object {
        const val BODY_PT = 12f
        const val ADVANCE_EM = 0.5f
        const val HEIGHT_EM = 0.7f
        const val MAX_ANALYSIS_MS = 3_000L
        const val LINE_CHARS = 80
    }
}
