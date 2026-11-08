package com.skeler.scanely.core.scan

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.skeler.scanely.core.geometry.Quad
import com.skeler.scanely.core.geometry.QuadAspect
import com.skeler.scanely.core.geometry.perspectiveInverse
import com.skeler.scanely.core.geometry.sampleBilinear
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Output size of a flattened page. */
data class PageSize(val width: Int, val height: Int)

/** A flattened page's ARGB pixels. */
class FlatPage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Flattens the part of a photo inside a quad into an upright page.
 *
 * The page's shape is the photographed rectangle's true shape ([QuadAspect], perspective-aware), or a
 * forced one (ID-1 / ID-3) turned to match the card's orientation in the photo. Its resolution keeps
 * the quad's longest side, capped at [MAX_LONG_SIDE] like every other page the app makes.
 */
object PageWarp {

    const val MAX_LONG_SIDE = 2200

    /** [forcedAspect] is width/height of the landscape shape, e.g. IdDocumentKind.aspect. */
    fun pageSize(
        quad: Quad,
        imageWidth: Int,
        imageHeight: Int,
        forcedAspect: Float? = null,
        maxLongSide: Int = MAX_LONG_SIDE
    ): PageSize {
        val estimated = QuadAspect.estimate(quad, imageWidth, imageHeight)
        val aspect = when {
            forcedAspect == null -> estimated
            estimated >= 1f -> forcedAspect
            else -> 1f / forcedAspect
        }
        val horizontal = max(side(quad, 0, 1), side(quad, 3, 2))
        val vertical = max(side(quad, 0, 3), side(quad, 1, 2))
        // keep the detail the photo has along whichever axis it has more of
        var w = max(horizontal, vertical * aspect)
        var h = w / aspect
        val scale = minOf(1f, maxLongSide / max(w, h))
        w *= scale
        h *= scale
        return PageSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    fun warp(pixels: IntArray, width: Int, height: Int, quad: Quad, forcedAspect: Float? = null): FlatPage {
        require(pixels.size == width * height) { "pixels must be width * height" }
        val size = pageSize(quad, width, height, forcedAspect)
        val m = perspectiveInverse(quad, size.width.toFloat(), size.height.toFloat())
        val out = IntArray(size.width * size.height)
        for (y in 0 until size.height) {
            val fy = y + HALF
            for (x in 0 until size.width) {
                val fx = x + HALF
                val den = m[6] * fx + m[7] * fy + m[8]
                val sx = (m[0] * fx + m[1] * fy + m[2]) / den
                val sy = (m[3] * fx + m[4] * fy + m[5]) / den
                // sampleBilinear indexes pixel centres at integers; the quad is in pixel-edge units
                out[y * size.width + x] = sampleBilinear(pixels, width, height, sx - HALF, sy - HALF)
            }
        }
        return FlatPage(out, size.width, size.height)
    }

    fun warp(source: Bitmap, quad: Quad, forcedAspect: Float? = null): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        val page = warp(px, w, h, quad, forcedAspect)
        return createBitmap(page.width, page.height).apply {
            setPixels(page.pixels, 0, page.width, 0, 0, page.width, page.height)
        }
    }

    private fun side(q: Quad, a: Int, b: Int) = hypot(q.x(b) - q.x(a), q.y(b) - q.y(a))

    private const val HALF = 0.5f
}
