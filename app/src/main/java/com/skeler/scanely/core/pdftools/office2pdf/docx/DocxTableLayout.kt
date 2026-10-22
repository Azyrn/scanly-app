package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import kotlin.math.max
import kotlin.math.min

/** Something drawn whole inside a table cell: a paragraph or a nested table. */
internal interface CellItem {
    val height: Float

    fun draw(canvas: Canvas, left: Float, top: Float)
}

internal class CellPlan(
    val cell: DocxCell,
    val column: Int,
    val left: Float,
    val right: Float,
    val items: List<CellItem>
)

internal class RowPlan(val height: Float, val cells: List<CellPlan>)

/**
 * Tables, one row at a time: a row is measured only when it is about to be placed, so a table of
 * thousands of rows never has more than one row of layouts alive. A vertically merged cell is
 * drawn as one shaded, unruled block; its content sits in its first row, which grows to fit it.
 */
internal class TableLayout(private val paragraphs: ParagraphFactory) {
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = Color.BLACK
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }

    /** Column edges from the table's grid, filled in where the grid is missing and scaled to fit. */
    fun columns(table: DocxTable, width: Float): FloatArray {
        val used = table.rows.maxOfOrNull { row -> row.cells.sumOf { it.span } } ?: 0
        val count = max(table.columns.size, used).coerceIn(1, MAX_COLUMNS)
        val widths = FloatArray(count) { (table.columns.getOrNull(it) ?: 0) / TWIPS_PER_POINT }
        val missing = widths.count { it <= 0f }
        if (missing > 0) {
            val share = ((width - widths.sum()) / missing).coerceAtLeast(MIN_COLUMN)
            for (index in widths.indices) if (widths[index] <= 0f) widths[index] = share
        }
        val scale = min(1f, width / widths.sum())
        val edges = FloatArray(count + 1)
        for (index in 0 until count) edges[index + 1] = edges[index] + widths[index] * scale
        return edges
    }

    fun row(row: DocxRow, edges: FloatArray, maxImageHeight: Float): RowPlan {
        var column = 0
        val last = edges.size - 1
        val cells = row.cells.mapNotNull { cell ->
            val start = column
            column += cell.span
            if (start >= last) return@mapNotNull null
            val end = min(start + cell.span, last)
            val inner = (edges[end] - edges[start] - 2 * CELL_PADDING).coerceAtLeast(MIN_INNER)
            val items = if (cell.merge == VerticalMerge.CONTINUE) {
                emptyList()
            } else {
                measure(cell.blocks, inner, maxImageHeight)
            }
            CellPlan(cell, start, edges[start], edges[end], items)
        }
        val content = cells.maxOfOrNull { cell -> cell.items.fold(0f) { sum, item -> sum + item.height } } ?: 0f
        val declared = row.heightTwips / TWIPS_PER_POINT
        val height = if (row.exactHeight && declared > 0f) {
            declared
        } else {
            max(content + 2 * CELL_PADDING_V, declared)
        }
        return RowPlan(height.coerceAtLeast(MIN_ROW), cells)
    }

    /**
     * Draws row [index] of [table] with its top at [top]; nothing below [clipBottom] is drawn.
     * [fills] carries each merged column's shading down from its first row.
     */
    fun drawRow(
        canvas: Canvas,
        table: DocxTable,
        index: Int,
        plan: RowPlan,
        origin: RowOrigin,
        fills: MutableMap<Int, Int?>
    ) {
        val bottom = min(origin.top + plan.height, origin.clipBottom)
        for (cellPlan in plan.cells) {
            val cell = cellPlan.cell
            val rect = RectF(origin.left + cellPlan.left, origin.top, origin.left + cellPlan.right, bottom)
            val continued = cell.merge == VerticalMerge.CONTINUE
            if (!continued) fills[cellPlan.column] = cell.fill
            fills[cellPlan.column]?.let { fill ->
                fillPaint.color = fill or OPAQUE
                canvas.drawRect(rect, fillPaint)
            }
            drawItems(canvas, rect, cellPlan.items)
            if (cell.bordered) drawBorders(canvas, rect, !continued, !continuesBelow(table, index, cellPlan.column))
        }
    }

    private fun drawItems(canvas: Canvas, rect: RectF, items: List<CellItem>) {
        if (items.isEmpty()) return
        canvas.withClip(rect) {
            var y = rect.top + CELL_PADDING_V
            for (item in items) {
                item.draw(canvas, rect.left + CELL_PADDING, y)
                y += item.height
            }
        }
    }

    private fun drawBorders(canvas: Canvas, rect: RectF, top: Boolean, bottom: Boolean) {
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, borderPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, borderPaint)
        if (top) canvas.drawLine(rect.left, rect.top, rect.right, rect.top, borderPaint)
        if (bottom) canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, borderPaint)
    }

    private fun continuesBelow(table: DocxTable, index: Int, column: Int): Boolean {
        val next = table.rows.getOrNull(index + 1) ?: return false
        var start = 0
        for (cell in next.cells) {
            if (start == column) return cell.merge == VerticalMerge.CONTINUE
            start += cell.span
            if (start > column) break
        }
        return false
    }

    /**
     * A cell's blocks. Contextual spacing ("don't add space between paragraphs of the same
     * style") is applied here as in the page flow; page breaks mean nothing inside a cell.
     */
    fun measure(blocks: List<DocxBlock>, width: Float, maxImageHeight: Float): List<CellItem> {
        val items = ArrayList<CellItem>()
        var previous: Pair<ParagraphStyle, ParagraphItem>? = null
        for (block in blocks) {
            when (block) {
                is DocxParagraph -> {
                    val item = paragraphItem(block, previous, width, maxImageHeight)
                    items += item
                    previous = block.style to item
                }
                is DocxTable -> {
                    items += NestedTable(block, width, maxImageHeight)
                    previous = null
                }
                DocxPageBreak -> Unit
            }
        }
        return items
    }

    private fun paragraphItem(
        paragraph: DocxParagraph,
        previous: Pair<ParagraphStyle, ParagraphItem>?,
        width: Float,
        maxImageHeight: Float
    ): ParagraphItem {
        val plans = paragraphs.build(paragraph, width, maxImageHeight)
        val style = paragraph.style
        val joined = previous != null && previous.first.styleId == style.styleId
        if (previous != null && joined && previous.first.contextualSpacing) previous.second.after = 0f
        val before = if (joined && style.contextualSpacing) 0f else plans.first().spaceBefore
        return ParagraphItem(plans, before, plans.last().spaceAfter)
    }

    private class ParagraphItem(
        private val plans: List<ParagraphPlan>,
        private val before: Float,
        var after: Float
    ) : CellItem {
        private val text = plans.fold(0f) { sum, plan -> sum + plan.textHeight }

        override val height: Float get() = before + text + after

        override fun draw(canvas: Canvas, left: Float, top: Float) {
            var y = top + before
            for (plan in plans) {
                plan.draw(canvas, left, y)
                y += plan.textHeight
            }
        }
    }

    /** A table inside a cell is small by nature, so it is measured and drawn whole. */
    private inner class NestedTable(
        private val table: DocxTable,
        width: Float,
        maxImageHeight: Float
    ) : CellItem {
        private val edges = columns(table, width)
        private val rows = table.rows.map { row(it, edges, maxImageHeight) }

        override val height: Float = rows.fold(0f) { sum, row -> sum + row.height }

        override fun draw(canvas: Canvas, left: Float, top: Float) {
            val fills = HashMap<Int, Int?>()
            var y = top
            rows.forEachIndexed { index, plan ->
                drawRow(canvas, table, index, plan, RowOrigin(left, y, Float.MAX_VALUE), fills)
                y += plan.height
            }
        }
    }

    private companion object {
        const val MAX_COLUMNS = 256
        const val MIN_COLUMN = 18f
        const val MIN_INNER = 6f
        const val MIN_ROW = 4f

        /** Word's default cell margins: 0.075" left and right, none above and below. */
        const val CELL_PADDING = 5.4f
        const val CELL_PADDING_V = 0f
        const val BORDER_WIDTH = 0.5f
        const val OPAQUE = -0x1000000
    }
}

/** Where a row goes on the page: its left edge, its top, and the page's lowest drawable point. */
internal class RowOrigin(val left: Float, val top: Float, val clipBottom: Float)
