package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import java.text.Normalizer

/**
 * One paragraph as cloud OCR read it from a page image: its box as fractions of the page from the
 * top-left, and its words in reading order. Mistral OCR returns paragraph-level boxes only (no
 * per-word boxes), so a block is matched to the page's lines by position and to their words by
 * alignment.
 */
class OcrBlock(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

/**
 * Words the PDF's text cannot be trusted for, read again from the page image. A token holds an
 * uncertain glyph ([Token.uncertain]) when the embedded font could not name it and the PDF's mapping
 * for it may be wrong (Word's Arabic exports). The page is read by OCR as a whole, which gives the
 * model the context Arabic needs; each OCR paragraph ([OcrBlock]) is laid over the lines whose middle
 * falls inside its box, and its words are aligned to their tokens ([corrected]): an uncertain token
 * takes the word it lines up with when the two mostly agree, so OCR replaces a wrong letter and
 * nothing else. Positions, styles and every certain token stay as the PDF has them.
 */
internal object ExactOcrWords {

    /** An OCR word replaces an uncertain token when this share of their letters agree. */
    private const val MIN_AGREEMENT = 0.5f

    /** Whether any line of [layout] holds an uncertain token: the page is worth reading again. */
    fun uncertain(layout: ExactPageLayout): Boolean {
        var found = false
        forEachSegment(layout) { segment -> if (segment.tokens.any { it.uncertain }) found = true }
        return found
    }

    /** The box of each upright line that holds an uncertain token, in points from the top-left (reports). */
    fun uncertainLines(layout: ExactPageLayout): List<LayoutBox> {
        val boxes = ArrayList<LayoutBox>()
        forEachSegment(layout) { segment ->
            if (segment.angleDeg == 0 && segment.tokens.any { it.uncertain }) boxes += box(segment, layout.heightPt)
        }
        return boxes
    }

    /**
     * [layout] with the uncertain tokens of the lines under each of [blocks] corrected by the block's
     * words. A line belongs to the first block that holds its middle; lines of one block are read in
     * the layout's order, which is reading order within a block.
     */
    fun applyBlocks(layout: ExactPageLayout, blocks: List<OcrBlock>): ExactPageLayout {
        if (blocks.isEmpty() || !uncertain(layout)) return layout
        val inTables = tableSegments(layout)
        val fixed = HashMap<Segment, Segment>()
        for ((i, segments) in members(layout, blocks)) {
            if (segments.none { s -> s.tokens.any { it.uncertain } }) continue
            corrected(segments, blocks[i].text, inTables).forEach { (old, new) -> fixed[old] = new }
        }
        return if (fixed.isEmpty()) layout else withFixed(layout, fixed)
    }

    /** The upright lines of [layout] under each of [blocks] (by index): a line goes to the first block holding its middle. */
    private fun members(layout: ExactPageLayout, blocks: List<OcrBlock>): Map<Int, List<Segment>> {
        val members = LinkedHashMap<Int, MutableList<Segment>>()
        forEachSegment(layout) { segment ->
            if (segment.angleDeg != 0) return@forEachSegment
            val b = box(segment, layout.heightPt)
            val x = (b.left + b.right) / 2f / layout.widthPt
            val y = (b.top + b.bottom) / 2f / layout.heightPt
            val i = blocks.indexOfFirst { holds(it, x, y) }
            if (i >= 0) {
                members.getOrPut(i) { ArrayList() } += segment
            }
        }
        return members
    }

    private fun withFixed(layout: ExactPageLayout, fixed: Map<Segment, Segment>): ExactPageLayout {
        val fix = { segment: Segment -> fixed[segment] ?: segment }
        val blocksOut = layout.blocks.map { block -> Block(block.lines.map(fix), block.pitchPt, block.indentPt) }
        val tables = layout.tables.map { table -> table(table, fix) }
        return ExactPageLayout(
            layout.widthPt,
            layout.heightPt,
            blocksOut,
            layout.behindPicture,
            tables,
            layout.unconfirmedTables,
            layout.tags
        )
    }

    /**
     * [segments] (in reading order) with their uncertain tokens replaced by the words of [read] they
     * align with. Alignment is by edit distance over words, a substitution costing what the two words
     * disagree on, so a word OCR splits or misses shifts nothing else.
     */
    fun corrected(
        segments: List<Segment>,
        read: String,
        inTables: Set<Segment> = emptySet()
    ): List<Pair<Segment, Segment>> {
        val words = read.split(' ').filter { word -> WordAlignment.letters(word).isNotEmpty() }
        val tokens = segments.flatMap { it.tokens }
        if (words.isEmpty()) return emptyList()
        val pairs = WordAlignment.align(tokens.map { it.text }, words)
        var next = 0
        return segments.map { segment ->
            val out = segment.tokens.map { token ->
                val word = pairs[next++]?.let { words[it] }?.takeIf {
                    replaceable(token, segment in inTables) && it != token.text &&
                        WordAlignment.agreement(token.text, it) >= MIN_AGREEMENT
                }
                if (word == null) {
                    token
                } else {
                    Token(
                        word,
                        token.style,
                        token.u0,
                        token.u1,
                        token.spaceBefore,
                        token.raisePt,
                        token.gapBeforePt,
                        mcid = token.mcid
                    )
                }
            }
            segment to Segment(out, segment.baselineV, segment.sizePt, segment.rtl, segment.angleDeg, segment.bidi)
        }
    }

    /**
     * A token OCR may correct: one with an uncertain glyph, or any Arabic word on a page that was read
     * again — the page was sent because its fonts' mapping is wrong, and a letter the mapping lost
     * (a final ة or م dropped by pdfium) leaves no uncertain glyph behind. Latin words and figures
     * stay as the PDF has them. In a table only uncertain tokens: OCR reads a table's cells in its own
     * order, which aligns with the layout's cells too loosely to trust for anything else.
     */
    private fun replaceable(token: Token, inTable: Boolean): Boolean =
        token.uncertain || !inTable && token.text.codePoints().anyMatch(ExactScripts::isArabic)
}

/** Words of a PDF line paired with the words OCR read for it, by edit distance. */
internal object WordAlignment {
    /**
     * For each token, the index of the word it aligns with, or null. Each word's letters are folded
     * once and each pair's cost is kept, so a block of n tokens and m words costs n·m comparisons.
     */
    fun align(tokens: List<String>, words: List<String>): Array<Int?> {
        val x = tokens.map(::letters)
        val y = words.map(::letters)
        val agree = Array(tokens.size) { i -> FloatArray(words.size) { j -> agreement(x[i], y[j]) } }
        val cost = costs(agree, tokens.size, words.size)
        val out = arrayOfNulls<Int>(tokens.size)
        var i = tokens.size
        var j = words.size
        while (i > 0 && j > 0) {
            when {
                cost[i][j] == cost[i - 1][j - 1] + 1f - agree[i - 1][j - 1] -> {
                    out[i - 1] = j - 1
                    i--
                    j--
                }
                cost[i][j] == cost[i - 1][j] + 1f -> i--
                else -> j--
            }
        }
        return out
    }

    /** Edit distance table over words: skipping a word costs 1, pairing two what they disagree on. */
    private fun costs(agree: Array<FloatArray>, n: Int, m: Int): Array<FloatArray> {
        val cost = Array(n + 1) { FloatArray(m + 1) }
        for (i in 0..n) cost[i][0] = i.toFloat()
        for (j in 0..m) cost[0][j] = j.toFloat()
        for (i in 1..n) {
            for (j in 1..m) {
                val pair = cost[i - 1][j - 1] + 1f - agree[i - 1][j - 1]
                cost[i][j] = minOf(pair, cost[i - 1][j] + 1f, cost[i][j - 1] + 1f)
            }
        }
        return cost
    }

    /** The share of letters two words have in common, compared as base letters (presentation forms folded). */
    fun agreement(a: String, b: String): Float = agreement(letters(a), letters(b))

    private fun agreement(x: List<Int>, y: List<Int>): Float {
        if (x.isEmpty() || y.isEmpty()) return 0f
        val counts = HashMap<Int, Int>()
        x.forEach { counts.merge(it, 1, Int::plus) }
        var common = 0
        y.forEach { c ->
            val left = counts[c] ?: 0
            if (left > 0) {
                common++
                counts[c] = left - 1
            }
        }
        return 2f * common / (x.size + y.size)
    }

    fun letters(text: String): List<Int> =
        Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints().toArray()
            .filter { Character.isLetterOrDigit(it) }
}

/** A line's box reaches this far above and below its baseline, in text sizes. */
private const val ASCENT_EM = 1.0f
private const val DESCENT_EM = 0.45f

private fun box(segment: Segment, pageHeightPt: Float): LayoutBox {
    val baseline = pageHeightPt - segment.baselineV
    val size = segment.sizePt
    return LayoutBox(segment.u0, baseline - size * ASCENT_EM, segment.u1, baseline + size * DESCENT_EM)
}

private fun forEachSegment(layout: ExactPageLayout, action: (Segment) -> Unit) {
    layout.blocks.forEach { block -> block.lines.forEach(action) }
    layout.tables.forEach { table ->
        table.rows.forEach { row ->
            row.cells.forEach { cell -> cell.lines.forEach { it.segments.forEach(action) } }
        }
    }
}

private fun table(table: ExactTable, fix: (Segment) -> Segment): ExactTable {
    val rows = table.rows.map { row ->
        val cells = row.cells.map { cell ->
            val lines = cell.lines.map { line -> ExactCellLine(line.segments.map(fix), line.dropPt) }
            ExactTableCell(lines.flatMap { it.segments }, lines, cell.span, cell.merge)
        }
        ExactTableRow(cells, row.box, row.fontSize, row.linePt)
    }
    return ExactTable(rows, table.columnWidthsPt, table.box, table.fontSize, table.rtl)
}

/** Every segment of [layout]'s tables. */
private fun tableSegments(layout: ExactPageLayout): Set<Segment> {
    val inTables = HashSet<Segment>()
    layout.tables.forEach { table ->
        table.rows.forEach { row -> row.cells.forEach { cell -> cell.lines.forEach { inTables += it.segments } } }
    }
    return inTables
}

/** [block] (fractions of the page) holds the point [x], [y]. */
private fun holds(block: OcrBlock, x: Float, y: Float): Boolean =
    x >= block.left && x <= block.right && y >= block.top && y <= block.bottom
