package com.skeler.scanely.core.idcard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import java.io.OutputStream
import kotlin.math.hypot

/** How the copy looks: as scanned, grey, or a crisp photocopy. */
enum class CopyLook { COLOUR, GREY, PHOTOCOPY }

/** Everything that defines one ID copy page. [sides] are in page order, already turned by the user. */
data class IdCopy(
    val kind: IdDocumentKind,
    val sides: List<Bitmap>,
    val look: CopyLook = CopyLook.COLOUR,
    val watermark: String = "",
    /** 1 = true size; see [IdCardLayout.sizeSteps]. */
    val scale: Float = 1f
)

/**
 * Draws an [IdCopy] as an A4 page. One drawing path at 300 dpi serves the preview (scaled down),
 * the image export and the PDF (scaled to points), so what the user sees is what prints.
 */
object IdCardRenderer {

    private const val PDF_POINTS_PER_INCH = 72f
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842

    /** The page as a bitmap at [dpi] (300 for export; ~100 for the on-screen preview). */
    fun renderPage(copy: IdCopy, dpi: Int = IdCardLayout.DEFAULT_DPI): Bitmap {
        val w = IdCardLayout.pageWidthPx(dpi)
        val h = IdCardLayout.pageHeightPx(dpi)
        val page = createBitmap(w, h)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val scale = dpi.toFloat() / IdCardLayout.DEFAULT_DPI
        canvas.scale(scale, scale)
        drawPage(canvas, copy)
        return page
    }

    /** One A4 page (595 x 842 pt); the scans keep their full resolution inside it. */
    fun writePdf(copy: IdCopy, out: OutputStream) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create())
            val scale = PDF_POINTS_PER_INCH / IdCardLayout.DEFAULT_DPI
            page.canvas.drawColor(Color.WHITE)
            page.canvas.scale(scale, scale)
            drawPage(page.canvas, copy)
            document.finishPage(page)
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** Draws on a canvas whose units are 300-dpi page pixels. */
    fun drawPage(canvas: Canvas, copy: IdCopy) {
        val placements = IdCardLayout.layout(copy.kind, copy.sides.map { it.width to it.height }, copy.scale)
        placements.forEachIndexed { i, placement -> drawSide(canvas, copy, copy.sides[i], placement) }
    }

    private fun drawSide(canvas: Canvas, copy: IdCopy, scan: Bitmap, placement: SidePlacement) {
        val box = placement.box.toRectF()
        val corner = IdCardLayout.mmToPx(copy.kind.cornerMm * copy.scale).toFloat()
        val clip = Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) }
        canvas.withSave {
            clipPath(clip)
            drawBitmap(scan, imageMatrix(scan, placement), lookPaint(copy.look))
            if (copy.watermark.isNotBlank()) drawWatermark(this, box, copy.watermark.trim())
        }
        // A hairline cut guide, as on a real copier output: helps the user trim the card out.
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = IdCardLayout.mmToPx(GUIDE_MM).toFloat().coerceAtLeast(1f)
            color = GUIDE_COLOUR
        }
        canvas.drawPath(clip, guide)
    }

    /** Maps the scan onto its slot, turning a portrait scan a quarter turn first. */
    private fun imageMatrix(scan: Bitmap, placement: SidePlacement): Matrix {
        val dst = placement.image.toRectF()
        return Matrix().apply {
            if (placement.rotate90) {
                // rotate about the origin, then the rotated bitmap spans (-h..0, 0..w): move it back
                postRotate(-QUARTER_TURN)
                postTranslate(0f, scan.width.toFloat())
                postScale(dst.width() / scan.height, dst.height() / scan.width)
            } else {
                postScale(dst.width() / scan.width, dst.height() / scan.height)
            }
            postTranslate(dst.left, dst.top)
        }
    }

    private fun lookPaint(look: CopyLook): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = when (look) {
            CopyLook.COLOUR -> null
            CopyLook.GREY -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            CopyLook.PHOTOCOPY -> ColorMatrixColorFilter(photocopyMatrix())
        }
    }

    /** Grey, high contrast, paper pushed to white — how a copier renders a card. */
    private fun photocopyMatrix(): ColorMatrix {
        val grey = ColorMatrix().apply { setSaturation(0f) }
        val s = PHOTOCOPY_CONTRAST
        val t = (1f - s) / 2f * 255f + PHOTOCOPY_LIFT
        val contrast = ColorMatrix(
            floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f, s, 0f, 0f, t,
                0f, 0f, s, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return ColorMatrix().apply { postConcat(grey); postConcat(contrast) }
    }

    /**
     * "Copy for …" written diagonally across the card, repeated so no part of the card can be cut out
     * clean — the anti-misuse mark banks and offices accept. Readable, but light enough to keep the
     * card's own text legible.
     */
    private fun drawWatermark(canvas: Canvas, box: RectF, text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WATERMARK_COLOUR
            textSize = box.height() / WATERMARK_ROWS_PER_CARD
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val unit = "$text   ·   "
        val unitWidth = paint.measureText(unit).coerceAtLeast(1f)
        val reach = hypot(box.width(), box.height())
        val repeats = (reach * 2 / unitWidth).toInt() + 2
        val line = unit.repeat(repeats)
        val rowGap = paint.textSize * 2.8f
        canvas.withSave {
            rotate(WATERMARK_ANGLE, box.centerX(), box.centerY())
            var y = box.centerY() - reach
            var row = 0
            while (y < box.centerY() + reach) {
                // stagger alternate rows so the repeats don't line up into columns
                val x = box.centerX() - reach - (if (row % 2 == 0) 0f else unitWidth / 2)
                drawText(line, x, y, paint)
                y += rowGap
                row++
            }
        }
    }

    private fun PxRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private const val QUARTER_TURN = 90f
    private const val GUIDE_MM = 0.2f
    private const val GUIDE_COLOUR = 0xFFB8BCC4.toInt()
    private const val WATERMARK_COLOUR = 0x471E3A8A // deep blue at ~28 % — ink-like, not alarming red
    private const val WATERMARK_ANGLE = -24f
    private const val WATERMARK_ROWS_PER_CARD = 12f
    private const val PHOTOCOPY_CONTRAST = 1.55f
    private const val PHOTOCOPY_LIFT = 18f
}
