package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.LayoutTables
import com.skeler.scanely.core.pdftools.pdf2office.carriesOn
import kotlin.math.abs
import kotlin.math.max

/**
 * The text on one baseline of a table, cut at the column edges: [cells] maps a column to its
 * pieces, left to right. [baselinePt] is down the page from its top.
 */
internal class GridLine(
    val baselinePt: Float,
    val linePt: Float,
    val sizePt: Float,
    val cells: Map<Int, List<Segment>>
) {
    fun text(column: Int): String = cells[column].orEmpty().joinToString(" ") { segmentText(it) }
    val isFigures: Boolean get() = cells.keys.any { ExactTableGrid.isFigure(text(it)) }
}

/**
 * Text into grid cells, and the lines of a stretch of table into rows. A cell whose text wraps
 * holds several lines; they are one row, not one row per line. Used for ruled tables
 * ([ExactRuledTables]) and for the tables found by alignment alone ([ExactTables]).
 */
@Suppress("TooManyFunctions")
internal object ExactTableGrid {

    private const val SAME_LINE = 0.5f

    /** As [ExactTables]: a stretch whose cells are this much figures is a table of values, one row per line. */
    private const val FIGURES_SHARE = 0.2f

    /** A gap this much wider than the usual pitch between lines separates two rows of wrapped text. */
    private const val ROW_GAP_RATIO = 1.25f

    /** A line this much closer to its neighbour than the rows are apart belongs to that neighbour's row. */
    private const val TIGHT_RATIO = 0.85f

    /** Wrapped text carries on from line to line in at least this share of its line pairs. */
    private const val CARRIES_ON_SHARE = 0.5f
    private const val PLACEHOLDERS = "-–—"

    /** A number, or the dash a table of figures puts where there is none. */
    fun isFigure(text: String): Boolean =
        text.isNotEmpty() && (LayoutTables.isNumeric(text) || text.all { it in PLACEHOLDERS || it.isWhitespace() })

    /** [segments] grouped by baseline, top first, each cut into the columns between [xs]. */
    fun lines(segments: List<Segment>, xs: List<Float>, pageHeightPt: Float): List<GridLine> {
        val sorted = segments.sortedWith(compareByDescending<Segment> { it.baselineV }.thenBy { it.u0 })
        val groups = ArrayList<MutableList<Segment>>()
        var v = 0f
        var size = 0f
        for (segment in sorted) {
            val current = groups.lastOrNull()
            if (current != null && abs(segment.baselineV - v) <= max(size, segment.sizePt) * SAME_LINE) {
                current += segment
                size = max(size, segment.sizePt)
            } else {
                groups += mutableListOf(segment)
                v = segment.baselineV
                size = segment.sizePt
            }
        }
        return groups.map { group ->
            val main = group.maxBy { it.sizePt }
            val cells = HashMap<Int, MutableList<Segment>>()
            group.forEach { segment ->
                cut(segment, xs).forEach { (column, piece) -> cells.getOrPut(column) { ArrayList() } += piece }
            }
            cells.values.forEach { pieces -> pieces.sortBy { it.u0 } }
            GridLine(
                pageHeightPt - main.baselineV,
                group.maxOf { ExactLayout.singleLineHeight(it) },
                main.sizePt,
                cells
            )
        }
    }

    /** [segment] as one piece per column it has words in; the words keep their reading order. */
    private fun cut(segment: Segment, xs: List<Float>): List<Pair<Int, Segment>> {
        val byColumn = segment.tokens.groupBy { column((it.u0 + it.u1) / 2f, xs) }
        if (byColumn.size == 1) return listOf(byColumn.keys.first() to segment)
        return byColumn.map { (column, tokens) ->
            column to Segment(tokens, segment.baselineV, segment.sizePt, segment.rtl, segment.angleDeg, segment.bidi)
        }
    }

    fun column(x: Float, xs: List<Float>): Int = (xs.indexOfLast { it <= x }).coerceIn(0, xs.size - 2)

    /**
     * [lines] (one stretch of a table with no rule inside it, top first) as rows. [enclosed]: rules
     * bound the stretch above and below, so it may be a single row of wrapped text.
     *
     * A stretch of figures has a row per line; a line with no figure that sits closer to a
     * neighbour than the rows are apart, or that carries its sentence on, is that row's wrapped
     * text. A stretch of prose is one row per paragraph gap that crosses every column.
     */
    fun rows(lines: List<GridLine>, enclosed: Boolean): List<List<GridLine>> {
        if (lines.size < 2) return listOf(lines).filter { it.isNotEmpty() }
        val cells = lines.sumOf { it.cells.size }
        val figures = lines.sumOf { line -> line.cells.keys.count { isFigure(line.text(it)) } }
        return when {
            figures >= cells * FIGURES_SHARE -> figureRows(lines)
            enclosed && isWrappedText(lines) -> proseRows(lines)
            else -> figureRows(lines)
        }
    }

