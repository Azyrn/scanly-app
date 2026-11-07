package com.skeler.scanely.core.ocr.paddle

import com.skeler.scanely.core.text.Markdown
import com.skeler.scanely.core.text.TableGrid
import kotlin.math.abs

private const val MIN_TABLE_CELLS = 4
private const val ROW_BAND = 0.6f
private const val COLUMN_SLACK = 4f

/** The pipe tables [DocumentStructure] writes for a TABLE region. */
internal object StructureTables {

    /** SLANet's [table] filled with the recognized [lines] that fall in its cells. */
    fun fromCells(
        table: TableStructure,
        lines: List<PositionedLine>
    ): String? {
        if (table.rows == 0 || table.columns == 0) return null
        val cells = table.cells.filter { it.row < table.rows && it.column < table.columns }
        if (cells.isEmpty()) return null

        val buckets = bucketLines(cells, lines)

        val grid = MutableList(table.rows) { MutableList(table.columns) { "" } }
        for (cell in cells) {
            // Only the cell's own slot, unlike the server's row spans which repeat downwards.
            // SLANet's spans are guesses — it will put rowspan="20" on a five-row table — and
            // repeating a cell on the strength of one copies a misreading into every row it
            // claims. A blank the user can see is recoverable; invented rows are not.
            grid[cell.row][cell.column] = buckets.getValue(cell)
                .joinToString(" ") { it.text.trim() }
                .trim()
        }

        if (grid.all { row -> row.all { it.isBlank() } }) return null
        return Markdown.table(TableGrid.repair(grid)).trimEnd()
    }

    /** Each line assigned to the cell holding its centre, or else to the nearest cell. */
    private fun bucketLines(
        cells: List<TableCell>,
        lines: List<PositionedLine>
    ): Map<TableCell, List<PositionedLine>> {
        val buckets = cells.associateWith { mutableListOf<PositionedLine>() }
        val remaining = lines.toMutableList()

        // Cell boxes and lines are both in page space.
        for (cell in cells) {
            val matched = remaining.filter { line ->
                val cx = (line.quad.minX + line.quad.maxX) / 2f
                val cy = (line.quad.minY + line.quad.maxY) / 2f
                cx in cell.left..cell.right && cy in cell.top..cell.bottom
            }
            remaining.removeAll(matched)
            buckets.getValue(cell).addAll(matched.sortedBy { it.quad.minX })
        }

        // SLANet's boxes are tight: a line whose centre just misses every box must not
        // vanish from the document, so it joins the nearest cell instead.
        for (line in remaining.sortedWith(compareBy({ it.quad.minY }, { it.quad.minX }))) {
            val cx = (line.quad.minX + line.quad.maxX) / 2f
            val cy = (line.quad.minY + line.quad.maxY) / 2f
            val nearest = cells.minBy { cell ->
                val dx = maxOf(cell.left - cx, cx - cell.right, 0f)
                val dy = maxOf(cell.top - cy, cy - cell.bottom, 0f)
                dx * dx + dy * dy
            }
            buckets.getValue(nearest).add(line)
        }
        return buckets
    }

    /**
     * A pipe table recovered from the cell boxes alone, for when SLANet is not installed.
     * It cannot see merged cells the way SLANet does, but a plain grid is far closer to the
     * page than the flat run of lines this used to degrade to.
     */
    fun fromGeometry(lines: List<PositionedLine>): String? {
        if (lines.size < MIN_TABLE_CELLS) return null

        val rows = groupRows(lines)
        val columns = columnBounds(lines)
        if (rows.size < 2 || columns.size < 2) return null

        val grid = rows.map { row ->
            val cells = MutableList(columns.size) { "" }
            for (line in row) {
                val center = (line.quad.minX + line.quad.maxX) / 2f
                val index = columns.indices.minBy { i ->
                    val (left, right) = columns[i]
                    maxOf(left - center, center - right, 0f)
                }
                cells[index] = "${cells[index]} ${line.text.trim()}".trim()
            }
            cells
        }

        if (grid.all { row -> row.count { it.isNotBlank() } < 2 }) return null
        return Markdown.table(TableGrid.repair(grid)).trimEnd()
    }

    /**
     * Column x-ranges, from every cell box projected onto the x axis and overlapping
     * projections merged. Columns in a real table are separated by whitespace, so the
     * merged runs are the columns.
     */
    private fun columnBounds(lines: List<PositionedLine>): List<Pair<Float, Float>> {
        val spans = lines.map { it.quad.minX to it.quad.maxX }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        for ((left, right) in spans) {
            val last = merged.lastOrNull()
            if (last != null && left <= last.second + COLUMN_SLACK) {
                merged[merged.size - 1] = last.first to maxOf(last.second, right)
            } else {
                merged.add(left to right)
            }
        }
        return merged
    }
}

/** Lines banded into table rows by vertical overlap with the band so far. */
internal fun groupRows(lines: List<PositionedLine>): List<List<PositionedLine>> {
    val rows = mutableListOf<MutableList<PositionedLine>>()
    for (line in lines.sortedBy { it.quad.minY }) {
        val center = (line.quad.minY + line.quad.maxY) / 2f
        val row = rows.lastOrNull()?.takeIf { current ->
            val ref = current.first().quad
            val refCenter = (ref.minY + ref.maxY) / 2f
            val height = maxOf(line.quad.maxY - line.quad.minY, ref.maxY - ref.minY)
            abs(center - refCenter) < height * ROW_BAND
        }
        if (row == null) rows.add(mutableListOf(line)) else row.add(line)
    }
    return rows.map { row -> row.sortedBy { it.quad.minX } }
}
