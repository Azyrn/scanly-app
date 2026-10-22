package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation

/**
 * The text half of [XlsxPagePainter]: one reused paint for the sheet title and every cell's text,
 * so fonts are applied in one place.
 *
 * Text is drawn as text, not pixels, so the PDF stays searchable and selectable.
 */
internal class XlsxTextPainter {
    /**
     * Unhinted, unrounded glyph positions: with hinting on, Skia snaps each glyph to whole units
     * (worse under the page's scale) and the PDF's text came back from a reader as broken runs —
     * "C / ol 0 / 1" for "Col 01" — which is what copying text out of the PDF would give too.
     */
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val typefaces = HashMap<Int, Typeface>()

    /** Height in points that [cell]'s wrapped text needs at [widthPt]. */
    fun measureWrapped(cell: XlsxCell, widthPt: Float): Float {
        applyFont(cell.style.font)
        return wrapped(cell, widthPt).height.toFloat()
    }

    fun drawHeader(canvas: Canvas, frame: PageFrame, title: String) {
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = HEADER_SIZE_PT
        textPaint.color = HEADER_COLOR
        textPaint.typeface = Typeface.SANS_SERIF
        val text = TextUtils.ellipsize(title, textPaint, frame.printableWidth, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, frame.marginX, frame.marginY / 2, textPaint)
    }

    fun drawCellText(canvas: Canvas, cell: XlsxCell, rect: RectF, clip: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        applyFont(cell.style.font)
        canvas.withClip(clip) {
            if (cell.style.wrap) drawWrapped(canvas, cell, rect) else drawSingleLine(canvas, cell, rect)
        }
    }

    private fun drawSingleLine(canvas: Canvas, cell: XlsxCell, rect: RectF) {
        val pad = XlsxLayout.CELL_PADDING_PT
        var text = cell.text.take(MAX_DRAWN_CHARS).replace('\n', ' ')
        var width = textPaint.measureText(text)
        if (cell.kind == CellKind.NUMBER && width > rect.width() - 2 * pad) {
            // Excel's "doesn't fit" marker; a cut-off number would print a wrong value instead.
            val hash = textPaint.measureText("#").coerceAtLeast(MIN_GLYPH_PT)
            text = "#".repeat(((rect.width() - 2 * pad) / hash).toInt().coerceAtLeast(1))
            width = textPaint.measureText(text)
        }
        val x = when (effectiveAlign(cell)) {
            HorizontalAlign.RIGHT -> rect.right - pad - width
            HorizontalAlign.CENTER -> rect.centerX() - width / 2
            else -> rect.left + pad
        }
        val metrics = textPaint.fontMetrics
        val baseline = when (cell.style.vertical) {
            VerticalAlign.TOP -> rect.top + pad - metrics.ascent
            VerticalAlign.CENTER -> rect.centerY() - (metrics.ascent + metrics.descent) / 2
            VerticalAlign.BOTTOM -> rect.bottom - pad - metrics.descent
        }
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun drawWrapped(canvas: Canvas, cell: XlsxCell, rect: RectF) {
        val layout = wrapped(cell, rect.width())
        val pad = XlsxLayout.CELL_PADDING_PT
        val y = when (cell.style.vertical) {
            VerticalAlign.TOP -> rect.top + pad
            VerticalAlign.CENTER -> rect.centerY() - layout.height / 2f
            VerticalAlign.BOTTOM -> rect.bottom - pad - layout.height
        }
        canvas.withTranslation(rect.left + pad, y.coerceAtLeast(rect.top)) {
            layout.draw(canvas)
        }
    }

    private fun wrapped(cell: XlsxCell, widthPt: Float): StaticLayout {
        val text = cell.text.take(MAX_DRAWN_CHARS)
        val width = (widthPt - 2 * XlsxLayout.CELL_PADDING_PT).toInt().coerceAtLeast(1)
        val alignment = when (effectiveAlign(cell)) {
            HorizontalAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            HorizontalAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()
    }

    private fun applyFont(font: FontStyle) {
        val style = when {
            font.bold && font.italic -> Typeface.BOLD_ITALIC
            font.bold -> Typeface.BOLD
            font.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = typefaces.getOrPut(style) { Typeface.create(Typeface.SANS_SERIF, style) }
        textPaint.textSize = font.sizePt
        textPaint.color = font.color
        textPaint.isUnderlineText = font.underline
        textPaint.isStrikeThruText = font.strike
    }

    private companion object {
        const val HEADER_SIZE_PT = 8f
        const val HEADER_COLOR = 0xFF757575.toInt()
        const val MIN_GLYPH_PT = 1f

        /** More than a page of text in one cell can't print anyway; capping keeps layout fast. */
        const val MAX_DRAWN_CHARS = 4_000
    }
}

/** General alignment as Excel resolves it: numbers right, booleans and errors centred, text left. */
internal fun effectiveAlign(cell: XlsxCell): HorizontalAlign = when {
    cell.style.horizontal != HorizontalAlign.GENERAL -> cell.style.horizontal
    cell.kind == CellKind.NUMBER -> HorizontalAlign.RIGHT
    cell.kind == CellKind.BOOLEAN || cell.kind == CellKind.ERROR -> HorizontalAlign.CENTER
    else -> HorizontalAlign.LEFT
}

/**
 * Unwrapped text spills into empty neighbours as in Excel: rightwards when left-aligned,
 * leftwards when right-aligned, both ways when centred, stopping at the next non-empty cell.
 */
internal fun overflowClip(
    layout: SheetLayout,
    cells: List<XlsxCell>,
    index: Int,
    rect: RectF,
    bounds: RectF
): RectF {
    val cell = cells[index]
    if (cell.style.wrap || cell.kind == CellKind.NUMBER) return rect
    val align = effectiveAlign(cell)
    val right = if (align != HorizontalAlign.RIGHT) overflowRight(layout, cells, index, bounds) else rect.right
    val left = if (align != HorizontalAlign.LEFT) overflowLeft(layout, cells, index, bounds) else rect.left
    return RectF(minOf(left, rect.left), rect.top, maxOf(right, rect.right), rect.bottom)
}

/** The left edge of the next non-empty cell to the right, or the page's edge. */
private fun overflowRight(layout: SheetLayout, cells: List<XlsxCell>, index: Int, bounds: RectF): Float {
    var next = index + 1
    while (next < cells.size && cells[next].text.isEmpty()) next++
    return cells.getOrNull(next)?.col?.let(layout::xOf) ?: bounds.right
}

/** The right edge of the previous non-empty cell to the left, or the page's edge. */
private fun overflowLeft(layout: SheetLayout, cells: List<XlsxCell>, index: Int, bounds: RectF): Float {
    var previous = index - 1
    while (previous >= 0 && cells[previous].text.isEmpty()) previous--
    return cells.getOrNull(previous)?.col?.let { layout.xOf(it + 1) } ?: bounds.left
}
