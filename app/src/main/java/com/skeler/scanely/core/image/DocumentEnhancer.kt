package com.skeler.scanely.core.image

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The AUTO filter for documents on ARGB pixels: a clean scan look.
 *
 * 1. **Flat-field** — each channel divided by the page's own brightness under the light it was shot
 *    in (the board estimate of [WhiteboardEnhancer]: block maxima, dark regions filled from the page
 *    around them). Shadows, light fall-off, a warm lamp or a screen's blue cast drop out, and a dim
 *    photo (a backlit screen shot in a dark room) comes up to white.
 * 2. **Levels** — a white point from the paper and a black point from the ink: the paper goes white,
 *    the ink's darkest strokes go to black, and everything between is stretched linearly with the
 *    same gain on all three channels, so coloured content keeps its hue and faint content gets
 *    darker against the paper, never snapped to white.
 */
object DocumentEnhancer {

    /**
     * Blocks along the long side for the page estimate: ~45 px at 2200 px, coarser than the
     * whiteboard's, so faint content as big as a small block (pencil shading, a grey table header)
     * is not taken for paper and flattened white. Real page shadows are softer than that.
     */
    private const val PAGE_GRID_LONG_SIDE = 48

    /** Flat-fielded luma at or above this is paper. */
    private const val PAPER_MIN = 200

    /** The paper's white point: all but this share of the paper (texture, moire, noise) clips white. */
    private const val PAPER_PERCENTILE = 0.15f

    /** Ink is darker than this share of the white point. */
    private const val INK_OF_WHITE = 0.75f

    /** The ink's black point: this share of the ink is darker still and clips black. */
    private const val INK_PERCENTILE = 0.35f

    /** Less ink than this share of the page (a blank sheet): no black point, only the white one. */
    private const val MIN_INK_SHARE = 0.002f

    /**
     * Mid-tones are pushed down along luma^gamma: a photographed stroke is blurred, so most of its
     * pixels are mid-greys; this takes them to near black while the paper stays white.
     */
    private const val INK_GAMMA = 1.8f

    /** The black point never passes this share of the white point (a page of only pale content). */
    private const val MAX_BLACK_OF_WHITE = 0.6f

    fun enhance(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) { "pixels must be width * height" }
        val page = WhiteboardEnhancer.boardEstimate(pixels, width, height, PAGE_GRID_LONG_SIDE)
        val flat = FloatArray(page.size)
        val hist = IntArray(LEVELS)
        for (i in pixels.indices) {
            val p = pixels[i]
            val b = i * 3
            flat[b] = flatField(p shr 16 and 0xFF, page[b])
            flat[b + 1] = flatField(p shr 8 and 0xFF, page[b + 1])
            flat[b + 2] = flatField(p and 0xFF, page[b + 2])
            hist[luma(flat[b], flat[b + 1], flat[b + 2])]++
        }
        val white = Levels.percentile(hist, PAPER_MIN, LEVELS, PAPER_PERCENTILE) ?: LEVELS - 1
        val inkTop = (white * INK_OF_WHITE).toInt()
        val inkCount = (0 until inkTop).sumOf { hist[it] }
        val black = if (inkCount < MIN_INK_SHARE * pixels.size) {
            0
        } else {
            minOf(Levels.percentile(hist, 0, inkTop, INK_PERCENTILE) ?: 0, (white * MAX_BLACK_OF_WHITE).toInt())
        }
        val gain = 255f / max(1, white - black)
        return IntArray(pixels.size) { i ->
            val b = i * 3
            Levels.toneCurve(
                pixels[i] and ALPHA,
                (flat[b] - black) * gain,
                (flat[b + 1] - black) * gain,
                (flat[b + 2] - black) * gain,
                INK_GAMMA
            )
        }
    }

    private fun flatField(value: Int, page: Float) = minOf(255f, value * 255f / max(page, 1f))

    private fun luma(r: Float, g: Float, b: Float) = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

    private const val LEVELS = 256
    private const val ALPHA = -0x1000000 // 0xFF000000
}

/** Percentiles of a 256-level histogram, and the shared tone curve. */
internal object Levels {
    /**
     * Applies luma -> 255 * (luma / 255)^[gamma] and keeps each channel's offset from luma (its
     * colour), then packs r, g, b with [alpha]: grey ink darkens, red ink darkens as much but stays
     * as red, rather than being scaled down towards black.
     */
    fun toneCurve(alpha: Int, r: Float, g: Float, b: Float, gamma: Float): Int {
        val l = LUMA_R * r + LUMA_G * g + LUMA_B * b
        val toned = FULL * Math.pow((l.coerceIn(0f, FULL) / FULL).toDouble(), gamma.toDouble()).toFloat()
        return alpha or (pack(toned + r - l) shl 16) or (pack(toned + g - l) shl 8) or pack(toned + b - l)
    }

    private fun pack(v: Float) = v.roundToInt().coerceIn(0, 255)

    private const val FULL = 255f
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /** The level below which [q] of the counts in [from, to) lie; null when that range is empty. */
    fun percentile(hist: IntArray, from: Int, to: Int, q: Float): Int? {
        val total = (from until to).sumOf { hist[it] }
        if (total == 0) return null
        var acc = 0
        for (v in from until to) {
            acc += hist[v]
            if (acc >= q * total) return v
        }
        return to - 1
    }
}
