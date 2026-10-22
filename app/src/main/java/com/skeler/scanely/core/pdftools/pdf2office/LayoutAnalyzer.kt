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

data class LayoutWord(val text: String, val box: LayoutBox, val fontSize: Float)

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

/** Every row has the same number of cells; an empty string is an empty cell. */
data class TableBlock(
    val rows: List<List<String>>,
    val rowBoxes: List<LayoutBox>,
    override val box: LayoutBox,
    override val fontSize: Float
) : LayoutBlock

/**
 * One page, in reading order. [positioned] is false when the page was too dense to analyse and
 * came back as plain lines with no geometry; [simplified] says the same thing to the result notes.
 */
data class PageLayout(val blocks: List<LayoutBlock>, val positioned: Boolean) {
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

    fun analyze(chars: List<LayoutChar>): PageLayout {
        if (chars.size > MAX_CHARS_PER_PAGE) {
            return plainLines(chars.take(MAX_PLAIN_TEXT_CHARS).joinToString("") { it.char.toString() })
        }
        val words = LayoutWords.words(chars)
        if (words.isEmpty()) return PageLayout(emptyList(), positioned = true)
        val lines = LayoutLines.lines(words)
        val bodySize = LayoutLines.bodySize(lines)
        val tables = LayoutTables.findTables(lines)

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
        return PageLayout(blocks, positioned = true)
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
