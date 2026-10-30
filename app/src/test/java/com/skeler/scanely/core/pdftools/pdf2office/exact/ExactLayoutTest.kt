package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumChar
import com.skeler.scanely.core.pdfeditor.engine.PdfiumPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind exact-layout PDF → Word, on character fixtures shaped like what pdfium
 * reports (PDF user space, y up, an A4 page).
 */
class ExactLayoutTest {

    @Test
    fun wordsOnABaselineBecomeOneLineWithASpaceBetweenThem() {
        val chars = word("Hello", 72f, 700f) + space(102f, 700f) + word("world", 105f, 700f)

        val layout = ExactLayout.analyze(page(chars))

        val line = layout.blocks.single().lines.single()
        assertEquals(listOf("Hello", "world"), line.tokens.map { it.text })
        assertTrue(line.tokens[1].spaceBefore)
        assertEquals(700f, line.baselineV, 0.01f)
    }

    @Test
    fun linesSharingAnEdgeAndAPitchBecomeOneParagraphBlock() {
        val chars = (0 until 3).flatMap { i -> word("line$i", 72f, 700f - i * 12f) + lineBreak() }

        val block = ExactLayout.analyze(page(chars)).blocks.single()

        assertEquals(3, block.lines.size)
        assertEquals(12f, block.pitchPt, 0.01f)
        assertEquals(0f, block.indentPt, 0.01f)
    }

    @Test
    fun anIndentedFirstLineStaysInTheParagraphAsItsIndent() {
        val chars = word("first", 90f, 700f) + lineBreak() + word("second", 72f, 688f) + lineBreak() +
            word("third", 72f, 676f)

        val block = ExactLayout.analyze(page(chars)).blocks.single()

        assertEquals(3, block.lines.size)
        assertEquals(18f, block.indentPt, 0.01f)
    }

    @Test
    fun anIrregularPitchStartsANewBlock() {
        val chars = word("a", 72f, 700f) + lineBreak() + word("b", 72f, 688f) + lineBreak() + word("c", 72f, 650f)

        val blocks = ExactLayout.analyze(page(chars)).blocks

        assertEquals(listOf(2, 1), blocks.map { it.lines.size })
    }

    @Test
    fun aColumnGapSplitsTheBaselineIntoTwoSegments() {
        val chars = word("left", 72f, 700f) + word("right", 320f, 700f)

        val blocks = ExactLayout.analyze(page(chars)).blocks

        assertEquals(listOf("left", "right"), blocks.map { it.lines.single().tokens.single().text })
    }

    @Test
    fun aCaptionBesideALargeHeadingKeepsItsOwnSizeAndBaseline() {
        // The W-9 masthead: 7 pt "Internal Revenue Service" at 710.97, a 14 pt heading at 714 across
        // a column gap. pdfium puts both on one line; each must keep its own size and baseline.
        val chars = word("Internal", 36f, 710.97f, size = 7f, advance = 3.5f) +
            word("Request", 300f, 714f, size = 14f, advance = 7f)

        val segments = ExactLayout.analyze(page(chars)).blocks.map { it.lines.single() }

        assertEquals(7f, segments[0].sizePt, 0.01f)
        assertEquals(710.97f, segments[0].baselineV, 0.01f)
        assertEquals(14f, segments[1].sizePt, 0.01f)
        assertEquals(714f, segments[1].baselineV, 0.01f)
    }

    @Test
    fun aGlyphPaintedTwiceOnTheSameSpotIsOneLetter() {
        // Fake bold: every glyph drawn a second time 0.3 pt to the right.
        val chars = word("Bold", 72f, 700f).flatMap { listOf(it, it.shifted(0.3f)) }

        val token = ExactLayout.analyze(page(chars)).blocks.single().lines.single().tokens.single()

        assertEquals("Bold", token.text)
    }

    @Test
    fun theSizeComesFromTheTextMatrixNotTheNominalFontSize() {
        // Tf 1 scaled by a 10x text matrix, as the Federal Reserve tables do.
        val chars = word("tiny", 72f, 700f, size = 1f, scale = 10f)

        val token = ExactLayout.analyze(page(chars)).blocks.single().lines.single().tokens.single()

        assertEquals(10f, token.style.sizePt, 0.01f)
    }

