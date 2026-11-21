package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumRule
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.LayoutCell
import com.skeler.scanely.core.pdftools.pdf2office.LayoutLine
import com.skeler.scanely.core.pdftools.pdf2office.LayoutTables
import kotlin.math.abs
import kotlin.math.max

/** The segments on one baseline of a cell; [dropPt] is that baseline's distance below the row's first. */
class ExactCellLine(val segments: List<Segment>, val dropPt: Float = 0f)

/** A cell merged with the one above it continues that cell; the first of such a run restarts it. */
enum class CellMerge { NONE, RESTART, CONTINUE }

/**
 * One cell: usually one line, none for an empty cell, several where its text wraps. [span] is how
 * many columns it covers. [segments] is all its text, line by line.
 */
class ExactTableCell(
    val segments: List<Segment>,
    lines: List<ExactCellLine>? = null,
    val span: Int = 1,
    val merge: CellMerge = CellMerge.NONE
) {
    val lines: List<ExactCellLine> = lines ?: if (segments.isEmpty()) emptyList() else listOf(ExactCellLine(segments))
    val text: String get() = segments.joinToString(" ") { segment -> segmentText(segment) }
}

/**
 * One table row, its cells left to right as on the page; their spans add up to the column count.
 * [box] runs from the top of the row's first line to the bottom of its last, each [linePt] tall
 * with its baseline at [ExactLayout.BASELINE_RATIO]; for a row of one line that is the line itself.
 */
class ExactTableRow(val cells: List<ExactTableCell>, val box: LayoutBox, val fontSize: Float, linePt: Float? = null) {
    val linePt: Float = linePt ?: box.height
    val firstBaselinePt: Float get() = box.top + linePt * ExactLayout.BASELINE_RATIO
    val lastBaselinePt: Float get() = box.bottom - linePt * (1f - ExactLayout.BASELINE_RATIO)
}

/**
 * [columnWidthsPt] run from each column's left edge to the next one's, from [box]'s left edge.
 * [rtl]: a right-to-left table, whose first column is the rightmost on the page.
 */
class ExactTable(
    val rows: List<ExactTableRow>,
    val columnWidthsPt: List<Float>,
    val box: LayoutBox,
    val fontSize: Float,
    val rtl: Boolean = false
) {
    val columns: Int get() = rows.maxOf { row -> row.cells.sumOf { it.span } }
}

/**
 * Finds tables in an exact layout with the same detector as the flowing converters
 * ([LayoutTables]): upright segments on one baseline become a row, each segment a cell. Table text
 * leaves the blocks, so the writers draw it once, as a native table.
 */
@Suppress("TooManyFunctions")
object ExactTables {

    private const val SAME_ROW = 0.5f
    private const val MIN_SEGMENTS = 4
    private const val COLUMN_EDGE_PT = 0.01f

    // Measured with hostconv's table report on the fidelity corpus: real tables (fed_h8, the tax
    // tables, irs_p17's worksheets) have 0-0.08 of their cells in such blocks, or are mostly
    // figures; the index pages 0.11-0.6. (0.25 at first, which let index fragments through.)
    private const val PARAGRAPH_LINES = 3
    private const val PARAGRAPH_SHARE = 0.1f
    private const val FIGURES_SHARE = 0.2f

    /** Real tables measured at 1.0 em and up (irs_p17's 7 pt worksheets at 7 pt); merged out-of-step tables at 0.3-0.5. */
    private const val MIN_PITCH_EM = 0.9f
    private const val MAX_ROW_SIZE_RATIO = 1.5f

    private class Row(val segments: List<Segment>, val line: LayoutLine)

    /** [modelTables]: the layout model's table regions, in points from the page's top left; see [LayoutTables.findTables]. */
    fun detect(
        layout: ExactPageLayout,
        modelTables: List<LayoutBox>? = null,
        rules: List<PdfiumRule> = emptyList()
    ): ExactPageLayout {
        val upright = layout.blocks.flatMap { it.lines }.filter { it.angleDeg == 0 && it.tokens.isNotEmpty() }
        val ruled = ruled(upright, rules, layout.heightPt)
        val taken = ruled.flatMapTo(HashSet()) { it.segments }
        val segments = upright.filter { it !in taken }
        val afterRuled = if (ruled.isEmpty()) {
            layout
        } else {
            ExactPageLayout(
                layout.widthPt,
                layout.heightPt,
                layout.blocks.flatMap { remaining(it, taken) },
                layout.behindPicture,
                layout.tables + ruled.map { it.table },
                tags = layout.tags
            )
        }
        return aligned(afterRuled, segments, modelTables)
    }

