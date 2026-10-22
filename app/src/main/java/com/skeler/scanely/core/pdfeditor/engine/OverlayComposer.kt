package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.skeler.scanely.core.pdfeditor.model.InkStroke
import com.skeler.scanely.core.pdfeditor.model.LineStyle
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.ShapeKind
import com.skeler.scanely.core.pdfeditor.model.TextFit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Page geometry in PDF points, which is the unit every [PdfEdit.Marking] is stored in. */
data class PageGeometry(val widthPt: Float, val heightPt: Float)

/**
 * Renders staged markings into a transparent PDF that lines up 1:1 with the document, ready for
 * `qpdf --overlay`.
 *
 * Stamping rather than rewriting content streams keeps the original text, vectors and any existing
 * signature byte-for-byte intact. Android's PdfDocument canvas is already top-left origin, so
 * nothing is flipped here, and it embeds whatever Typeface it draws with.
 */
@Singleton
class OverlayComposer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val typefaces = mutableMapOf<OfficialFont, Typeface>()

    /**
     * Emits one page per entry in [pages] — blank where there are no markings — so overlay page N
     * always maps to document page N.
     *
     * [stampBitmaps] resolves [PdfEdit.Stamp.imageId] to the signature or image being placed.
     * Returns null when nothing would be drawn, letting the caller skip the overlay pass entirely.
     */
    suspend fun compose(
        pages: List<PageGeometry>,
        markings: Map<Int, List<PdfEdit.Marking>>,
        stampBitmaps: Map<String, Bitmap>,
        output: File
    ): File? = withContext(Dispatchers.Default) {
        if (markings.values.all { it.isEmpty() }) return@withContext null

        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, geometry ->
                val info = PdfDocument.PageInfo.Builder(
                    geometry.widthPt.toInt().coerceAtLeast(1),
                    geometry.heightPt.toInt().coerceAtLeast(1),
                    index + 1
                ).create()
                val page = document.startPage(info)
                markings[index]?.forEach { draw(page.canvas, it, stampBitmaps) }
                document.finishPage(page)
            }
            output.outputStream().use { document.writeTo(it) }
            output
        } finally {
            document.close()
        }
    }

    private fun draw(
        canvas: Canvas,
        marking: PdfEdit.Marking,
        stampBitmaps: Map<String, Bitmap>
    ) {
        when (marking) {
            is PdfEdit.Ink -> marking.strokes.forEach { drawStroke(canvas, it) }
            is PdfEdit.Highlight -> drawHighlight(canvas, marking)
            is PdfEdit.Underline -> drawUnderline(canvas, marking)
            is PdfEdit.TextBox -> drawText(
                canvas,
                marking.box,
                marking.text,
                marking.font,
                marking.sizePt,
                marking.color,
                marking.baselinePt
            )
            is PdfEdit.ReplaceText -> {
                canvas.drawRect(marking.cover.toAndroid(), fill(marking.coverColor))
                drawText(
                    canvas,
                    marking.textBox,
                    marking.replacement,
                    marking.font,
                    marking.sizePt,
                    marking.color,
                    marking.baselinePt,
                    fitWidthPt = marking.cover.width
                )
            }
            is PdfEdit.Stamp -> drawStamp(canvas, marking, stampBitmaps)
            is PdfEdit.Shape -> drawShape(canvas, marking)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: InkStroke) {
        if (stroke.points.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.widthPt
            color = stroke.color.toArgb()
            if (stroke.translucent) alpha = HIGHLIGHTER_ALPHA
        }

        // Quadratic midpoint smoothing: raw touch samples are visibly faceted at print resolution
        // even though they look fine on screen.
        val path = Path().apply {
            val first = stroke.points.first()
            moveTo(first.x, first.y)
            for (i in 1 until stroke.points.size) {
                val previous = stroke.points[i - 1]
                val current = stroke.points[i]
                quadTo(
                    previous.x,
                    previous.y,
                    (previous.x + current.x) / 2f,
                    (previous.y + current.y) / 2f
                )
            }
            stroke.points.last().let { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }

    // Alpha rather than a multiply blend: PdfDocument's canvas does not translate Porter-Duff
    // modes into PDF blend operators, so a blend mode silently renders as opaque in some viewers.
    private fun drawHighlight(canvas: Canvas, marking: PdfEdit.Highlight) {
        val paint = fill(marking.color).apply { alpha = HIGHLIGHTER_ALPHA }
        marking.quads.forEach { canvas.drawRect(it.toAndroid(), paint) }
    }

    private fun drawUnderline(canvas: Canvas, marking: PdfEdit.Underline) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = UNDERLINE_WEIGHT
            strokeCap = Paint.Cap.ROUND
            color = marking.color.toArgb()
        }
        marking.quads.forEach { quad ->
            val y = when (marking.style) {
                LineStyle.STRIKETHROUGH -> quad.top + quad.height / 2f
                else -> quad.bottom - UNDERLINE_DROP
            }
            if (marking.style == LineStyle.SQUIGGLY) {
                canvas.drawPath(squiggle(quad.left, quad.right, y), paint)
            } else {
                canvas.drawLine(quad.left, y, quad.right, y, paint)
            }
        }
    }

    private fun squiggle(left: Float, right: Float, y: Float): Path = Path().apply {
        moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            val next = (x + SQUIGGLE_PERIOD).coerceAtMost(right)
            quadTo(
                (x + next) / 2f,
                if (up) y - SQUIGGLE_AMPLITUDE else y + SQUIGGLE_AMPLITUDE,
                next,
                y
            )
            x = next
            up = !up
        }
    }

    /**
     * [fitWidthPt] is the width the text should *try* to occupy; [box] is the width it is allowed
     * to use. For a replacement those differ: it aims to fit the space the old run vacated, so
     * nothing to its right is disturbed, but may run on to the margin rather than wrap.
     */
    private fun drawText(
        canvas: Canvas,
        box: Rect,
        text: String,
        font: OfficialFont,
        sizePt: Float,
        color: Color,
        baselinePt: Float?,
        fitWidthPt: Float = box.width
    ) {
        if (text.isEmpty()) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaceFor(font)
            textSize = sizePt
            this.color = color.toArgb()
        }
        paint.textSize = TextFit.fittedSize(sizePt, paint.measureText(text), fitWidthPt)

        val width = box.width.toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        // Anchoring on the baseline rather than the box top is what keeps a replacement sitting on
        // the same line as the text around it: the gap between a line box's top and its baseline is
        // roughly a fifth of the size, which is a whole line's worth of drift on a dense page.
        val top = baselinePt?.let { it - layout.getLineBaseline(0) } ?: box.top

        canvas.save()
        canvas.translate(box.left, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawStamp(
        canvas: Canvas,
        marking: PdfEdit.Stamp,
        stampBitmaps: Map<String, Bitmap>
    ) {
        val bitmap = stampBitmaps[marking.imageId] ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        canvas.save()
        if (marking.rotationDegrees != 0f) {
            canvas.rotate(
                marking.rotationDegrees,
                marking.box.left + marking.box.width / 2f,
                marking.box.top + marking.box.height / 2f
            )
        }
        canvas.drawBitmap(bitmap, null, marking.box.toAndroid(), paint)
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, marking: PdfEdit.Shape) {
        val box = marking.box.toAndroid()
        marking.fillColor?.let { canvas.drawShapeBody(marking.kind, box, fill(it)) }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = marking.strokeWidthPt
            strokeCap = Paint.Cap.ROUND
            color = marking.strokeColor.toArgb()
        }
        canvas.drawShapeBody(marking.kind, box, stroke)

        if (marking.kind == ShapeKind.ARROW) {
            canvas.drawPath(arrowHead(box), stroke.apply { style = Paint.Style.FILL })
        }
    }

    private fun Canvas.drawShapeBody(kind: ShapeKind, box: RectF, paint: Paint) {
        when (kind) {
            ShapeKind.RECTANGLE -> drawRect(box, paint)
            ShapeKind.ELLIPSE -> drawOval(box, paint)
            ShapeKind.LINE, ShapeKind.ARROW ->
                if (paint.style == Paint.Style.STROKE) {
                    drawLine(box.left, box.top, box.right, box.bottom, paint)
                }
        }
    }

    private fun arrowHead(box: RectF): Path {
        val angle = atan2(box.bottom - box.top, box.right - box.left)
        val spread = Math.toRadians(ARROW_SPREAD_DEGREES).toFloat()
        return Path().apply {
            moveTo(box.right, box.bottom)
            lineTo(
                box.right - ARROW_HEAD_LENGTH * cos(angle - spread),
                box.bottom - ARROW_HEAD_LENGTH * sin(angle - spread)
            )
            lineTo(
                box.right - ARROW_HEAD_LENGTH * cos(angle + spread),
                box.bottom - ARROW_HEAD_LENGTH * sin(angle + spread)
            )
            close()
        }
    }

    private fun typefaceFor(font: OfficialFont): Typeface = typefaces.getOrPut(font) {
        runCatching { Typeface.createFromAsset(context.assets, font.assetPath) }
            .getOrDefault(Typeface.DEFAULT)
    }

    private fun fill(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color.toArgb()
    }

    private fun Rect.toAndroid() = RectF(left, top, right, bottom)

    private companion object {
        const val HIGHLIGHTER_ALPHA = 90
        const val UNDERLINE_WEIGHT = 1.2f
        const val UNDERLINE_DROP = 1.5f
        const val SQUIGGLE_PERIOD = 4f
        const val SQUIGGLE_AMPLITUDE = 1.5f
        const val ARROW_HEAD_LENGTH = 9f
        const val ARROW_SPREAD_DEGREES = 26.0
    }
}
