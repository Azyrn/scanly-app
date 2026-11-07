package com.skeler.scanely.core.ocr.paddle

/**
 * Decodes SLANet's per-step outputs into a grid.
 *
 * The vocabulary is HTML-ish: `<tr>`, `<td></td>`, or `<td` followed by span attributes
 * and `>`. Cell boxes are emitted on the step that opens the cell.
 */
object TableDecoder {

    fun decode(
        structure: FloatArray,
        boxes: FloatArray,
        steps: Int,
        classes: Int,
        vocab: List<String>,
        scale: Float
    ): TableStructure = SlanetGrid(structure, boxes, steps, classes, vocab, scale).decode()

    const val SOS = "sos"
    const val EOS = "eos"

    /** PaddleOCR wraps the file dictionary in sos/eos before decoding. */
    fun vocabulary(dictionary: List<String>): List<String> = buildList {
        add(SOS)
        addAll(dictionary)
        add(EOS)
    }
}

private const val SPAN_PREFIX_COL = " colspan=\""
private const val SPAN_PREFIX_ROW = " rowspan=\""
private const val MAX_SPAN = 100
private const val BOX_COORDS = 8

/** One decoding pass over SLANet's outputs, building the grid cell by cell. */
private class SlanetGrid(
    private val structure: FloatArray,
    private val boxes: FloatArray,
    private val steps: Int,
    private val classes: Int,
    private val vocab: List<String>,
    private val scale: Float
) {
    private val cells = mutableListOf<TableCell>()

    // Grid slots already taken by an earlier cell's row/col span.
    private val occupied = mutableSetOf<Long>()
    private var row = -1
    private var column = 0
    private var ended = false
    private var colSpan = 1
    private var rowSpan = 1

    private fun token(step: Int): String? = vocab.getOrNull(argmax(structure, step * classes, classes))

    fun decode(): TableStructure {
        var step = 0
        while (step < steps && !ended) {
            val token = token(step)
            if (token == null || token == TableDecoder.EOS) break

            when (token) {
                "<tr>" -> {
                    row++
                    column = 0
                }
                "<td></td>", "<td" -> step = openCell(step, token)
            }
            step++
        }

        if (cells.isEmpty()) return TableStructure(0, 0, emptyList())

        // `<tr>` count is authoritative: a misread span (the model can emit rowspan="20" on a
        // 5-row table) would otherwise stretch the grid into dozens of empty rows.
        val rows = maxOf(row + 1, cells.maxOf { it.row + 1 })
        val clamped = cells.map { cell ->
            cell.copy(rowSpan = cell.rowSpan.coerceAtMost(rows - cell.row))
        }
        val columns = clamped.maxOf { it.column + it.colSpan }
        return TableStructure(rows, columns, clamped)
    }

    /** Adds the cell [token] opens at [step]; returns the last step its attributes used. */
    private fun openCell(step: Int, token: String): Int {
        if (row < 0) row = 0
        colSpan = 1
        rowSpan = 1
        // The box belongs to the step that opened the cell, not to its attributes.
        val next = if (token == "<td") readAttributes(step) else step

        while (occupied.contains(slot(row, column))) column++

        val box = FloatArray(BOX_COORDS) { boxes[step * BOX_COORDS + it] }
        val xs = (0 until BOX_COORDS step 2).map { box[it] * scale }
        val ys = (1 until BOX_COORDS step 2).map { box[it] * scale }

        cells.add(
            TableCell(
                row = row,
                column = column,
                rowSpan = rowSpan,
                colSpan = colSpan,
                left = xs.min(),
                top = ys.min(),
                right = xs.max(),
                bottom = ys.max()
            )
        )

        for (r in row until row + rowSpan) {
            for (c in column until column + colSpan) occupied.add(slot(r, c))
        }
        column += colSpan
        return next
    }

    /**
     * Attributes trail the opening token until '>' closes it. The model sometimes drops the '>',
     * so anything that isn't a span attribute ends the cell and is re-read as structure on the
     * next pass. Returns the step the attributes end on.
     */
    private fun readAttributes(step: Int): Int {
        var next = step + 1
        var reading = true
        while (reading && next < steps) {
            val attr = token(next)
            when {
                attr == null || attr == TableDecoder.EOS -> {
                    ended = true
                    reading = false
                }
                attr == ">" -> reading = false
                applySpan(attr) -> next++
                else -> {
                    next--
                    reading = false
                }
            }
        }
        return next
    }

    /** Takes the colspan or rowspan [attr] sets; false when it sets neither. */
    private fun applySpan(attr: String): Boolean {
        val col = spanOf(attr, SPAN_PREFIX_COL)
        val rsp = spanOf(attr, SPAN_PREFIX_ROW)
        if (col != null) colSpan = col
        if (rsp != null) rowSpan = rsp
        return col != null || rsp != null
    }
}

private fun spanOf(token: String, prefix: String): Int? =
    token.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.removeSuffix("\"")
        ?.toIntOrNull()
        ?.takeIf { it in 2..MAX_SPAN }

private fun slot(row: Int, column: Int): Long = row.toLong() shl 32 or column.toLong()

private fun argmax(values: FloatArray, base: Int, classes: Int): Int {
    var best = 0
    var bestScore = values[base]
    for (c in 1 until classes) {
        val v = values[base + c]
        if (v > bestScore) {
            bestScore = v
            best = c
        }
    }
    return best
}