    /** The tables the page's ruling lines draw; a segment goes to the first grid that holds it. */
    private fun ruled(
        segments: List<Segment>,
        rules: List<PdfiumRule>,
        pageHeightPt: Float
    ): List<ExactRuledTables.Found> {
        if (rules.isEmpty() || segments.isEmpty()) return emptyList()
        val taken = HashSet<Segment>()
        return ExactRules.grids(rules, pageHeightPt, segments).mapNotNull { grid ->
            ExactRuledTables.table(grid, segments.filter { it !in taken }, pageHeightPt)?.also { taken += it.segments }
        }
    }

    /** Tables found by the alignment of their text alone, among [segments] (those no ruled table took). */
    private fun aligned(
        layout: ExactPageLayout,
        segments: List<Segment>,
        modelTables: List<LayoutBox>?
    ): ExactPageLayout {
        if (segments.size < MIN_SEGMENTS) return layout
        val rows = rows(segments, layout.heightPt)
        val found = LayoutTables.findTables(rows.map { it.line }, modelTables)
        val ranges = found.ranges
        if (ranges.isEmpty()) return layout.copy(unconfirmedTables = found.unconfirmed)
        val paragraphLines = HashSet<Segment>()
        layout.blocks.forEach { block -> if (block.lines.size >= PARAGRAPH_LINES) paragraphLines += block.lines }
        val used = HashSet<Segment>()
        val tables = ranges.mapNotNull { range ->
            val group = rows.subList(range.first, range.last + 1)
            if (!placeable(group, paragraphLines)) return@mapNotNull null
            group.forEach { used += it.segments }
            table(group)
        }
        if (tables.isEmpty()) return layout.copy(unconfirmedTables = found.unconfirmed)
        val blocks = layout.blocks.flatMap { remaining(it, used) }
        return ExactPageLayout(
            layout.widthPt,
            layout.heightPt,
            blocks,
            layout.behindPicture,
            layout.tables + tables,
            found.unconfirmed,
            layout.tags
        )
    }

    /** Whether [group] can be written as an exact native table without losing what the page shows. */
    private fun placeable(group: List<Row>, paragraphLines: Set<Segment>): Boolean = when {
        isRunningText(group, paragraphLines) -> false
        // Right-to-left cells can't be placed exactly in a native table yet (no indent from the
        // cell's right edge); positioned text keeps such a table where the PDF has it.
        group.any { row -> row.segments.any { it.rtl } } -> false
        // Cells of one row share a text size; a title beside small print (irs_p17's cover) only lines up.
        group.any { row -> mixesSizes(row.segments) } -> false
        // Rows closer than their text is tall (side-by-side tables out of step, irs_p17 page 113)
        // would each be cut to that pitch and clipped: no exact native table for them.
        else -> group.zipWithNext().none { (row, next) ->
            baseline(next) - baseline(row) < max(row.line.fontSize, next.line.fontSize) * MIN_PITCH_EM
        }
    }

    private fun mixesSizes(segments: List<Segment>): Boolean =
        segments.maxOf { it.sizePt } > segments.minOf { it.sizePt } * MAX_ROW_SIZE_RATIO

    /** Segments grouped by baseline, top of the page first; each row as the line model [LayoutTables] reads. */
    private fun rows(segments: List<Segment>, pageHeightPt: Float): List<Row> {
        val sorted = segments.sortedWith(compareByDescending<Segment> { it.baselineV }.thenBy { it.u0 })
        val groups = ArrayList<MutableList<Segment>>()
        var v = 0f
        var size = 0f
        for (segment in sorted) {
            val current = groups.lastOrNull()
            if (current != null && abs(segment.baselineV - v) <= max(size, segment.sizePt) * SAME_ROW) {
                current += segment
                size = max(size, segment.sizePt)
            } else {
                groups += mutableListOf(segment)
                v = segment.baselineV
                size = segment.sizePt
            }
        }
        return groups.map { group -> row(group.sortedBy { it.u0 }, pageHeightPt) }
    }

