package com.skeler.scanely.ui.pdfeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawTransform
import kotlin.math.min

/**
 * Maps between the page's own coordinate system and the canvas.
 *
 * Every marking is stored in page points on the upright page, because that is the space the
 * overlay is stamped in. Rotation and zoom therefore live here and nowhere else: the drawing code
 * and the gesture code both work in page points and never learn that the page is turned.
 */
data class PageProjection(
    val containerSize: Size,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val rotation: Int = 0,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero
) {
    private val quarterTurns: Int = ((rotation / 90) % 4 + 4) % 4

    private val displayWidthPt: Float
        get() = if (quarterTurns % 2 == 0) pageWidthPt else pageHeightPt

    private val displayHeightPt: Float
        get() = if (quarterTurns % 2 == 0) pageHeightPt else pageWidthPt

    val fitScale: Float
        get() = min(
            containerSize.width / displayWidthPt.coerceAtLeast(1f),
            containerSize.height / displayHeightPt.coerceAtLeast(1f)
        )

    val scale: Float get() = fitScale * zoom

    private val centre: Offset
        get() = Offset(containerSize.width / 2f, containerSize.height / 2f) + pan

    /** Canvas pixels to page points. */
    fun toPage(offset: Offset): Offset {
        val local = (offset - centre) / scale.coerceAtLeast(0.0001f)
        val unrotated = when (quarterTurns) {
            1 -> Offset(local.y, -local.x)
            2 -> Offset(-local.x, -local.y)
            3 -> Offset(-local.y, local.x)
            else -> local
        }
        return Offset(unrotated.x + pageWidthPt / 2f, unrotated.y + pageHeightPt / 2f)
    }

    /** How far the page pans each way before an edge comes in: half of what it overflows the container by. */
    fun panLimit(): Offset = Offset(
        ((displayWidthPt * scale - containerSize.width) / 2f).coerceAtLeast(0f),
        ((displayHeightPt * scale - containerSize.height) / 2f).coerceAtLeast(0f)
    )

    fun clampToPage(point: Offset): Offset = Offset(
        point.x.coerceIn(0f, pageWidthPt),
        point.y.coerceIn(0f, pageHeightPt)
    )

    /** Applies the page transform, after which the draw block works in page points. */
    fun DrawTransform.applyPageTransform() {
        translate(centre.x, centre.y)
        rotate(quarterTurns * QUARTER_TURN_DEGREES, Offset.Zero)
        scale(scale, scale, Offset.Zero)
        translate(-pageWidthPt / 2f, -pageHeightPt / 2f)
    }
}

private const val QUARTER_TURN_DEGREES = 90f
