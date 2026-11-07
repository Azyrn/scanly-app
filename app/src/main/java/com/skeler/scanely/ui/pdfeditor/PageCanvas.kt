package com.skeler.scanely.ui.pdfeditor

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import com.skeler.scanely.core.pdfeditor.model.InkStroke
import com.skeler.scanely.core.pdfeditor.model.LineStyle
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.ShapeKind
import com.skeler.scanely.core.pdfeditor.model.TextFit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

inline fun DrawScope.withPage(projection: PageProjection, block: DrawScope.() -> Unit) {
    withTransform({ with(projection) { applyPageTransform() } }, block)
}

@Composable
fun rememberOfficialFonts(): Map<OfficialFont, FontFamily> {
    val assets = LocalContext.current.assets
    return remember(assets) {
        OfficialFont.entries.associateWith { font ->
            runCatching { FontFamily(Font(path = font.assetPath, assetManager = assets)) }
                .getOrDefault(FontFamily.Default)
        }
    }
}

/**
 * Draws one staged marking exactly as `OverlayComposer` will write it, so the preview is the
 * result rather than an impression of it.
 */
fun DrawScope.drawMarking(
    marking: PdfEdit.Marking,
    measurer: TextMeasurer,
    fonts: Map<OfficialFont, FontFamily>,
    fontSizeOf: (Float) -> TextUnit,
    stampFor: (String) -> Bitmap?
) {
    when (marking) {
        is PdfEdit.Ink -> drawInk(marking)

        is PdfEdit.Highlight -> drawHighlight(marking)

        is PdfEdit.Underline -> marking.quads.forEach { drawUnderline(it, marking) }

        is PdfEdit.TextBox -> drawPageText(
            marking.box, marking.text, marking.font, marking.sizePt, marking.color,
            marking.baselinePt, measurer, fonts, fontSizeOf
        )

        is PdfEdit.ReplaceText -> {
            drawRect(marking.coverColor, marking.cover.topLeft, marking.cover.size)
            drawPageText(
                marking.textBox, marking.replacement, marking.font, marking.sizePt, marking.color,
                marking.baselinePt, measurer, fonts, fontSizeOf,
                fitWidthPt = marking.cover.width
            )
        }

        is PdfEdit.Stamp -> drawStamp(marking, stampFor)

        is PdfEdit.Shape -> drawPageShape(marking)
    }
}

private fun DrawScope.drawInk(marking: PdfEdit.Ink) = marking.strokes.forEach { drawInkStroke(it) }

private fun DrawScope.drawHighlight(marking: PdfEdit.Highlight) = marking.quads.forEach {
    drawRect(
        color = marking.color.copy(alpha = HIGHLIGHT_ALPHA),
        topLeft = it.topLeft,
        size = it.size
    )
}

/** Draws the stamp's image, or nothing while [stampFor] has no bitmap for it. */
private fun DrawScope.drawStamp(marking: PdfEdit.Stamp, stampFor: (String) -> Bitmap?) {
    val bitmap = stampFor(marking.imageId) ?: return
    drawImage(
        image = bitmap.asImageBitmap(),
        dstOffset = IntOffset(
            marking.box.left.roundToInt(),
            marking.box.top.roundToInt()
        ),
        dstSize = IntSize(
            marking.box.width.roundToInt().coerceAtLeast(1),
            marking.box.height.roundToInt().coerceAtLeast(1)
        )
    )
}

