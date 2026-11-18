package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox

/**
 * Tables from a page's ruling lines ([ExactRules]): the rules give the grid, the text fills it.
 * Where a stretch between two rules holds several lines, [ExactTableGrid.rows] says which are rows
 * and which are a cell's wrapped text. Cells merge where the rule between them is missing.
 */
@Suppress("TooManyFunctions")
internal object ExactRuledTables {

    /** A cell's text continues into the row below when its next line is this close, in line heights. */
    private const val STRADDLE_LINES = 1.5f

    /** Rows below the first that are one box across the grid: this many make the grid a form. */
    private const val MIN_FORM_BOXES = 3

    /** A grid is a table when at least this many of its cells hold text. */
    private const val MIN_CELLS = 2
    private const val HALF = 0.5f

    class Found(val table: ExactTable, val segments: Set<Segment>)

    private class Draft(val lines: List<GridLine>, val groups: List<IntRange>, val band: Int, val box: LayoutBox)

    /** The table [grid] holds, with the segments it took from [segments]; null when it holds no table. */
    fun table(grid: RuledGrid, segments: List<Segment>, pageHeightPt: Float): Found? {
        val xs = grid.xs
        val ys = grid.ys
        val inside = segments.filter { segment ->
            val middle = pageHeightPt - segment.baselineV - segment.sizePt * MIDDLE_EM
            val centre = (segment.u0 + segment.u1) / 2f
            middle > ys.first() && middle < ys.last() && centre > xs.first() && centre < xs.last()
        }
        if (inside.isEmpty()) return null
        val under = openBody(grid, segments, pageHeightPt)
        val lines = ExactTableGrid.lines(inside, xs, pageHeightPt)
        val drafts = ArrayList<Draft>()
        for (band in 0 until ys.size - 1) drafts += bandDrafts(grid, lines, band)
        if (under.isNotEmpty()) drafts += openDrafts(grid, under, pageHeightPt)
        val rows = drafts.map { ExactTableGrid.row(it.lines, it.groups, xs, it.box) }
        if (rows.sumOf { row -> row.cells.count { it.lines.isNotEmpty() } } < MIN_CELLS) return null
        val merged = mergeDown(grid, drafts, rows)
        if (isForm(merged, xs.size - 1)) return null
        val widths = xs.zipWithNext { a, b -> b - a }
        val box = LayoutBox(xs.first(), merged.first().box.top, xs.last(), merged.last().box.bottom)
        val all = inside + under
        val rtl = all.count { it.rtl } > all.size * HALF
        val table = ExactTable(merged, widths, box, all.map { it.sizePt }.sorted()[all.size / 2], rtl)
        return Found(table, all.toSet())
    }

    /**
     * A form, not a table: below its first row, several rows are a single labelled box across at least
     * half the grid (W-9's "Name of entity", "Address" boxes over the digit boxes of a tax number). A
     * data table's rows keep its columns; at most its header spans them. Forms stay positioned text.
     */
    private fun isForm(rows: List<ExactTableRow>, columns: Int): Boolean {
        if (columns < 2) return false
        val boxes = rows.drop(1).count { row ->
            row.cells.any { it.span > 1 && it.span * 2 >= columns && it.text.isNotBlank() }
        }
        return boxes >= MIN_FORM_BOXES
    }

    /** The rows under the grid's last rule: columns as the last ruled band has them. */
    private fun openDrafts(grid: RuledGrid, under: List<Segment>, pageHeightPt: Float): List<Draft> {
        val xs = grid.xs
        val groups = (0 until xs.size - 1).map { it..it }
        val body = ExactTableGrid.lines(under, xs, pageHeightPt)
        val box = LayoutBox(xs.first(), grid.ys.last(), xs.last(), body.last().baselinePt)
        return ExactTableGrid.rows(body, enclosed = false).map { Draft(it, groups, grid.ys.size - 1, box) }
    }