    @Test
    fun rightToLeftGlyphsComeOutInReadingOrder() {
        // "سلام" drawn right to left: the first letter in reading order is rightmost on the page.
        val letters = "سلام"
        val chars = letters.mapIndexed { i, c -> char(c, 200f - (i + 1) * 6f, 700f) }

        val line = ExactLayout.analyze(page(chars)).blocks.single().lines.single()

        assertTrue(line.rtl)
        assertEquals(letters, line.tokens.single().text)
        assertTrue(line.tokens.single().style.arabic)
    }

    @Test
    fun arabicWordsAreSeparatedWhereThePageHasAGapEvenWithoutASpaceCharacter() {
        // Measured from a Chrome-made PDF: "الواقع في" with a 6 pt gap and a zero-width space glyph.
        fun a(c: Char, left: Float, right: Float) = char(c, left, 700f, size = 12f, advance = right - left)
        val chars = listOf(
            a('ا', 146.4f, 147.7f), a('ل', 142.9f, 144.9f), a('و', 137.8f, 143.2f), a('ا', 135.4f, 136.7f),
            a('ق', 129.4f, 133.9f), a('ع', 124.6f, 130.3f),
            char(' ', 114.1f, 700f, size = 12f, advance = 0f),
            a('ف', 114.1f, 118.6f), a('ي', 106.7f, 114.4f)
        )

        val line = ExactLayout.analyze(page(chars)).blocks.single().lines.single()

        assertEquals(listOf("الواقع", "في"), line.tokens.map { it.text })
        assertTrue(line.tokens[1].spaceBefore)
        assertEquals(6.0f, line.tokens[1].gapBeforePt, 0.1f)
    }

    @Test
    fun textInsideAFormXObjectStaysOutOfTheEditableLayer() {
        val chars = word("figure", 72f, 700f, flags = 2) + word("body", 72f, 600f)

        val tokens = ExactLayout.analyze(page(chars)).blocks.flatMap { b -> b.lines.flatMap { it.tokens } }

        assertEquals(listOf("body"), tokens.map { it.text })
    }

    @Test
    fun aBoldItalicSerifFontMapsToTimesNewRomanBoldItalic() {
        val chars = word("x", 72f, 700f, font = "ABCDEF+TimesNewRomanPS-BoldItalicMT")

        val style = ExactLayout.analyze(page(chars)).blocks.single().lines.single().tokens.single().style

        assertEquals(OfficeFamily.TIMES, style.family)
        assertTrue(style.bold)
        assertTrue(style.italic)
        assertFalse(style.arabic)
    }

    // --- width correction ---

    @Test
    fun eachWordLandsAtItsPdfPositionWhateverTheMeasuredWidth() {
        // Every letter measures 5 pt, but the PDF set "Hello" 30 pt wide and put "world" at 110.
        val chars = word("Hello", 72f, 700f, advance = 6f) + space(102f, 700f) + word("world", 110f, 700f, advance = 6f)
        val line = ExactLayout.analyze(page(chars)).blocks.single().lines.single()
        val measurer = TextMeasurer { text, _, _, _, _, _ -> text.length * 5f }

        val runs = ExactRuns.runs(line, 72f, measurer)

        // "Hello": 25 pt measured, 30 pt wanted → +1 pt (20 twips) after each letter.
        assertEquals("Hello", runs[0].text)
        assertEquals(20, runs[0].spacingTwips)
        // The space: "Hello" ends at 102, "world" starts at 110, the space measures 5 → +3 pt.
        assertEquals(" ", runs[1].text)
        assertEquals(60, runs[1].spacingTwips)
        assertEquals("world", runs[2].text)
    }