    private fun row(segments: List<Segment>, pageHeightPt: Float): Row {
        val main = segments.maxBy { it.sizePt }
        val height = segments.maxOf { ExactLayout.singleLineHeight(it) }
        val top = pageHeightPt - main.baselineV - height * ExactLayout.BASELINE_RATIO
        val bottom = top + height
        val cells = segments.map { segment ->
            val text = segmentText(segment)
            LayoutCell(text, LayoutBox(segment.u0, top, segment.u1, bottom), text.split(' ').count { it.isNotEmpty() })
        }
        val box = LayoutBox(segments.minOf { it.u0 }, top, segments.maxOf { it.u1 }, bottom)
        return Row(segments, LayoutLine(emptyList(), box, main.sizePt, cells))
    }

    /**
     * The exact path has no column regions to keep text columns apart (the flowing path does, and
     * lays out paragraphs before tables), but [ExactLayout] has already joined the lines of
     * each paragraph or list into a block. A run built largely from such lines is columns of text
     * that happen to share baselines (an index, captions side by side), unless it holds figures: a
     * column of numbers read top to bottom is a block too.
     */
    private fun isRunningText(group: List<Row>, paragraphLines: Set<Segment>): Boolean {
        val segments = group.flatMap { it.segments }
        val inParagraphs = segments.count { it in paragraphLines }
        val numeric = segments.count { LayoutTables.isNumeric(segmentText(it)) }
        return inParagraphs >= segments.size * PARAGRAPH_SHARE && numeric < segments.size * FIGURES_SHARE
    }

    /** A row's baseline down the page, in top-left page points. */
    private fun baseline(row: Row): Float = row.line.box.top + row.line.box.height * ExactLayout.BASELINE_RATIO

    /** The detector's table, with each segment in the column whose left edge it starts at or after. */
    private fun table(group: List<Row>): ExactTable {
        val block = LayoutTables.buildTable(group.map { it.line })
        val columns = block.rows.first().size
        // The widths are optional on a TableBlock; without them the box is divided equally.
        val widths = block.columnWidthsPt.takeIf { it.size == columns } ?: List(columns) { block.box.width / columns }
        val lefts = widths.runningFold(block.box.left) { left, width -> left + width }.dropLast(1)
        val xs = lefts + block.box.right
        val lines = group.map { row ->
            val slots = HashMap<Int, MutableList<Segment>>()
            row.segments.forEach { segment ->
                slots.getOrPut(
                    lefts.indexOfLast { it <= segment.u0 + COLUMN_EDGE_PT }.coerceAtLeast(0)
                ) { ArrayList() } += segment
            }
            GridLine(baseline(row), row.line.box.height, row.line.fontSize, slots)
        }
        // Lines that are a cell's wrapped text join their row; every other line is a row of its own.
        val cells = lefts.indices.map { it..it }
        val rows = ExactTableGrid.rows(lines, enclosed = false).map { ExactTableGrid.row(it, cells, xs, block.box) }
        return ExactTable(rows, widths, block.box, block.fontSize)
    }

    /** [block] without the table's lines: each run of lines it keeps becomes a block of its own. */
    private fun remaining(block: Block, used: Set<Segment>): List<Block> {
        if (block.lines.none { it in used }) return listOf(block)
        val runs = ArrayList<List<Segment>>()
        var run = ArrayList<Segment>()
        for (line in block.lines) {
            if (line in used) {
                if (run.isNotEmpty()) runs += run
                run = ArrayList()
            } else {
                run += line
            }
        }
        if (run.isNotEmpty()) runs += run
        return runs.map(ExactLayout::block)
    }
}

/** A segment's words in reading order, a space wherever the page had one. */
internal fun segmentText(segment: Segment): String = buildString {
    segment.tokens.forEachIndexed { i, token ->
        if (i > 0 && token.spaceBefore) append(' ')
        append(token.text)
    }
}
