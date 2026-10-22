package com.skeler.scanely.core.text

/**
 * Repairs the grid a recognised table arrives as, so offline tables export in the same shape the
 * server's do. A port of the Worker's `repairGrid`; the two must stay in step, or a scan changes
 * shape depending on which engine read it.
 *
 * A recognised table is a guess at structure drawn in ink, so it carries artefacts no spreadsheet
 * would: ragged widths, blank rows where a rule read as content, blank edge columns, and headings
 * segmented finer than the data. Each pass repairs one and is a no-op otherwise.
 *
 * Invariant across every pass: no cell text is dropped and no value moves between columns.
 */
object TableGrid {

    fun repair(grid: List<List<String>>): List<List<String>> {
        val squared = squareOff(grid)
        return foldStrandedHeadings(trimBlankEdgeColumns(dropBlankRows(squared)))
    }

    /** Ragged rows padded to one width, so every later pass can address a column by index. */
    private fun squareOff(grid: List<List<String>>): List<List<String>> {
        val width = grid.maxOfOrNull { it.size } ?: 0
        return grid.map { row -> List(width) { column -> row.getOrElse(column) { "" } } }
    }

    /**
     * A blank row has no spelling in a pipe table that survives the round trip: an exporter
     * reads one as the boundary between two tables, which would split a statement in half at
     * a ruled line.
     */
    private fun dropBlankRows(grid: List<List<String>>): List<List<String>> =
        grid.filter { row -> row.any { it.isNotBlank() } }.ifEmpty { grid }

    /**
     * Blank columns at the edges go the way a spreadsheet's used range ignores them: they are
     * the margin of the recognised region, not part of the table. Interior blank columns are
     * the document's own shape and are left alone here.
     */
    private fun trimBlankEdgeColumns(grid: List<List<String>>): List<List<String>> {
        val width = grid.firstOrNull()?.size ?: return grid
        fun blank(column: Int) = grid.all { row -> row.getOrElse(column) { "" }.isBlank() }

        var first = 0
        var last = width - 1
        while (first <= last && blank(first)) first++
        while (last > first && blank(last)) last--
        if (first > last) return grid

        return grid.map { row -> row.subList(first, last + 1).toList() }
    }

    /**
     * Folds a heading sitting over an empty column into the neighbour that holds the data.
     *
     * Headings are often printed over a band the rules never divide — money columns on a statement,
     * a grouped "Unit / Price / Amount" on an invoice — so the recogniser can emit more heading
     * cells than the rows ever fill, stranding a labelled column empty.
     *
     * Deliberately narrow: only a blank column whose heading sits directly between two columns that
     * both hold data. A genuinely empty column (unused "Notes", untouched timesheet days) is at an
     * edge or beside another empty one, so it survives. Both headings are kept, because which owns
     * the values is a question the grid cannot answer.
     */
    private fun foldStrandedHeadings(grid: List<List<String>>): List<List<String>> {
        val header = grid.firstOrNull() ?: return grid
        val body = grid.drop(1)
        if (body.isEmpty()) return grid

        val hasData = header.indices.map { column ->
            body.any { row -> row.getOrElse(column) { "" }.isNotBlank() }
        }
        val stranded = header.indices.map { column ->
            !hasData[column] &&
                header[column].isNotBlank() &&
                hasData.getOrElse(column - 1) { false } &&
                hasData.getOrElse(column + 1) { false }
        }
        if (stranded.none { it }) return grid

        val headings = header.toMutableList()
        for (column in headings.indices) {
            if (!stranded[column]) continue
            // Into the following column: a heading printed over a band sits at or before the
            // values it covers, and that column is the nearest one holding any.
            val orphan = headings[column].trim()
            val existing = headings[column + 1].trim()
            headings[column + 1] =
                if (existing.contains(orphan)) existing else "$orphan $existing".trim()
        }

        val columns = headings.indices.filterNot { stranded[it] }
        return (listOf(headings) + body).map { row ->
            columns.map { column -> row.getOrElse(column) { "" } }
        }
    }
}
