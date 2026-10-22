package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.abs
import kotlin.math.max

/** Finds runs of lines that form a table, and lays their cells into a grid. */
internal object LayoutTables {

    private class Column(var left: Float, var right: Float)

    /**
     * Runs of consecutive multi-cell lines whose cells line up — by left edge, right edge (numbers
     * are usually right-aligned) or centre — with at least two shared columns and even row
     * spacing. A failed run is skipped as a whole, which keeps the scan linear.
     */
    fun findTables(lines: List<LayoutLine>): List<IntRange> {
        val tables = mutableListOf<IntRange>()
        var index = 0
        while (index < lines.size) {
            val end = if (lines[index].cells.size < 2) index else tableEnd(lines, index)
            if (end > index && looksTabular(lines.subList(index, end + 1))) tables += index..end
            index = end + 1
        }
        return tables
    }

    /** The last line of the aligned run starting at [start]. */
    private fun tableEnd(lines: List<LayoutLine>, start: Int): Int {
        val columns = lines[start].cells.map { Column(it.box.left, it.box.right) }.toMutableList()
        var end = start
        while (end + 1 < lines.size && end + 1 - start < MAX_TABLE_ROWS) {
            if (!extends(lines[end], lines[end + 1], columns)) break
            end++
        }
        return end
    }

    /** Whether [next] continues the run below [previous]; its new columns join [columns] if so. */
    private fun extends(previous: LayoutLine, next: LayoutLine, columns: MutableList<Column>): Boolean {
        if (next.cells.size < 2) return false
        val gap = next.box.top - previous.box.bottom
        if (gap > next.fontSize * ROW_GAP_EM) return false
        val tolerance = max(MIN_ALIGN_TOLERANCE_PT, next.fontSize * ALIGN_TOLERANCE_EM)
        val unmatched = next.cells.filter { cell -> columns.none { aligned(it, cell.box, tolerance) } }
        val fits = next.cells.size - unmatched.size >= 2 && columns.size + unmatched.size <= MAX_TABLE_COLUMNS
        if (fits) unmatched.forEach { columns += Column(it.box.left, it.box.right) }
        return fits
    }

    private fun aligned(column: Column, box: LayoutBox, tolerance: Float): Boolean =
        abs(column.left - box.left) <= tolerance ||
            abs(column.right - box.right) <= tolerance ||
            abs((column.left + column.right) / 2f - box.centerX) <= tolerance

    /**
     * Two-column prose also lines up in two "cells" per line; what gives it away is long cells.
     * Real tables hold short values, and their rows are evenly spaced.
     */
    private fun looksTabular(rows: List<LayoutLine>): Boolean {
        val cells = rows.flatMap { it.cells }
        if (cells.sumOf { it.wordCount }.toFloat() / cells.size > MAX_WORDS_PER_CELL) return false
        val pitches = rows.zipWithNext { a, b -> b.box.centerY - a.box.centerY }
        val smallest = pitches.minOrNull() ?: return false
        val largest = pitches.maxOrNull() ?: return false
        return largest <= smallest * PITCH_SPREAD + rows.first().fontSize
    }

    fun buildTable(rows: List<LayoutLine>): TableBlock {
        // Columns from every cell, merged where they overlap, so a wide header and the narrow
        // values under it land in one column.
        val spans = rows.flatMap { row -> row.cells.map { it.box } }.sortedBy { it.left }
        val columns = mutableListOf<Column>()
        for (span in spans) {
            val last = columns.lastOrNull()
            if (last != null && span.left <= last.right) {
                last.right = max(last.right, span.right)
            } else {
                columns += Column(span.left, span.right)
            }
        }
        val grid = rows.map { row ->
            val cells = MutableList(columns.size) { "" }
            row.cells.forEach { cell ->
                val column = columns.indices.minByOrNull { distance(columns[it], cell.box) } ?: 0
                cells[column] = if (cells[column].isEmpty()) cell.text else cells[column] + " " + cell.text
            }
            cells.toList()
        }
        return TableBlock(
            rows = grid,
            rowBoxes = rows.map { it.box },
            box = rows.map { it.box }.reduce { a, b -> a.union(b) },
            fontSize = rows.map { it.fontSize }.sorted()[rows.size / 2]
        )
    }

    private fun distance(column: Column, box: LayoutBox): Float = when {
        box.right < column.left -> column.left - box.right
        box.left > column.right -> box.left - column.right
        else -> 0f
    }

    private const val ROW_GAP_EM = 2.5f
    private const val MIN_ALIGN_TOLERANCE_PT = 3f
    private const val ALIGN_TOLERANCE_EM = 0.6f
    private const val MAX_TABLE_ROWS = 2_000
    private const val MAX_TABLE_COLUMNS = 40
    private const val MAX_WORDS_PER_CELL = 5f
    private const val PITCH_SPREAD = 2f
}
