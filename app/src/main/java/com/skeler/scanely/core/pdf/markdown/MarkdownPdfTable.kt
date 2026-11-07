package com.skeler.scanely.core.pdf.markdown

import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MdBlock

/** A grid that repeats its header row on every page it spans; a row never splits. */
internal fun drawTable(pager: Pager, table: MdBlock.Table) {
    val columns = table.header.size
    if (columns == 0) return
    val widths = columnWidths(table)
    val fill = FILL_COLOR

    val header = cells(table.header, widths, bold = true)
    row(pager, header, widths, fill)
    table.rows.forEach { data ->
        val laid = cells(data, widths, bold = false)
        // A row never splits; carrying the header over keeps the grid readable.
        if (pager.needsBreak(height(laid))) {
            pager.finish()
            row(pager, header, widths, fill)
        }
        row(pager, laid, widths, fill = null)
    }
    pager.advance(BLOCK_GAP)
}

/**
 * Columns share the page in proportion to the text they hold, but never below the width of
 * their longest word.
 *
 * Both halves matter. Sizing on character counts treated `$1,200` and `mmmmmm` as equal, and
 * proportional sizing alone let a long neighbour squeeze a column under its own header —
 * which is how a four-column invoice broke `Amount` across two lines.
 */
private fun columnWidths(table: MdBlock.Table): List<Float> {
    val columns = table.header.size
    val desired = FloatArray(columns)
    val floor = FloatArray(columns)

    fun measure(row: List<String>, paint: TextPaint) {
        for (column in 0 until columns) {
            val text = MarkdownParser.plain(row.getOrElse(column) { "" })
            desired[column] = maxOf(desired[column], paint.measureText(text))
            floor[column] = maxOf(floor[column], longestWord(text, paint))
        }
    }

    measure(table.header, body(bold = true))
    val bodyPaint = body()
    table.rows.forEach { measure(it, bodyPaint) }

    val padding = 2 * CELL_PADDING
    val cap = CONTENT_WIDTH * MAX_COLUMN_FLOOR
    return distribute(
        desired.map { it + padding },
        floor.map { (it + padding).coerceAtMost(cap) }
    )
}

private fun longestWord(text: String, paint: TextPaint): Float =
    text.split(WHITESPACE).maxOfOrNull { paint.measureText(it) } ?: 0f

/**
 * Gives each column its proportional share, pins any that landed under its floor, and splits
 * what is left among the rest — repeatedly, since pinning one column shrinks the pool the
 * others draw from.
 */
private fun distribute(desired: List<Float>, floor: List<Float>): List<Float> {
    val widths = desired.toMutableList()
    val pinned = BooleanArray(desired.size)

    repeat(desired.size) {
        val taken = widths.filterIndexed { column, _ -> pinned[column] }.sum()
        val free = CONTENT_WIDTH - taken
        val flexible = desired.filterIndexed { column, _ -> !pinned[column] }.sum()
        if (flexible > 0f) {
            var pinnedAny = false
            desired.indices.filterNot { pinned[it] }.forEach { column ->
                val share = free * desired[column] / flexible
                if (share < floor[column]) {
                    widths[column] = floor[column]
                    pinned[column] = true
                    pinnedAny = true
                } else {
                    widths[column] = share
                }
            }
            if (!pinnedAny) return widths
        }
    }

    // Every column is pinned and they still overrun the page: no arrangement avoids a break,
    // so scale to fit and let the text wrap.
    val total = widths.sum()
    return if (total > CONTENT_WIDTH) widths.map { it * CONTENT_WIDTH / total } else widths
}

private fun cells(cells: List<String>, widths: List<Float>, bold: Boolean): List<StaticLayout> {
    val paint = body(bold = bold)
    return widths.mapIndexed { column, width ->
        layout(
            spans(cells.getOrElse(column) { "" }),
            paint,
            (width - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
        )
    }
}

private fun height(cells: List<StaticLayout>): Float =
    cells.maxOf { it.height }.toFloat() + 2 * CELL_PADDING

private fun row(pager: Pager, cells: List<StaticLayout>, widths: List<Float>, fill: Int?) {
    val height = height(cells)
    val canvas = pager.reserve(height)
    val top = pager.y
    val border = stroke(BORDER_COLOR)

    if (fill != null) {
        canvas.drawRect(
            MARGIN.toFloat(),
            top,
            MARGIN + widths.sum(),
            top + height,
            fill(fill)
        )
    }
    canvas.drawRect(MARGIN.toFloat(), top, MARGIN + widths.sum(), top + height, border)

    var x = MARGIN.toFloat()
    cells.forEachIndexed { column, cell ->
        if (column > 0) canvas.drawLine(x, top, x, top + height, border)
        canvas.withTranslation(x + CELL_PADDING, top + CELL_PADDING) { cell.draw(this) }
        x += widths[column]
    }
    pager.advance(height)
}