    @Test
    fun aWordOrSpaceThatMustShrinkIsScaledNeverGivenNegativeSpacing() {
        // Letters measure 5 pt but the PDF set them 4 pt apart, with a 2 pt gap between the words.
        val chars = word("Hello", 72f, 700f, advance = 4f) + space(92f, 700f) + word("world", 94f, 700f, advance = 4f)
        val line = ExactLayout.analyze(page(chars)).blocks.single().lines.single()
        val measurer = TextMeasurer { text, _, _, _, _, _ -> text.length * 5f }

        val runs = ExactRuns.runs(line, 72f, measurer)

        assertTrue(runs.all { it.spacingTwips >= 0 })
        // 20 pt wanted of 25 measured → 80%; the space 2 of 5 → 40%.
        assertEquals(80, runs.first { it.text.startsWith("Hell") }.scalePercent)
        assertEquals(40, runs.first { it.text == " " }.scalePercent)
    }

    @Test
    fun aTableBetweenParagraphsBecomesAnExactTableAndLeavesTheBlocks() {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Pears", "7", "4.25"),
            listOf("Plums", "30", "1.10")
        )
        val columnsX = listOf(72f, 200f, 330f)
        val tableChars = grid.flatMapIndexed { row, cells ->
            cells.flatMapIndexed { column, cell -> word(cell, columnsX[column], 720f - row * 14f) } + lineBreak()
        }
        val chars = word("Above", 72f, 760f) + lineBreak() + tableChars + word("Below", 72f, 640f)

        val layout = ExactTables.detect(ExactLayout.analyze(page(chars)))

