package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.max

/** A column of a possible table: its extent across the page. */
internal class Column(var left: Float, var right: Float) {
    /** The widest cell seen in this column so far, for the section-label width limit. */
    var width = right - left
}

/** [slots] holds, per row and column, the cells that landed there (usually one or none). */
internal class Grid(val columns: List<Column>, val slots: List<List<List<LayoutCell>>>)

internal fun List<LayoutCell>.text(): String = joinToString(" ") { it.text }

/** [rows] as a grid of columns. */
internal fun grid(rows: List<LayoutLine>): Grid {
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
    val slots = rows.map { row ->
        val cells = List(columns.size) { mutableListOf<LayoutCell>() }
        row.cells.forEach { cell ->
            val column = columns.indices.minByOrNull { distance(columns[it], cell.box) } ?: 0
            cells[column] += cell
        }
        cells
    }
    return Grid(columns, slots)
}

private fun distance(column: Column, box: LayoutBox): Float = when {
    box.right < column.left -> column.left - box.right
    box.left > column.right -> box.left - column.right
    else -> 0f
}

/** [next] carries on the text of [line]: a word broken at the line end, or a lower-case start. */
internal fun carriesOn(line: String, next: String): Boolean =
    line.trimEnd().lastOrNull() in LINE_END_HYPHENS || next.first().isLowerCase()

private val LINE_END_HYPHENS = setOf('-', '­', '‐')