    /** The rows of one band between two horizontal grid lines. */
    private fun bandDrafts(grid: RuledGrid, lines: List<GridLine>, band: Int): List<Draft> {
        val xs = grid.xs
        val top = grid.ys[band]
        val bottom = grid.ys[band + 1]
        val bandLines = lines.filter {
            it.baselinePt - it.sizePt * MIDDLE_EM > top && it.baselinePt - it.sizePt * MIDDLE_EM <= bottom
        }
        val groups = groups(grid, band)
        val box = LayoutBox(xs.first(), top, xs.last(), bottom)
        val rows = if (closed(grid, band)) listOf(bandLines) else ExactTableGrid.rows(bandLines, enclosed = true)
        if (rows.isNotEmpty() && bandLines.isNotEmpty()) return rows.map { Draft(it, groups, band, box) }
        // A band with no text is a row of its own only where the rules show one (a spacer row).
        val spacer = grid.hasVerticals && bottom - top > ExactRules.SNAP_PT
        return if (spacer) listOf(Draft(emptyList(), groups, band, box)) else emptyList()
    }

    /**
     * The text under the grid's last rule that is still the table: a table ruled only around its
     * header runs on below it, to the foot of the page when it continues on the next one. Lines
     * are taken while they keep to the columns (none runs across a column edge) and follow closely;
     * what is left at the end in a single column with no row after it is a note, not a row.
     */
    private fun openBody(grid: RuledGrid, segments: List<Segment>, pageHeightPt: Float): List<Segment> {
        val xs = grid.xs
        if (grid.hasVerticals || xs.size < MIN_OPEN_EDGES) return emptyList()
        val below = segments.filter { pageHeightPt - it.baselineV - it.sizePt * MIDDLE_EM >= grid.ys.last() }
            .groupBy { it.baselineV }.toSortedMap(compareByDescending { it }).values.toList()
        val taken = ArrayList<List<Segment>>()
        var last = grid.ys.last()
        for (line in below) {
            val baseline = pageHeightPt - line.first().baselineV
            val size = line.maxOf { it.sizePt }
            if (!keepsToColumns(line, xs) || baseline - last > size * OPEN_ROW_GAP_EM) break
            taken += line
            last = baseline
        }
        while (taken.isNotEmpty() && columnsOf(taken.last(), xs) < 2) taken.removeAt(taken.lastIndex)
        return taken.flatten()
    }

    private fun keepsToColumns(line: List<Segment>, xs: List<Float>): Boolean = line.all { segment ->
        segment.u0 > xs.first() - ExactRules.SNAP_PT && segment.u1 < xs.last() + ExactRules.SNAP_PT &&
            xs.subList(1, xs.size - 1).none { ExactRules.runsAcross(segment, it) }
    }

    private fun columnsOf(line: List<Segment>, xs: List<Float>): Int =
        line.flatMap { it.tokens }.map { ExactTableGrid.column((it.u0 + it.u1) / 2f, xs) }.distinct().size

    /** As [LayoutTables]' row gap: a line further below than this starts something else. */
    private const val OPEN_ROW_GAP_EM = 2.5f

    /** An open body needs columns to keep to: at least one inner column edge. */
    private const val MIN_OPEN_EDGES = 3

    /**
     * The band is a single row: vertical rules run through it and horizontal rules close it above and
     * below.
     */
    private fun closed(grid: RuledGrid, band: Int): Boolean {
        if (!grid.hasVerticals) return false
        val columns = grid.xs.size - 1
        val ruled = (0 until columns).count { column ->
            val x = (grid.xs[column] + grid.xs[column + 1]) / 2f
            grid.horizontalAt(grid.ys[band], x) && grid.horizontalAt(grid.ys[band + 1], x)
        }
        return ruled >= columns * HALF
    }

