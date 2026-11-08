package com.skeler.scanely.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AUTO for ID cards and passports on ARGB pixels: colour-safe, unlike [DocumentEnhancer].
 *
 * A card has no white paper to flatten against: its ground is a pastel security print and large
 * parts of it are a photo. So the light is modelled as a plane (a card is small and flat: the light
 * across it is a gradient, not a shadow pattern), fitted to the brighter blocks with the photo and
 * dense print voted out; then levels from the card's own darkest and brightest content, with the
 * white point below 255 so the pastel print stays print; then a mild saturation lift.
 */
object CardEnhancer {

    /** Blocks along the long side for the light fit. */
    private const val GRID_LONG = 8

    /** A block's brightness: this percentile of its luma (its ground, not its text). */
    private const val BLOCK_PERCENTILE = 0.9f

    /** Blocks darker than this share of the plane (photo, dense print) are refitted without. */
    private const val KEEP_OF_FIT = 0.9f
    private const val FIT_ROUNDS = 3

    /** Levels: these percentiles of the flattened luma go to [OUT_BLACK] and [OUT_WHITE]. */
    private const val BLACK_PERCENTILE = 0.02f
    private const val WHITE_PERCENTILE = 0.995f
    private const val OUT_BLACK = 0f
    private const val OUT_WHITE = 248f

    private const val SATURATION = 1.15f

    /** A mild mid-tone push: text firms up, the pastel print stays print. */
    private const val GAMMA = 1.35f

    fun enhance(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) { "pixels must be width * height" }
        val light = LightPlane.fit(pixels, width, height, GRID_LONG, BLOCK_PERCENTILE, KEEP_OF_FIT, FIT_ROUNDS)
        val flat = FloatArray(pixels.size * 3)
        val hist = IntArray(LEVELS)
        for (i in pixels.indices) {
            val g = light.gain(i % width, i / width)
            val p = pixels[i]
            val b = i * 3
            flat[b] = (p shr 16 and 0xFF) * g
            flat[b + 1] = (p shr 8 and 0xFF) * g
            flat[b + 2] = (p and 0xFF) * g
            hist[luma(flat[b], flat[b + 1], flat[b + 2]).roundToInt().coerceIn(0, LEVELS - 1)]++
        }
        val black = Levels.percentile(hist, 0, LEVELS, BLACK_PERCENTILE) ?: 0
        val white = max(black + 1, Levels.percentile(hist, 0, LEVELS, WHITE_PERCENTILE) ?: LEVELS - 1)
        val gain = (OUT_WHITE - OUT_BLACK) / (white - black)
        return IntArray(pixels.size) { i ->
            val b = i * 3
            val r = OUT_BLACK + (flat[b] - black) * gain
            val g = OUT_BLACK + (flat[b + 1] - black) * gain
            val bl = OUT_BLACK + (flat[b + 2] - black) * gain
            val l = luma(r, g, bl)
            Levels.toneCurve(
                pixels[i] and ALPHA,
                l + (r - l) * SATURATION,
                l + (g - l) * SATURATION,
                l + (bl - l) * SATURATION,
                GAMMA
            )
        }
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    private const val LEVELS = 256
    private const val ALPHA = -0x1000000 // 0xFF000000
}

/** Light falling across a flat object as a plane a + b x + c y over the image; [gain] evens it out. */
internal class LightPlane private constructor(
    private val a: Float,
    private val b: Float,
    private val c: Float,
    private val peak: Float
) {
    /** Factor that brings pixel (x, y) to the light of the brightest corner. */
    fun gain(x: Int, y: Int): Float = peak / max(a + b * x + c * y, peak * MIN_LIGHT)

    companion object {
        /** The plane never darkens below this share of its peak (a fit pulled off by content). */
        private const val MIN_LIGHT = 0.3f

        /** Fit to the [percentile] luma of a grid of blocks, dropping blocks under [keep] of the fit. */
        fun fit(px: IntArray, w: Int, h: Int, gridLong: Int, percentile: Float, keep: Float, rounds: Int): LightPlane {
            val block = max(1, max(w, h) / gridLong)
            val samples = blockLevels(px, w, h, block, percentile)
            var used = samples
            var plane = solve(used)
            repeat(rounds) {
                val kept = samples.filter { (x, y, v) -> v >= keep * (plane[0] + plane[1] * x + plane[2] * y) }
                if (kept.size >= MIN_BLOCKS) used = kept
                plane = solve(used)
            }
            val corners = listOf(0f to 0f, w.toFloat() to 0f, 0f to h.toFloat(), w.toFloat() to h.toFloat())
            val peak = corners.maxOf { (x, y) -> plane[0] + plane[1] * x + plane[2] * y }
            return LightPlane(plane[0], plane[1], plane[2], max(peak, 1f))
        }

        private const val MIN_BLOCKS = 3

        /** (centre x, centre y, percentile luma) of each [block] x [block] block. */
        private fun blockLevels(px: IntArray, w: Int, h: Int, block: Int, q: Float): List<Triple<Float, Float, Float>> {
            val out = ArrayList<Triple<Float, Float, Float>>()
            val hist = IntArray(LEVELS)
            for (by in 0 until h step block) for (bx in 0 until w step block) {
                hist.fill(0)
                val y1 = minOf(h, by + block)
                val x1 = minOf(w, bx + block)
                for (y in by until y1) for (x in bx until x1) {
                    val p = px[y * w + x]
                    hist[(R * (p shr 16 and 0xFF) + G * (p shr 8 and 0xFF) + B * (p and 0xFF)) / WEIGHTS]++
                }
                val v = Levels.percentile(hist, 0, LEVELS, q) ?: continue
                out += Triple((bx + x1) / 2f, (by + y1) / 2f, v.toFloat())
            }
            return out
        }

        /** Least-squares a, b, c of v = a + b x + c y (Cramer's rule on the normal equations). */
        private fun solve(s: List<Triple<Float, Float, Float>>): FloatArray {
            val m = Array(3) { DoubleArray(3) }
            val r = DoubleArray(3)
            for ((x, y, v) in s) {
                val f = doubleArrayOf(1.0, x.toDouble(), y.toDouble())
                for (i in 0 until 3) {
                    r[i] += f[i] * v
                    for (j in 0 until 3) m[i][j] += f[i] * f[j]
                }
            }
            val d = det(m)
            if (abs(d) < SINGULAR) return floatArrayOf(s.map { it.third }.average().toFloat(), 0f, 0f)
            return FloatArray(3) { k ->
                val mk = Array(3) { i -> DoubleArray(3) { j -> if (j == k) r[i] else m[i][j] } }
                (det(mk) / d).toFloat()
            }
        }

        private fun det(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        private const val SINGULAR = 1e-9
        private const val LEVELS = 256
        private const val R = 299
        private const val G = 587
        private const val B = 114
        private const val WEIGHTS = 1000
    }
}