        val table = layout.tables.single()
        assertEquals(grid, table.rows.map { row -> row.cells.map { it.text } })
        assertEquals(3, table.columnWidthsPt.size)
        assertEquals(72f, table.box.left, 0.01f)
        assertEquals(listOf(128f, 130f), table.columnWidthsPt.take(2))
        assertEquals(listOf("Above", "Below"), layout.blocks.flatMap { it.lines }.map { line -> line.tokens.joinToString("") { it.text } })
    }

    @Test
    fun aBlockCutByATableKeepsItsOtherLinesAsABlockOfTheirOwn() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 10f, argb = 0xFF000000.toInt())
        fun segment(text: String, x: Float, baseline: Float) =
            Segment(listOf(Token(text, style, x, x + text.length * 5f, false, 0f)), baseline, 10f, false, 0)
        // The first column and the note under it share an edge and a pitch: one block.
        val firstColumn = listOf("Item", "Apples", "Pears", "Plums", "Note").mapIndexed { row, text -> segment(text, 72f, 720f - row * 14f) }
        val others = listOf(listOf("Qty", "Price"), listOf("12", "3.50"), listOf("7", "4.25"), listOf("30", "1.10"))
            .flatMapIndexed { row, cells -> cells.mapIndexed { column, text -> segment(text, 200f + column * 130f, 720f - row * 14f) } }
        val blocks = listOf(ExactLayout.block(firstColumn)) + others.map { ExactLayout.block(listOf(it)) }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, blocks))

        assertEquals(4, layout.tables.single().rows.size)
        val note = layout.blocks.single()
        assertEquals(listOf("Note"), note.lines.map { line -> line.tokens.joinToString("") { it.text } })
        assertEquals(10f * ExactLayout.SINGLE_LINE_FACTOR, note.pitchPt, 0.01f)
    }

    /** 1000_0.pdf (a newspaper): side-by-side justified columns line up like a grid but are text. */
    @Test
    fun justifiedNewspaperColumnsAreNotATable() {
        val columns = listOf(
            listOf("The US administration seems", "to have chosen the path of", "de-escalation putting on hold", "at least for now its previous", "plans for military action", "against Iran after allies"),
            listOf("Up until Wednesday, the", "United States was threaten-", "ing military action against", "Iran should it carry out the", "death penalty against people", "arrested over the protests."),
            listOf("Diplomatic sources also said", "on Thursday that the secur-", "ity posture at the airbase in", "Qatar had been lowered and", "planes have started moving", "back to their old positions.")
        )
        val segments = columns.flatMapIndexed { column, lines ->
            lines.mapIndexed { row, text -> justified(text, 72f + column * 160f, 150f, 720f - row * 12f) }
        }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))

        assertTrue(layout.tables.isEmpty())
        assertEquals(segments.size, layout.blocks.size)
    }

    /** fed_h8_tables.pdf: a row of figures reads as one wide, wordy cell; it is still a table. */
    @Test
    fun aRowOfFiguresBesideItsLabelIsStillATable() {
        val rows = listOf(
            "Deposits" to "18,411.6 18,952.9 19,084.0 19,273.4 19,361.1",
            "Loans" to "12,048.6 12,158.1 12,204.2 12,191.0 12,287.8",
            "Securities" to "5,107.3 5,123.9 5,140.2 5,152.6 5,170.1",
            "Cash" to "3,311.0 3,290.4 3,301.7 3,285.2 3,277.9"
        )
        val segments = rows.flatMapIndexed { row, (label, figures) ->
            listOf(justified(label, 72f, label.length * 5f, 720f - row * 12f), justified(figures, 200f, 300f, 720f - row * 12f))
        }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))

        assertEquals(4, layout.tables.single().rows.size)
    }

    /** irs_p17's index: columns of short entries, each column already one paragraph block, are text. */
    @Test
    fun columnsOfIndexEntriesAreNotATable() {
        val entries = listOf(
            listOf("Employment (See Foreign", "Extension of time to file 11", "Filing requirements 7", "Withholding from IRA"),
            listOf("Corrections (See Errors)", "Cost basis:", "IRAs for nondeductible", "Cost-of-living allowances 48"),
            listOf("Changing claim after filing", "Itemizing (See Itemized", "Pass-through entities 102", "Repayments 71")
        )
        val blocks = entries.mapIndexed { column, lines ->
            ExactLayout.block(lines.mapIndexed { row, text -> justified(text, 72f + column * 160f, text.length * 4.5f, 720f - row * 12f) })
        }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, blocks))

        assertTrue(layout.tables.isEmpty())
    }

    /** irs_p17 tax tables: a column of figures read top to bottom is one block, and still a table. */
    @Test
    fun columnsOfFiguresReadAsBlocksAreStillATable() {
        val columns = listOf(listOf("21,000", "21,050", "21,100", "21,150"), listOf("2,285", "2,291", "2,297", "2,303"), listOf("2,103", "2,108", "2,114", "2,118"))
        val blocks = columns.mapIndexed { column, lines ->
            ExactLayout.block(lines.mapIndexed { row, text -> justified(text, 72f + column * 60f, 25f, 720f - row * 9f) })
        }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, blocks))

        assertEquals(4, layout.tables.single().rows.size)
    }

    /**
     * irs_p17 tax tables: 7 pt rows 7.5 pt apart, tighter than a 1.2 em line. Every row's baseline
     * must still land where the PDF has it, so the shared line is the tightest pitch, not the font's.
     */
    @Test
    fun tightTableRowsKeepEveryBaselineInPlace() {
        val baselines = listOf(700f, 692.5f, 685f, 670f)
        val segments = baselines.flatMap { b -> listOf(justified("21,000", 72f, 30f, b, size = 7f), justified("2,285", 150f, 25f, b, size = 7f)) }
        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))
        val table = layout.tables.single()

        val g = tableGeometry(table)

        // The tight rows get the 7.5 pt pitch; the last row, 15 pt below, keeps its natural 8.4 pt line.
        assertEquals(listOf(7.5f, 7.5f, 7.5f, 8.4f), g.lineHeightsPt.map { Math.round(it * 100) / 100f })
        var top = g.top
        baselines.forEachIndexed { i, b ->
            assertEquals("row $i baseline", 842f - b, top + g.lineHeightsPt[i] * ExactLayout.BASELINE_RATIO, 0.01f)
            assertTrue("row $i holds its line", g.heightsPt[i] >= g.lineHeightsPt[i] - 0.01f)
            top += g.heightsPt[i]
        }
    }

    /**
     * ar_wiki: right-to-left cells can't be placed exactly in a native table yet (no indent from the
     * right edge), so a table holding right-to-left text stays as positioned text.
     */
    @Test
    fun aRightToLeftTableStaysAsText() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = true, bold = false, italic = false, sizePt = 10f, argb = 0xFF000000.toInt())
        fun rtl(text: String, x: Float, baseline: Float) =
            Segment(listOf(Token(text, style, x, x + text.length * 5f, false, 0f)), baseline, 10f, true, 0, bidi = true)
        val rows = listOf("السكان" to "37,000,000", "المساحة" to "446,550", "العاصمة" to "الرباط", "العملة" to "درهم")
        val segments = rows.flatMapIndexed { row, (key, value) -> listOf(rtl(value, 72f, 720f - row * 14f), rtl(key, 250f, 720f - row * 14f)) }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))

        assertTrue(layout.tables.isEmpty())
        assertEquals(segments.size, layout.blocks.size)
    }

    /**
     * irs_p17 page 113: side-by-side tables a few points out of step merge into one run whose rows
     * are 3 and 6 pt apart, and every row's line is cut to that pitch (clipped text). Rows closer than the
     * text is tall are not a table that can be set exactly; they stay positioned text.
     */
    @Test
    fun rowsCloserThanTheTextIsTallAreNotATable() {
        val segments = (0 until 6).flatMap { i ->
            val left = 720f - i * 9f
            // Out of step by more than the half em that groups segments into one row.
            val right = left - 6f
            listOf(justified("1,000", 72f, 25f, left), justified("101", 120f, 15f, left), justified("2,000", 300f, 25f, right), justified("201", 350f, 15f, right))
        }

        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))

        assertTrue(layout.tables.isEmpty())
    }

    @Test
    fun aPageWithoutATableIsLeftAsItWas() {
        val layout = ExactLayout.analyze(page(word("Alone", 72f, 700f)))

        val detected = ExactTables.detect(layout)

        assertTrue(detected.tables.isEmpty())
        assertEquals(layout.blocks, detected.blocks)
    }

    // --- fixtures ---

    /** One line of 10 pt text set from [x] to [x] + [width], one token per word, as a justified line is. */
    private fun justified(text: String, x: Float, width: Float, baseline: Float, size: Float = 10f): Segment {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = size, argb = 0xFF000000.toInt())
        val words = text.split(' ')
        val step = width / words.size
        val tokens = words.mapIndexed { i, word -> Token(word, style, x + i * step, x + (i + 1) * step - 2f, i > 0, 0f) }
        return Segment(tokens, baseline, size, false, 0)
    }

    private fun page(chars: List<PdfiumChar>) = PdfiumPage(595f, 842f, chars)

    private fun word(
        text: String,
        x: Float,
        baseline: Float,
        size: Float = 10f,
        scale: Float = 1f,
        advance: Float = 5f,
        flags: Int = 0,
        font: String = "Helvetica"
    ): List<PdfiumChar> = text.mapIndexed { i, c ->
        char(c, x + i * advance, baseline, size, scale, advance, flags, font)
    }

    private fun char(
        c: Char,
        x: Float,
        baseline: Float,
        size: Float = 10f,
        scale: Float = 1f,
        advance: Float = 5f,
        flags: Int = 0,
        font: String = "Helvetica"
    ) = PdfiumChar(
        code = c.code, originX = x, originY = baseline,
        left = x, bottom = baseline - 2f, right = x + advance, top = baseline + 7f,
        size = size, matrixA = scale, matrixB = 0f, matrixC = 0f, matrixD = scale,
        font = font, fontFlags = 0, weight = 400, argb = 0xFF000000.toInt(), flags = flags, renderMode = 0
    )

    private fun space(x: Float, baseline: Float) = listOf(char(' ', x, baseline).copyAsGenerated())

    private fun lineBreak() = listOf(
        PdfiumChar('\r'.code, 0f, 0f, 0f, 0f, 0f, 0f, 10f, 1f, 0f, 0f, 1f, "", 0, 400, 0, 1, 0),
        PdfiumChar('\n'.code, 0f, 0f, 0f, 0f, 0f, 0f, 10f, 1f, 0f, 0f, 1f, "", 0, 400, 0, 1, 0)
    )

    private fun PdfiumChar.copyAsGenerated() = PdfiumChar(
        code, originX, originY, left, bottom, left, top, size, matrixA, matrixB, matrixC, matrixD,
        font, fontFlags, weight, argb, 1, renderMode
    )

    private fun PdfiumChar.shifted(dx: Float) = PdfiumChar(
        code, originX + dx, originY, left + dx, bottom, right + dx, top, size, matrixA, matrixB, matrixC, matrixD,
        font, fontFlags, weight, argb, flags, renderMode
    )
}
