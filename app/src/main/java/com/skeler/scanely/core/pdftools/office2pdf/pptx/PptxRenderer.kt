package com.skeler.scanely.core.pdftools.office2pdf.pptx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hands the renderer a decoded picture no bigger than it needs for a box of [widthPx] × [heightPx]
 * device pixels, or null when the image can't be decoded (EMF/WMF, damaged, too large).
 */
internal fun interface ImageSource {
    fun bitmap(part: String, widthPx: Int, heightPx: Int): Bitmap?
}

/**
 * Draws one [PptxSlide] onto a PDF page canvas whose units are points, so slide geometry maps
 * 1:1. Each element is drawn on its own: one that fails is dropped with a note, never the slide.
 */
internal class PptxRenderer(private val images: ImageSource, private val issues: PptxIssues) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = PptxTextLayout()

    fun draw(canvas: Canvas, slide: PptxSlide, width: Float, height: Float) {
        canvas.drawColor(WHITE)
        slide.background?.let { drawFill(canvas, it, RectF(0f, 0f, width, height), null) }
        for (element in slide.elements) drawElement(canvas, element)
    }

    /**
     * The framework's Path, StaticLayout and span code throws these on degenerate input (odd sizes,
     * span ranges); that element is dropped and noted, the rest of the slide still draws.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun drawElement(canvas: Canvas, element: SlideElement) {
        try {
            when (element) {
                is ShapeElement -> drawShape(canvas, element)
                is PictureElement -> drawPicture(canvas, element)
                is TableElement -> drawTable(canvas, element)
            }
        } catch (_: IllegalArgumentException) {
            issues.shapesSimplified = true
        } catch (_: IllegalStateException) {
            issues.shapesSimplified = true
        } catch (_: IndexOutOfBoundsException) {
            issues.shapesSimplified = true
        }
    }

    // --- shapes ---

    private fun drawShape(canvas: Canvas, shape: ShapeElement) {
        val f = shape.frame
        if (shape.fill != null || shape.stroke != null) {
            withFrame(canvas, f, applyFlips = true) {
                val box = RectF(0f, 0f, f.width, f.height)
                val path = geometryPath(shape.geometry, shape.adjust, f.width, f.height)
                if (shape.geometry != Geometry.LINE) shape.fill?.let { drawFill(canvas, it, box, path) }
                shape.stroke?.let { stroke(canvas, path, it) }
            }
        }
        shape.text?.let { body ->
            // Text turns with its shape but is never mirrored; a vertical flip reads as a half turn.
            val textFrame = if (f.flipV) f.copy(rotation = f.rotation + HALF_TURN) else f
            withFrame(canvas, textFrame, applyFlips = false) { text.drawText(canvas, body, f.width, f.height) }
        }
    }

    private fun drawPicture(canvas: Canvas, picture: PictureElement) {
        val f = picture.frame
        if (f.width <= 0f || f.height <= 0f) return
        val bitmap = images.bitmap(picture.part, toPixels(f.width), toPixels(f.height))
        withFrame(canvas, f, applyFlips = true) {
            val dst = RectF(0f, 0f, f.width, f.height)
            val path = geometryPath(picture.geometry, null, f.width, f.height)
            if (bitmap != null) {
                canvas.withSave {
                    if (picture.geometry != Geometry.RECT) canvas.clipPath(path)
                    canvas.drawBitmap(bitmap, cropRect(bitmap, picture.crop), dst, bitmapPaint)
                }
            }
            picture.stroke?.let { stroke(canvas, path, it) }
        }
    }

    private fun drawFill(canvas: Canvas, fill: Fill, box: RectF, path: Path?) {
        when (fill) {
            Fill.None -> Unit
            is Fill.Solid -> {
                fillPaint.shader = null
                fillPaint.color = fill.color
                if (path != null) canvas.drawPath(path, fillPaint) else canvas.drawRect(box, fillPaint)
            }
            is Fill.Gradient -> {
                fillPaint.shader = gradient(fill, box)
                fillPaint.color = BLACK
                if (path != null) canvas.drawPath(path, fillPaint) else canvas.drawRect(box, fillPaint)
                fillPaint.shader = null
            }
            is Fill.Image -> {
                val bitmap = images.bitmap(fill.part, toPixels(box.width()), toPixels(box.height())) ?: return
                val save = canvas.save()
                if (path != null) canvas.clipPath(path)
                canvas.drawBitmap(bitmap, null, box, bitmapPaint)
                canvas.restoreToCount(save)
            }
        }
    }

    private fun stroke(canvas: Canvas, path: Path, stroke: Stroke) {
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.width
        strokePaint.pathEffect = if (stroke.dashed) {
            DashPathEffect(floatArrayOf(stroke.width * DASH_ON, stroke.width * DASH_OFF), 0f)
        } else {
            null
        }
        canvas.drawPath(path, strokePaint)
    }

    // --- tables ---

    private fun drawTable(canvas: Canvas, table: TableElement) {
        val f = table.frame
        val columnX = FloatArray(table.columns.size + 1)
        table.columns.forEachIndexed { i, w -> columnX[i + 1] = columnX[i] + w }

        val heights = table.rows.map { row -> rowHeight(row, columnX, table.columns.size) }
        val rowY = FloatArray(heights.size + 1)
        heights.forEachIndexed { i, h -> rowY[i + 1] = rowY[i] + h }

        withFrame(canvas, f.copy(height = rowY.last()), applyFlips = false) {
            table.rows.forEachIndexed { r, row -> drawRow(canvas, table, row, r, columnX, rowY) }
        }
    }

    /** Rows grow to fit their tallest unspanned cell, as PowerPoint does when text is added. */
    private fun rowHeight(row: TableRow, columnX: FloatArray, columnCount: Int): Float {
        var needed = row.height
        var column = 0
        for (cell in row.cells) {
            val span = cell.gridSpan
            val body = cell.text
            if (body != null && sizesRow(cell) && column < columnCount) {
                val width = spanWidth(columnX, column, span)
                needed = maxOf(needed, text.textHeight(body, width))
            }
            column += span
        }
        return needed
    }

    private fun drawRow(
        canvas: Canvas,
        table: TableElement,
        row: TableRow,
        r: Int,
        columnX: FloatArray,
        rowY: FloatArray
    ) {
        var column = 0
        for (cell in row.cells) {
            if (column >= table.columns.size) break
            if (!cell.covered) {
                val endColumn = (column + cell.gridSpan).coerceAtMost(table.columns.size)
                val endRow = (r + cell.rowSpan).coerceAtMost(table.rows.size)
                drawCell(canvas, cell, RectF(columnX[column], rowY[r], columnX[endColumn], rowY[endRow]))
            }
            column += cell.gridSpan
        }
    }

    private fun drawCell(canvas: Canvas, cell: TableCell, box: RectF) {
        cell.fill?.let { drawFill(canvas, it, box, null) }
        cell.text?.let { body ->
            canvas.withTranslation(box.left, box.top) {
                text.drawText(canvas, body, box.width(), box.height())
            }
        }
        cell.borders.left?.let { line(canvas, box.left, box.top, box.left, box.bottom, it) }
        cell.borders.top?.let { line(canvas, box.left, box.top, box.right, box.top, it) }
        cell.borders.right?.let { line(canvas, box.right, box.top, box.right, box.bottom, it) }
        cell.borders.bottom?.let { line(canvas, box.left, box.bottom, box.right, box.bottom, it) }
    }

    private fun line(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, stroke: Stroke) {
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.width
        strokePaint.pathEffect = null
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
    }

    // --- frames ---

    private inline fun withFrame(canvas: Canvas, f: Frame, applyFlips: Boolean, block: () -> Unit) {
        val save = canvas.save()
        canvas.translate(f.x + f.width / 2, f.y + f.height / 2)
        if (f.rotation != 0f) canvas.rotate(f.rotation)
        if (applyFlips && (f.flipH || f.flipV)) canvas.scale(if (f.flipH) -1f else 1f, if (f.flipV) -1f else 1f)
        canvas.translate(-f.width / 2, -f.height / 2)
        try {
            block()
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun toPixels(points: Float): Int = (points * IMAGE_DPI / POINTS_PER_INCH).toInt().coerceAtLeast(1)
}

/** Only a cell that spans one row sizes its row; covered cells belong to the cell that spans them. */
private fun sizesRow(cell: TableCell): Boolean = !cell.covered && cell.rowSpan == 1

private fun spanWidth(columnX: FloatArray, column: Int, span: Int): Float {
    val end = (column + span).coerceAtMost(columnX.size - 1)
    return columnX[end] - columnX[column]
}

private fun cropRect(bitmap: Bitmap, crop: Crop?): Rect? {
    crop ?: return null
    val w = bitmap.width
    val h = bitmap.height
    // Negative crops pad the picture out; padding is dropped and the image fills the box.
    val rect = Rect(
        (w * crop.left.coerceIn(0f, 1f)).toInt(),
        (h * crop.top.coerceIn(0f, 1f)).toInt(),
        (w * (1 - crop.right.coerceIn(0f, 1f))).toInt(),
        (h * (1 - crop.bottom.coerceIn(0f, 1f))).toInt()
    )
    return rect.takeIf { it.width() > 0 && it.height() > 0 }
}

private fun gradient(fill: Fill.Gradient, box: RectF): Shader {
    val rad = Math.toRadians(fill.angle.toDouble())
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    val half = (abs(box.width() * dx) + abs(box.height() * dy)) / 2
    val cx = box.centerX()
    val cy = box.centerY()
    return LinearGradient(
        cx - dx * half,
        cy - dy * half,
        cx + dx * half,
        cy + dy * half,
        fill.stops.map { it.color }.toIntArray(),
        fill.stops.map { it.position }.toFloatArray(),
        Shader.TileMode.CLAMP
    )
}

private const val HALF_TURN = 180f
private const val DASH_ON = 4f
private const val DASH_OFF = 3f

/** Pictures are decoded for about this density on the page — sharp in print, small in memory. */
private const val IMAGE_DPI = 200f
private const val POINTS_PER_INCH = 72f
