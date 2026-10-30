package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.max
import kotlin.math.min

/**
 * One character as the text layer reports it, in top-left-origin page points. A character the
 * layer places nowhere — a line-break space, a soft hyphen — has a zero-area box and only ever
 * separates words.
 */
data class LayoutChar(
    val char: Char,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontSize: Float
) {
    val hasBox: Boolean get() = right > left && bottom > top
}

data class LayoutBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: LayoutBox) = LayoutBox(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom)
    )

    companion object {
        val ZERO = LayoutBox(0f, 0f, 0f, 0f)
    }
}

data class LayoutWord(val text: String, val box: LayoutBox, val fontSize: Float) {
    /** Hebrew or Arabic letters outnumber Latin ones: the word reads right to left. */
    val rtl: Boolean get() = TextDirection.isRtl(text)
}

/** Which way a run of text reads, from its strong letters (digits and punctuation are neutral). */
internal object TextDirection {
    fun isRtlChar(char: Char): Boolean = when (Character.getDirectionality(char)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
        else -> false
    }

    private fun isLtrChar(char: Char): Boolean =
        Character.getDirectionality(char) == Character.DIRECTIONALITY_LEFT_TO_RIGHT

    fun isRtl(text: CharSequence): Boolean {
        var balance = 0
        for (char in text) {
            if (isRtlChar(char)) {
                balance++
            } else if (isLtrChar(char)) {
                balance--
            }
        }
        return balance > 0
    }

    /** Most words, by count, read right to left. */
    fun isRtl(words: List<LayoutWord>): Boolean = words.count { it.rtl } * 2 > words.size
}

/** A run of words on one line separated from its neighbours by a column-sized gap. */
data class LayoutCell(val text: String, val box: LayoutBox, val wordCount: Int)

data class LayoutLine(
    val words: List<LayoutWord>,
    val box: LayoutBox,
    val fontSize: Float,
    val cells: List<LayoutCell>
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

enum class BlockKind { HEADING_1, HEADING_2, BODY, BULLET, NUMBERED }

sealed interface LayoutBlock {
    val box: LayoutBox
    val fontSize: Float
}

/**
 * [text] is the paragraph reflowed into one string (what a word processor wants); [lines] and
 * [lineBoxes] keep the original line breaks and positions (what a slide wants). A [BlockKind.BULLET]
 * has its marker glyph removed, since the writers draw their own.
 */
data class ParagraphBlock(
    val kind: BlockKind,
    val text: String,
    val lines: List<String>,
    val lineBoxes: List<LayoutBox>,
    override val box: LayoutBox,
    override val fontSize: Float
) : LayoutBlock

/**
 * Every row has the same number of cells; an empty string is an empty cell. [columnWidthsPt], when
 * known, runs from each column's left edge to the next one's (the last to the table's right edge),
 * so the widths add up to [box]'s width; empty means equal columns.
 */
data class TableBlock(
    val rows: List<List<String>>,
    val rowBoxes: List<LayoutBox>,
    override val box: LayoutBox,
    override val fontSize: Float,
    val columnWidthsPt: List<Float> = emptyList()
) : LayoutBlock

/**
 * One page, in reading order. [positioned] is false when the page was too dense to analyse and
 * came back as plain lines with no geometry; [simplified] says the same thing to the result notes.
 * [unconfirmedTables]: a possible table was left out until the layout model looks at the page
 * ([LayoutTables.Found.unconfirmed]); analyse again with its table regions to settle it.
 */
data class PageLayout(val blocks: List<LayoutBlock>, val positioned: Boolean, val unconfirmedTables: Boolean = false) {
    val simplified: Boolean get() = !positioned

    val tables: List<TableBlock> get() = blocks.filterIsInstance<TableBlock>()

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Each visual line once, for a spreadsheet that has no tables to show. */
    val textLines: List<String>
        get() = blocks.flatMap { block ->
            when (block) {
                is ParagraphBlock -> block.lines
                is TableBlock -> block.rows.map { row -> row.filter { it.isNotEmpty() }.joinToString(" ") }
            }
        }.filter { it.isNotBlank() }
}

/**
 * Turns a page's characters into the structure an office document can hold: paragraphs, headings,
 * list items and tables. Every rule is a heuristic over geometry alone — there is no tagged
 * structure in most PDFs to read instead — so each one errs towards plain paragraphs, which are
 * always editable, rather than towards structure that might be wrong.
 *
 * All work is bounded by [MAX_CHARS_PER_PAGE]: a page above it (a generated report, a map full of
 * labels) skips the geometry entirely and becomes plain lines, so no input can stall a conversion.
 * Sorting dominates, so a page at the cap costs well under a second.
 */
object LayoutAnalyzer {

    const val MAX_CHARS_PER_PAGE = 50_000

    /** Plain-line fallback reads at most this much, so a dense page can't build a huge string. */
    const val MAX_PLAIN_TEXT_CHARS = 200_000

    /** [modelTables]: the layout model's table regions on this page, in points from the top left; see [LayoutTables.findTables]. */
    fun analyze(chars: List<LayoutChar>, modelTables: List<LayoutBox>? = null): PageLayout {
        if (chars.size > MAX_CHARS_PER_PAGE) {
            return plainLines(chars.take(MAX_PLAIN_TEXT_CHARS).joinToString("") { it.char.toString() })
        }
        val words = LayoutWords.words(chars)
        if (words.isEmpty()) return PageLayout(emptyList(), positioned = true)
        // Each region (a column, a headline, a box) is read on its own, in reading order.
        val regionLines = LayoutRegions.regions(words).map(LayoutLines::lines)
        val bodySize = LayoutLines.bodySize(regionLines.flatten())
        var unconfirmed = false
        val blocks = regionLines.flatMap { lines ->
            val found = LayoutTables.findTables(lines, modelTables)
            unconfirmed = unconfirmed || found.unconfirmed
            blocks(lines, found.ranges, bodySize)
        }
        return PageLayout(blocks, positioned = true, unconfirmedTables = unconfirmed)
    }

    private fun blocks(lines: List<LayoutLine>, tables: List<IntRange>, bodySize: Float): List<LayoutBlock> {
        val blocks = mutableListOf<LayoutBlock>()
        var index = 0
        var tableCursor = 0
        while (index < lines.size) {
            val table = tables.getOrNull(tableCursor)
            if (table != null && table.first == index) {
                blocks += LayoutTables.buildTable(lines.subList(table.first, table.last + 1))
                index = table.last + 1
                tableCursor++
                continue
            }
            val end = table?.first ?: lines.size
            blocks += LayoutParagraphs.paragraphs(lines.subList(index, end), bodySize)
            index = end
        }
        return blocks
    }

    /** No geometry: every non-blank line is its own body paragraph. */
    fun plainLines(text: String): PageLayout {
        val blocks = text.take(MAX_PLAIN_TEXT_CHARS)
            .lineSequence()
            .map { clean(it).trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_PLAIN_LINES)
            .map { line ->
                ParagraphBlock(BlockKind.BODY, line, listOf(line), listOf(LayoutBox.ZERO), LayoutBox.ZERO, 0f)
            }
            .toList()
        return PageLayout(blocks, positioned = false)
    }

    private fun clean(text: String): String = text.filter { it == '\t' || LayoutWords.isPrintable(it) }

    private const val MAX_PLAIN_LINES = 10_000
}