    /**
     * The cells of a row in [band] as column ranges. Two columns are separate cells when a vertical
     * rule divides them in the band, or when the horizontal rule above them breaks there; where no
     * rule runs above them the rule below decides, and with neither they are separate.
     */
    private fun groups(grid: RuledGrid, band: Int): List<IntRange> {
        val top = grid.ys[band]
        val bottom = grid.ys[band + 1]
        val groups = ArrayList<IntRange>()
        var start = 0
        for (edge in 1 until grid.xs.size - 1) {
            val x = grid.xs[edge]
            val divided = when {
                grid.verticalAt(x, (top + bottom) / 2f) -> true
                grid.horizontalAt(top, x) -> grid.breakAt(top, x)
                grid.horizontalAt(bottom, x) -> grid.breakAt(bottom, x)
                else -> true
            }
            if (divided) {
                groups += start until edge
                start = edge
            }
        }
        groups += start until grid.xs.size - 1
        return groups
    }

    /**
     * Cells merged with the cell above: the same columns in the next band with no rule between. In a
     * grid with vertical rules a missing rule is a merged cell, unless the cell's side rules end
     * there; in a table ruled only across, it is one when the text runs on over the missing rule
     * (a header beside a two-level header).
     */
    private fun mergeDown(grid: RuledGrid, drafts: List<Draft>, rows: List<ExactTableRow>): List<ExactTableRow> {
        val merge = Merge(grid, rows)
        for (r in 0 until rows.size - 1) {
            val upper = drafts[r]
            val lower = drafts[r + 1]
            if (lower.band == upper.band) continue
            upper.groups.forEachIndexed { c, group ->
                val below = lower.groups.indexOf(group)
                if (below >= 0) merge.join(r, c, below, group, grid.ys[lower.band])
            }
        }
        return rows.mapIndexed { r, row -> ExactTableRow(merge.cells[r], row.box, row.fontSize, row.linePt) }
    }

    private class Merge(val grid: RuledGrid, val rows: List<ExactTableRow>) {
        val cells = rows.map { it.cells.toMutableList() }

        /** The cell that holds a merged cell's text: (row, index in that row). */
        private val owners = HashMap<Pair<Int, Int>, Pair<Int, Int>>()

        /** Cell [c] of row [r] continues into cell [below] of the next row, unless a rule at [y] separates them. */
        fun join(r: Int, c: Int, below: Int, group: IntRange, y: Float) {
            val x = (grid.xs[group.first] + grid.xs[group.last + 1]) / 2f
            // Side borders drawn cell by cell end where the two cells meet: two cells, no rule between.
            val sides = grid.verticalBreakAt(grid.xs[group.first], y) ||
                grid.verticalBreakAt(grid.xs[group.last + 1], y)
            if (grid.horizontalAt(y, x) || sides) return
            val (ownerRow, ownerCell) = owners[r to c] ?: (r to c)
            val above = cells[ownerRow][ownerCell]
            val under = cells[r + 1][below]
            if (!grid.hasVerticals && !straddles(rows, ownerRow, above, r + 1, under)) return
            val shift = rows[r + 1].firstBaselinePt - rows[ownerRow].firstBaselinePt
            val lines = above.lines + under.lines.map { ExactCellLine(it.segments, it.dropPt + shift) }
            cells[ownerRow][ownerCell] =
                ExactTableCell(lines.flatMap { it.segments }, lines, above.span, CellMerge.RESTART)
            cells[r + 1][below] = ExactTableCell(emptyList(), emptyList(), under.span, CellMerge.CONTINUE)
            owners[(r + 1) to below] = ownerRow to ownerCell
        }
    }

    private fun straddles(
        rows: List<ExactTableRow>,
        upperRow: Int,
        upper: ExactTableCell,
        lowerRow: Int,
        lower: ExactTableCell
    ): Boolean {
        val last = upper.lines.lastOrNull() ?: return false
        val next = lower.lines.firstOrNull() ?: return false
        val gap = rows[lowerRow].firstBaselinePt + next.dropPt - (rows[upperRow].firstBaselinePt + last.dropPt)
        return gap <= rows[lowerRow].linePt * STRADDLE_LINES
    }

    /** A line's middle is about this far above its baseline, in text sizes. */
    private const val MIDDLE_EM = 0.3f
}
