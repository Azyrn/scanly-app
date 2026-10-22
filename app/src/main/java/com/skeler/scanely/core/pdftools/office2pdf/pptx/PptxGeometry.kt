package com.skeler.scanely.core.pdftools.office2pdf.pptx

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/*
 * Outlines for the preset geometries the reader recognises, in a w × h box at the origin.
 */

private const val HALF = 0.5f
private const val DEFAULT_CORNER = 0.16667f
private const val QUARTER_TURN = 90f

internal fun geometryPath(geometry: Geometry, adjust: Float?, w: Float, h: Float): Path {
    val path = Path()
    when (geometry) {
        Geometry.RECT, Geometry.ROUND_RECT, Geometry.ELLIPSE -> addBox(path, geometry, adjust, w, h)
        Geometry.LINE, Geometry.TRIANGLE, Geometry.RIGHT_TRIANGLE, Geometry.DIAMOND ->
            addPolygon(path, geometry, adjust, w, h)
        Geometry.ARROW_RIGHT, Geometry.ARROW_LEFT, Geometry.ARROW_DOWN, Geometry.ARROW_UP ->
            addArrow(path, geometry, adjust, w, h)
    }
    return path
}

/** Rectangles and ovals; a rounded rectangle's [adjust] is its corner radius over the short side. */
private fun addBox(path: Path, geometry: Geometry, adjust: Float?, w: Float, h: Float) {
    when (geometry) {
        Geometry.ROUND_RECT -> {
            val r = min(w, h) * (adjust ?: DEFAULT_CORNER).coerceIn(0f, HALF)
            path.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        }
        Geometry.ELLIPSE -> path.addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
        else -> path.addRect(0f, 0f, w, h, Path.Direction.CW)
    }
}

/** Straight-edged shapes; a triangle's [adjust] places its apex along the top edge. */
private fun addPolygon(path: Path, geometry: Geometry, adjust: Float?, w: Float, h: Float) {
    when (geometry) {
        Geometry.LINE -> {
            path.moveTo(0f, 0f)
            path.lineTo(w, h)
        }
        Geometry.TRIANGLE -> {
            path.moveTo(w * (adjust ?: HALF).coerceIn(0f, 1f), 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
        }
        Geometry.RIGHT_TRIANGLE -> {
            path.moveTo(0f, 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
        }
        else -> {
            path.moveTo(w / 2, 0f)
            path.lineTo(w, h / 2)
            path.lineTo(w / 2, h)
            path.lineTo(0f, h / 2)
            path.close()
        }
    }
}

/** Every arrow is built pointing right, then mirrored or turned into place. */
private fun addArrow(path: Path, geometry: Geometry, adjust: Float?, w: Float, h: Float) {
    when (geometry) {
        Geometry.ARROW_RIGHT -> arrow(path, w, h, adjust)
        Geometry.ARROW_LEFT -> {
            arrow(path, w, h, adjust)
            path.transform(Matrix().apply { setScale(-1f, 1f, w / 2, h / 2) })
        }
        else -> {
            // Built pointing right in a box turned on its side, then turned into place.
            arrow(path, h, w, adjust)
            val m = Matrix()
            m.setRotate(QUARTER_TURN)
            m.postTranslate(w, 0f)
            if (geometry == Geometry.ARROW_UP) m.postScale(1f, -1f, w / 2, h / 2)
            path.transform(m)
        }
    }
}

/** A right arrow in a [w] × [h] box: shaft [adjust] of the height, head as long as half the short side. */
private fun arrow(path: Path, w: Float, h: Float, adjust: Float?) {
    val shaft = h * (adjust ?: HALF).coerceIn(0f, 1f)
    val head = min(w, min(w, h) * HALF)
    val top = (h - shaft) / 2
    path.moveTo(0f, top)
    path.lineTo(w - head, top)
    path.lineTo(w - head, 0f)
    path.lineTo(w, h / 2)
    path.lineTo(w - head, h)
    path.lineTo(w - head, top + shaft)
    path.lineTo(0f, top + shaft)
    path.close()
}