    /** Most lines carry on from the line above them in their column: paragraphs, not entries. */
    private fun isWrappedText(lines: List<GridLine>): Boolean {
        var pairs = 0
        var carried = 0
        for ((line, next) in lines.zipWithNext()) {
            for (column in next.cells.keys) {
                val above = line.text(column)
                val below = next.text(column)
                if (above.isEmpty() || below.isEmpty()) continue
                pairs++
                if (carriesOn(above, below)) carried++
            }
        }
        return pairs > 0 && carried >= pairs * CARRIES_ON_SHARE
    }

    private fun proseRows(lines: List<GridLine>): List<List<GridLine>> {
        val pitch = columnPitch(lines)
        val rows = ArrayList<MutableList<GridLine>>()
        lines.forEachIndexed { i, line ->
            val gap = if (i == 0) 0f else line.baselinePt - lines[i - 1].baselinePt
            if (i == 0 || gap > pitch * ROW_GAP_RATIO) rows += mutableListOf(line) else rows.last() += line
        }
        return rows
    }

    /** The usual distance between a line and the next one in the same column. */
    private fun columnPitch(lines: List<GridLine>): Float {
        val gaps = ArrayList<Float>()
        val last = HashMap<Int, Float>()
        for (line in lines) {
            for (column in line.cells.keys) {
                last[column]?.let { gaps += line.baselinePt - it }
                last[column] = line.baselinePt
            }
        }
        return if (gaps.isEmpty()) Float.MAX_VALUE else gaps.sorted()[gaps.size / 2]
    }

    private fun figureRows(lines: List<GridLine>): List<List<GridLine>> {
        val tight = rowPitch(lines) * TIGHT_RATIO
        // Runs of lines that sit closer than rows do, or carry a sentence on: each is one row's
        // wrapped text, unless it holds several lines of figures.
        val runs = ArrayList<MutableList<GridLine>>()
        lines.forEachIndexed { i, line ->
            val above = lines.getOrNull(i - 1)
            val joined = above != null &&
                (line.baselinePt - above.baselinePt <= tight || (!line.isFigures && continues(above, line)))
            if (joined) runs.last() += line else runs += mutableListOf(line)
        }
        return runs.flatMap { run -> if (run.count { it.isFigures } <= 1) listOf(run) else splitAtFigures(run) }
    }

    /**
     * A tight run with several lines of figures: a row per line of figures. The lines without
     * figures are wrapped text of the row whose figures sit on its last line when the run opens
     * with such a line (figures at the foot of their cells), else of the row above them.
     */
    private fun splitAtFigures(run: List<GridLine>): List<List<GridLine>> {
        val rows = ArrayList<MutableList<GridLine>>()
        val figuresLast = !run.first().isFigures
        var open = false
        for (line in run) {
            val starts = if (figuresLast) !open else line.isFigures
            if (rows.isEmpty() || starts) {
                rows += mutableListOf(line)
            } else {
                rows.last() += line
            }
            open = figuresLast && !line.isFigures
        }
        return rows
    }

    /** [line] has text only where the line above has, and each piece carries on from the one above it. */
    private fun continues(above: GridLine, line: GridLine): Boolean = line.cells.keys.all { column ->
        val before = above.text(column)
        before.isNotEmpty() && carriesOn(before, line.text(column))
    }

    /** The distance between rows: the tightest gap between two lines of figures, else the widest gap. */
    private fun rowPitch(lines: List<GridLine>): Float {
        val gaps = lines.zipWithNext().filter { (a, b) -> a.isFigures && b.isFigures }
            .map { (a, b) -> b.baselinePt - a.baselinePt }
        return gaps.minOrNull() ?: lines.zipWithNext { a, b -> b.baselinePt - a.baselinePt }.max()
    }

    /**
     * One row from its [lines]. [groups] are its cells as column ranges (a merged cell covers
     * several columns); [band] is the row's extent when it has no text to take it from.
     */
    fun row(lines: List<GridLine>, groups: List<IntRange>, xs: List<Float>, band: LayoutBox): ExactTableRow {
        if (lines.isEmpty()) {
            return ExactTableRow(groups.map { ExactTableCell(emptyList(), span = it.count()) }, band, 0f)
        }
        val linePt = lines.maxOf { it.linePt }
        val first = lines.first().baselinePt
        val cells = groups.map { group ->
            val cellLines = lines.mapNotNull { line ->
                val pieces = group.flatMap { line.cells[it].orEmpty() }.sortedBy { it.u0 }
                if (pieces.isEmpty()) null else ExactCellLine(pieces, line.baselinePt - first)
            }
            ExactTableCell(cellLines.flatMap { it.segments }, cellLines, span = group.count())
        }
        val box = LayoutBox(
            xs.first(),
            first - linePt * ExactLayout.BASELINE_RATIO,
            xs.last(),
            lines.last().baselinePt + linePt * (1f - ExactLayout.BASELINE_RATIO)
        )
        return ExactTableRow(cells, box, lines.maxOf { it.sizePt }, linePt)
    }
}
