package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.pdf2office.BlockKind
import com.skeler.scanely.core.pdftools.pdf2office.LayoutAnalyzer
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
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
    fun `a single-cell section row inside a table stays in the table`() {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Greens", "", ""),
            listOf("Kale", "5", "2.00"),
            listOf("Leeks", "9", "1.75")
        )

        val table = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 200f, 350f))).blocks.single() as TableBlock

        assertEquals(grid, table.rows)
    }

    @Test
    fun `a numeric total under the last row stays in the table`() {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Pears", "7", "4.25"),
            listOf("", "", "7.75")
        )

        val table = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 200f, 350f))).blocks.single() as TableBlock

        assertEquals(grid, table.rows)
    }

    @Test
    fun `a short paragraph directly below a table is not swallowed`() {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Pears", "7", "4.25")
        )
        val chars = gridChars(grid, listOf(50f, 200f, 350f)) +
            text("Notes", 50f, 148f) +
            text("Prices may change without notice.", 50f, 164f)

        val blocks = LayoutAnalyzer.analyze(chars).blocks

        assertEquals(grid, (blocks.first() as TableBlock).rows)
        val after = blocks.drop(1).filterIsInstance<ParagraphBlock>().joinToString(" ") { it.text }
        assertEquals("Notes Prices may change without notice.", after)
    }

    @Test
    fun `a four-column table with one empty cell per row is still a table`() {
        val grid = (0 until 5).map { row -> (0 until 4).map { column -> if (column == row % 4) "" else "v$row$column" } }

        val table = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 150f, 250f, 350f))).blocks.single() as TableBlock

        assertEquals(grid, table.rows)
        assertEquals(4, table.columnWidthsPt.size)
    }

    @Test
    fun `a sparse three-column run with too few values is not a table`() {
        // A full row, then lone values in the last column alternating with two-value rows:
        // 15 of 27 cells filled, under the small-table fill gate of 100 / (27 + 148).
        val grid = listOf(listOf("ab", "cd", "ef")) + (1 until 9).map { row ->
            if (row % 2 == 1) listOf("", "", "g$row") else listOf("h$row", "i$row", "")
        }

        val layout = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 200f, 350f)))

        assertTrue(layout.tables.isEmpty())
    }

    @Test
    fun `a bulleted link list set in columns is not a table`() {
        // irs_p17 cover: "• IRS.gov (English)  • IRS.gov/Chinese  • IRS.gov/Russian", two rows of three.
        val grid = listOf(
            listOf("• IRS.gov (English)", "• IRS.gov/Chinese", "• IRS.gov/Russian"),
            listOf("• IRS.gov/Spanish", "• IRS.gov/Korean", "• IRS.gov/Vietnamese")
        )

        val layout = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 220f, 390f)))

        assertTrue(layout.tables.isEmpty())
    }

    @Test
    fun `index entries in columns are not a table`() {
        // irs_p17 index: terms ending in page numbers, three columns.
        val grid = listOf(
            listOf("Seminars 25", "Short tax year 102", "Taxability of 62, 63"),
            listOf("Address 16", "Stock options 51", "Volunteer work 52"),
            listOf("Grants 73", "Supplemental 39", "Due dates 11")
        )

        val layout = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 220f, 390f)))

        assertTrue(layout.tables.isEmpty())
    }

    @Test
    fun `column ends that share a baseline wait for the layout model and stay text without a table region`() {
        // 1000_0.pdf: the last lines of three newspaper columns happen to share baselines.
        val grid = listOf(
            listOf("nearly vanished.", "ema, a goal since childhood.", "Libyan films crossed borders."),
            listOf("in Europe.", "of Gaza in October 2023.", "rearming.")
        )
        val chars = gridChars(grid, listOf(50f, 220f, 390f))

        val first = LayoutAnalyzer.analyze(chars)
        val withModel = LayoutAnalyzer.analyze(chars, modelTables = listOf(LayoutBox(400f, 0f, 560f, 60f)))

        assertTrue(first.tables.isEmpty())
        assertTrue(first.unconfirmedTables)
        assertTrue(withModel.tables.isEmpty())
        assertFalse(withModel.unconfirmedTables)
    }

    @Test
    fun `a table of sentences the layout model marks as a table stays a table`() {
        // irs_p17 p83: "$246,000 or more | no deduction." inside a ruled table.
        val grid = listOf(listOf("Less than $226,000", "full deduction."), listOf("$246,000 or more", "no deduction."))
        val chars = gridChars(grid, listOf(50f, 250f))

        val layout = LayoutAnalyzer.analyze(chars, modelTables = listOf(LayoutBox(40f, 80f, 400f, 130f)))

        assertEquals(grid, layout.tables.single().rows)
    }

    @Test
    fun `wrapped headlines side by side are text set in columns`() {
        // 1000_0.pdf p1 teaser strip: each column one wrapped headline.
        val grid = listOf(
            listOf("Damascus-", "Brotherhood’s", "Gaza’s second"),
            listOf("SDF", "terrorist", "phase"),
            listOf("showdown", "designation", "")
        )

        val layout = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 220f, 390f)))

        assertTrue(layout.tables.isEmpty())
    }

    @Test
    fun `column widths cover the table from its left edge to its right edge`() {
        val grid = listOf(listOf("Apples", "12"), listOf("Pears", "7"))

        val table = LayoutAnalyzer.analyze(gridChars(grid, listOf(50f, 200f))).blocks.single() as TableBlock

        assertEquals(listOf(150f, 12f), table.columnWidthsPt)
        assertEquals(table.box.width, table.columnWidthsPt.sum(), 0.01f)
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
    fun `two columns of prose read down the left column before the right`() {
        val left = listOf("the left column opens the story", "and carries it down the page", "until the left column ends")
        val right = listOf("the right column picks it up", "and finishes the story below", "with the very last sentence")
        val chars = left.indices.flatMap { row ->
            text(left[row], 40f, 100f + row * 14f) + text(right[row], 320f, 100f + row * 14f)
        }

        val read = LayoutAnalyzer.analyze(chars).blocks.filterIsInstance<ParagraphBlock>().joinToString(" ") { it.text }

        assertEquals((left + right).joinToString(" "), read)
    }

    /** pdfium reports Arabic in reading order while each letter sits left of the one before. */
    @Test
    fun `arabic set right to left keeps its words and their order`() {
        val line = "النص العربي يقرأ من اليمين إلى اليسار"

        val paragraph = LayoutAnalyzer.analyze(rtlText(line, 400f, 100f)).blocks.single() as ParagraphBlock

        assertEquals(line, paragraph.text)
    }

    /** A tanween or shadda sits above its letter, so its box doesn't overlap the letter's. */
    @Test
    fun `arabic diacritics stay on their word`() {
        val word = rtlText("سياسي", 400f, 100f)
        val mark = LayoutChar('\u064B', word.last().left, 84f, word.last().left + 2f, 86f, BODY_PT)

        val paragraph = LayoutAnalyzer.analyze(word + mark + rtlText("جديد", 360f, 100f)).blocks.single()

        assertEquals("سياسي\u064B جديد", (paragraph as ParagraphBlock).text)
    }

    /** Justified Arabic stretches a word with tatweel; the stretch is part of the word. */
    @Test
    fun `a tatweel stretch keeps the word whole`() {
        val start = rtlText("سياس", 400f, 100f)
        val tatweel = LayoutChar('\u0640', start.last().left - 14f, 94f, start.last().left, 100f, BODY_PT)
        val end = rtlText("يا", tatweel.left, 100f)

        val paragraph = LayoutAnalyzer.analyze(start + tatweel + end).blocks.single()

        assertEquals("سياس\u0640يا", (paragraph as ParagraphBlock).text)
    }

    @Test
    fun `arabic columns read from the right column first`() {
        val right = listOf("هذا هو العمود الأيمن من المقال", "ويستمر النص في السطر التالي هنا")
        val left = listOf("وهذا هو العمود الأيسر بعد ذلك", "وينتهي المقال في هذا السطر")
        val chars = right.indices.flatMap { row ->
            rtlText(left[row], 230f, 100f + row * 14f) + rtlText(right[row], 520f, 100f + row * 14f)
        }

        val read = LayoutAnalyzer.analyze(chars).blocks.filterIsInstance<ParagraphBlock>().joinToString(" ") { it.text }

        assertEquals((right + left).joinToString(" "), read)
    }

    @Test
    fun `a headline across both columns is read before them`() {
        val headline = text("Headline spanning both of the columns", 40f, 70f, size = 20f)
        val left = listOf("the left column opens the story", "and carries it down the page")
        val right = listOf("the right column picks it up", "and finishes the story below")
        val columns = left.indices.flatMap { row ->
            text(left[row], 40f, 100f + row * 14f) + text(right[row], 320f, 100f + row * 14f)
        }

        val blocks = LayoutAnalyzer.analyze(headline + columns).blocks.filterIsInstance<ParagraphBlock>()

        assertEquals("Headline spanning both of the columns", blocks.first().text)
        assertEquals(
            (left + right).joinToString(" "),
            blocks.drop(1).joinToString(" ") { it.text }
        )
    }

    /** pdfium reports a hyphen that ends a line as U+FFFE, with a box, as in a typeset magazine. */
    @Test
    fun `a line-end hyphen pdfium reports as U+FFFE joins the word`() {
        val first = text("strong misgivings and af", 50f, 100f)
        val hyphen = LayoutChar('\uFFFE', 50f + 24 * 6f, 92f, 50f + 24 * 6f + 3f, 100f, BODY_PT)
        val second = text("ter that the talks went on", 50f, 114f)

        val paragraph = LayoutAnalyzer.analyze(first + hyphen + second).blocks.single() as ParagraphBlock

        assertEquals("strong misgivings and after that the talks went on", paragraph.text)
    }

    @Test
    fun `an unmapped glyph inside a line is not taken for a hyphen`() {
        val chars = text("A", 50f, 100f) + LayoutChar('\uFFFE', 56f, 92f, 62f, 100f, BODY_PT) + text("er", 62f, 100f)

        val paragraph = LayoutAnalyzer.analyze(chars).blocks.single() as ParagraphBlock

        assertFalse(paragraph.text, paragraph.text.contains('-'))
    }

    /** pdfium reports every letter of a ligature ("ffi") with the ligature glyph's own box. */
    @Test
    fun `the letters of a ligature stay in one word`() {
        val o = LayoutChar('o', 50f, 91.6f, 56f, 100f, BODY_PT)
        val ligature = "ffi".map { LayoutChar(it, 56f, 91.6f, 66f, 100f, BODY_PT) }

        val paragraph = LayoutAnalyzer.analyze(listOf(o) + ligature + text("cial", 66f, 100f)).blocks.single()

        assertEquals("official", (paragraph as ParagraphBlock).text)
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

    /** [grid] laid out one row every 16 pt from y = 100, each column starting at its x; "" is no text. */
    private fun gridChars(grid: List<List<String>>, columnsX: List<Float>): List<LayoutChar> =
        grid.flatMapIndexed { row, cells ->
            cells.flatMapIndexed { column, cell -> text(cell, columnsX[column], 100f + row * 16f) }
        }

    /** [text] in reading order, each character placed left of the previous one, ending at [right]. */
    private fun rtlText(text: String, right: Float, baseline: Float, size: Float = BODY_PT): List<LayoutChar> =
        text.mapIndexed { index, char ->
            val charRight = right - index * size * ADVANCE_EM
            LayoutChar(char, charRight - size * ADVANCE_EM, baseline - size * HEIGHT_EM, charRight, baseline, size)
        }

    private companion object {
        const val BODY_PT = 12f
        const val ADVANCE_EM = 0.5f
        const val HEIGHT_EM = 0.7f
        const val MAX_ANALYSIS_MS = 3_000L
        const val LINE_CHARS = 80
    }
}