fun DrawScope.drawInkStroke(stroke: InkStroke) {
    if (stroke.points.size < 2) return
    drawPath(
        path = smoothPath(stroke.points),
        color = stroke.color.copy(
            alpha = if (stroke.translucent) HIGHLIGHT_ALPHA else stroke.color.alpha
        ),
        style = Stroke(
            width = stroke.widthPt,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        quadraticTo(
            previous.x,
            previous.y,
            (previous.x + current.x) / 2f,
            (previous.y + current.y) / 2f
        )
    }
    lineTo(points.last().x, points.last().y)
}

private fun DrawScope.drawUnderline(quad: Rect, marking: PdfEdit.Underline) {
    val y = when (marking.style) {
        LineStyle.STRIKETHROUGH -> quad.top + quad.height / 2f
        else -> quad.bottom - UNDERLINE_DROP_PT
    }
    if (marking.style == LineStyle.SQUIGGLY) {
        drawPath(
            path = squigglePath(quad.left, quad.right, y),
            color = marking.color,
            style = Stroke(width = UNDERLINE_WEIGHT_PT, cap = StrokeCap.Round)
        )
    } else {
        drawLine(
            color = marking.color,
            start = Offset(quad.left, y),
            end = Offset(quad.right, y),
            strokeWidth = UNDERLINE_WEIGHT_PT,
            cap = StrokeCap.Round
        )
    }
}

private fun squigglePath(left: Float, right: Float, y: Float): Path = Path().apply {
    moveTo(left, y)
    var x = left
    var up = true
    while (x < right) {
        val next = min(x + SQUIGGLE_PERIOD_PT, right)
        quadraticTo(
            (x + next) / 2f,
            if (up) y - SQUIGGLE_AMPLITUDE_PT else y + SQUIGGLE_AMPLITUDE_PT,
            next,
            y
        )
        x = next
        up = !up
    }
}

private fun DrawScope.drawPageShape(marking: PdfEdit.Shape) {
    val box = marking.box
    val stroke = Stroke(width = marking.strokeWidthPt, cap = StrokeCap.Round)
    when (marking.kind) {
        ShapeKind.RECTANGLE -> {
            marking.fillColor?.let { drawRect(it, box.topLeft, box.size) }
            drawRect(marking.strokeColor, box.topLeft, box.size, style = stroke)
        }
        ShapeKind.ELLIPSE -> {
            marking.fillColor?.let { drawOval(it, box.topLeft, box.size) }
            drawOval(marking.strokeColor, box.topLeft, box.size, style = stroke)
        }
        ShapeKind.LINE, ShapeKind.ARROW -> {
            drawLine(
                color = marking.strokeColor,
                start = box.topLeft,
                end = Offset(box.right, box.bottom),
                strokeWidth = marking.strokeWidthPt,
                cap = StrokeCap.Round
            )
            if (marking.kind == ShapeKind.ARROW) {
                drawPath(arrowHead(box), marking.strokeColor)
            }
        }
    }
}

private fun arrowHead(box: Rect): Path {
    val angle = atan2(box.bottom - box.top, box.right - box.left)
    val spread = Math.toRadians(ARROW_SPREAD_DEGREES).toFloat()
    return Path().apply {
        moveTo(box.right, box.bottom)
        lineTo(
            box.right - ARROW_HEAD_PT * cos(angle - spread),
            box.bottom - ARROW_HEAD_PT * sin(angle - spread)
        )
        lineTo(
            box.right - ARROW_HEAD_PT * cos(angle + spread),
            box.bottom - ARROW_HEAD_PT * sin(angle + spread)
        )
        close()
    }
}

/**
 * Mirrors `OverlayComposer.drawText` step for step — same fit, same baseline anchor — because the
 * preview is only worth showing if it is the same arithmetic as the write.
 */
private fun DrawScope.drawPageText(
    box: Rect,
    text: String,
    font: OfficialFont,
    sizePt: Float,
    color: Color,
    baselinePt: Float?,
    measurer: TextMeasurer,
    fonts: Map<OfficialFont, FontFamily>,
    fontSizeOf: (Float) -> TextUnit,
    fitWidthPt: Float = box.width
) {
    if (text.isEmpty()) return
    val family = fonts[font] ?: FontFamily.Default
    fun styleAt(size: Float) = TextStyle(
        color = color,
        fontSize = fontSizeOf(size),
        fontFamily = family
    )

    val unconstrained = measurer.measure(text = text, style = styleAt(sizePt), softWrap = false)
    val fitted = TextFit.fittedSize(sizePt, unconstrained.size.width.toFloat(), fitWidthPt)

    val layout = measurer.measure(
        text = text,
        style = styleAt(fitted),
        constraints = Constraints(maxWidth = max(1, box.width.roundToInt()))
    )
    val top = baselinePt?.let { it - layout.firstBaseline } ?: box.top
    drawText(layout, topLeft = Offset(box.left, top))
}

private const val HIGHLIGHT_ALPHA = 0.35f
private const val UNDERLINE_WEIGHT_PT = 1.2f
private const val UNDERLINE_DROP_PT = 1.5f
private const val SQUIGGLE_PERIOD_PT = 4f
private const val SQUIGGLE_AMPLITUDE_PT = 1.5f
private const val ARROW_HEAD_PT = 9f
private const val ARROW_SPREAD_DEGREES = 26.0
