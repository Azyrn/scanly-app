package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withTranslation

/**
 * Draws laid-out sheet pages onto a PDF page canvas, in points. Order matters: gridlines first,
 * then fills and merged areas over them (a filled or merged cell hides its inner lines, as on
 * screen), then borders, then text on top.
 *
 * Text is drawn by [XlsxTextPainter], as text, not pixels, so the PDF stays searchable and selectable.
 */
internal class XlsxPagePainter {
    private val text = XlsxTextPainter()
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cellRect = RectF()

    /** Height in points that [cell]'s wrapped text needs at [widthPt]. */
    fun measureWrapped(cell: XlsxCell, widthPt: Float): Float = text.measureWrapped(cell, widthPt)

    fun drawHeader(canvas: Canvas, frame: PageFrame, title: String) = text.drawHeader(canvas, frame, title)

    fun draw(canvas: Canvas, layout: SheetLayout, slice: PageSlice) {
        val left = layout.colLefts[slice.colFrom]
        val top = layout.rowTops[slice.rowFrom]
        val bounds = RectF(left, top, layout.colLefts[slice.colTo], layout.rowTops[slice.rowTo])
        canvas.withTranslation(layout.frame.marginX, layout.frame.marginY) {
            canvas.scale(layout.scale, layout.scale)
            canvas.translate(-left, -top)
            canvas.clipRect(bounds)
            val rows = layout.firstRow + slice.rowFrom until layout.firstRow + slice.rowTo
            val cols = layout.firstCol + slice.colFrom until layout.firstCol + slice.colTo
            if (layout.sheet.showGridLines) drawGrid(canvas, layout, slice, bounds)
            drawFills(canvas, layout, rows, cols)
            val merges = layout.merges.filter { it.overlaps(rows, cols) }
            merges.forEach { drawMergeBackground(canvas, layout, it) }
            drawBorders(canvas, layout, rows, cols)
            drawTexts(canvas, layout, rows, cols, bounds)
            merges.forEach { drawMergeText(canvas, layout, it) }
        }
    }

    private fun drawGrid(canvas: Canvas, layout: SheetLayout, slice: PageSlice, bounds: RectF) {
        linePaint.color = GRID_COLOR
        linePaint.strokeWidth = GRID_WIDTH_PT
        for (i in slice.colFrom..slice.colTo) {
            val x = layout.colLefts[i]
            canvas.drawLine(x, bounds.top, x, bounds.bottom, linePaint)
        }
        for (i in slice.rowFrom..slice.rowTo) {
            val y = layout.rowTops[i]
            canvas.drawLine(bounds.left, y, bounds.right, y, linePaint)
        }
    }

    private fun drawFills(canvas: Canvas, layout: SheetLayout, rows: IntRange, cols: IntRange) {
        for (row in rows) {
            for (cell in layout.cellsIn(row)) {
                // Merged areas paint their own background.
                val fill = cell.style.fill
                if (fill != null && drawsAlone(layout, row, cell.col, cols)) {
                    fillPaint.color = fill
                    canvas.drawRect(rectOf(layout, row, cell.col, row, cell.col), fillPaint)
                }
            }
        }
    }

    private fun drawMergeBackground(canvas: Canvas, layout: SheetLayout, merge: CellRange) {
        fillPaint.color = anchorOf(layout, merge)?.style?.fill ?: WHITE
        canvas.drawRect(rectOf(layout, merge.firstRow, merge.firstCol, merge.lastRow, merge.lastCol), fillPaint)
    }

    private fun drawBorders(canvas: Canvas, layout: SheetLayout, rows: IntRange, cols: IntRange) {
        for (row in rows) {
            for (cell in layout.cellsIn(row)) {
                val borders = cell.style.borders
                if (cell.col !in cols || !borders.any) continue
                drawCellBorders(canvas, borders, rectOf(layout, row, cell.col, row, cell.col))
            }
        }
    }

    private fun drawCellBorders(canvas: Canvas, borders: Borders, r: RectF) {
        borders.left?.let { edge(canvas, it, r.left, r.top, r.left, r.bottom) }
        borders.right?.let { edge(canvas, it, r.right, r.top, r.right, r.bottom) }
        borders.top?.let { edge(canvas, it, r.left, r.top, r.right, r.top) }
        borders.bottom?.let { edge(canvas, it, r.left, r.bottom, r.right, r.bottom) }
    }

    private fun edge(canvas: Canvas, line: BorderLine, x1: Float, y1: Float, x2: Float, y2: Float) {
        linePaint.color = line.color
        linePaint.strokeWidth = line.widthPt
        canvas.drawLine(x1, y1, x2, y2, linePaint)
    }

    private fun drawTexts(canvas: Canvas, layout: SheetLayout, rows: IntRange, cols: IntRange, bounds: RectF) {
        for (row in rows) {
            val cells = layout.cellsIn(row)
            cells.forEachIndexed { index, cell ->
                if (cell.text.isEmpty() || !drawsAlone(layout, row, cell.col, cols)) return@forEachIndexed
                val rect = RectF(rectOf(layout, row, cell.col, row, cell.col))
                val clip = overflowClip(layout, cells, index, rect, bounds)
                text.drawCellText(canvas, cell, rect, clip)
            }
        }
    }

    private fun drawMergeText(canvas: Canvas, layout: SheetLayout, merge: CellRange) {
        val anchor = anchorOf(layout, merge)?.takeIf { it.text.isNotEmpty() } ?: return
        val rect = RectF(rectOf(layout, merge.firstRow, merge.firstCol, merge.lastRow, merge.lastCol))
        text.drawCellText(canvas, anchor, rect, rect)
    }

    private fun rectOf(layout: SheetLayout, row: Int, col: Int, lastRow: Int, lastCol: Int): RectF {
        cellRect.set(layout.xOf(col), layout.yOf(row), layout.xOf(lastCol + 1), layout.yOf(lastRow + 1))
        return cellRect
    }

    private companion object {
        const val GRID_COLOR = 0xFFD9D9D9.toInt()
        const val GRID_WIDTH_PT = 0.3f
    }
}

/** A cell on this page that isn't hidden under a merge or the anchor of one (merges draw themselves). */
private fun drawsAlone(layout: SheetLayout, row: Int, col: Int, cols: IntRange): Boolean =
    col in cols && !layout.isCovered(row, col) && layout.mergeAt(row, col) == null

private fun anchorOf(layout: SheetLayout, merge: CellRange): XlsxCell? =
    layout.cellsIn(merge.firstRow).firstOrNull { it.col == merge.firstCol }

private fun CellRange.overlaps(rows: IntRange, cols: IntRange): Boolean =
    firstRow <= rows.last && lastRow >= rows.first && firstCol <= cols.last && lastCol >= cols.first
