package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Homography
import com.skeler.scanely.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The one definition of every scan-quality metric. Our pipeline and GMS outputs are both scored
 * here, from (output image, quad the output was cut from, fixture).
 */
object ScanMetrics {

    /** Output luma at or above this is clipped white. */
    const val CLIPPED_WHITE = 250

    /** An ink pixel whose output (darkest of its 3x3 neighbourhood) is at least this light is lost. */
    const val INK_LOST_LUMA = 235

    class CornerError(val meanPx: Float, val maxPx: Float, val meanPercent: Float, val maxPercent: Float)

    class Tone(val rmsContrast: Float, val p5: Int, val p95: Int, val clippedWhitePercent: Float) {
        val spread get() = p95 - p5
    }

    /** Marker colour kept: share of strongly coloured truth ink whose output hue is within 30 deg. */
    class Hue(val keptPercent: Float, val meanErrorDeg: Float)

    fun luma(p: Int): Int = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /**
     * Corner distance from [found] to [truth], best of the 4 cyclic corner orders (a scanner that hands
     * the page back rotated has not mis-placed its corners); % is of the truth quad's mean diagonal.
     */
    fun cornerError(found: Quad, truth: Quad): CornerError {
        val diag = (hypot(truth.x(2) - truth.x(0), truth.y(2) - truth.y(0)) +
            hypot(truth.x(3) - truth.x(1), truth.y(3) - truth.y(1))) / 2
        val best = (0 until 4).map { shift ->
            FloatArray(4) { i -> hypot(found.x((i + shift) % 4) - truth.x(i), found.y((i + shift) % 4) - truth.y(i)) }
        }.minBy { it.sum() }
        val mean = best.average().toFloat()
        val max = best.max()
        return CornerError(mean, max, 100 * mean / diag, 100 * max / diag)
    }

    fun tone(img: Argb): Tone {
        val hist = IntArray(256)
        var sum = 0.0
        var sq = 0.0
        for (p in img.pixels) {
            val l = luma(p)
            hist[l]++
            sum += l
            sq += l.toDouble() * l
        }
        val n = img.pixels.size
        val mean = sum / n
        val rms = sqrt(maxOf(0.0, sq / n - mean * mean)).toFloat()
        val clipped = (CLIPPED_WHITE..255).sumOf { hist[it] } * 100f / n
        return Tone(rms, percentile(hist, n, 0.05), percentile(hist, n, 0.95), clipped)
    }

    private fun percentile(hist: IntArray, n: Int, q: Double): Int {
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= q * n) return v
        }
        return 255
    }

    /**
     * Maps each truth pixel to where it landed in [output]: truth -> photo through the fixture's true
     * corners, photo -> output through the quad the output was cut from. Geometry error therefore
     * counts as loss only where it really misplaces content.
     */
    private class TruthToOutput(fx: ScanFixture, truthW: Int, truthH: Int, outQuad: Quad, output: Argb) {
        private val toPhoto = Homography.rectToQuad(fx.corners, truthW.toFloat(), truthH.toFloat())
        private val toOutput = Homography.rectToQuad(outQuad, output.width.toFloat(), output.height.toFloat()).inverse()

        fun map(u: Int, v: Int): Pair<Float, Float> {
            val (px, py) = toPhoto.map(u + 0.5f, v + 0.5f)
            return toOutput.map(px, py)
        }
    }

    /** % of truth ink pixels whose output is white (or cut off). Null without a truth. */
    fun inkLostPercent(fx: ScanFixture, output: Argb, outQuad: Quad): Float? {
        val truth = fx.truth() ?: return null
        val ink = fx.ink() ?: return null
        val map = TruthToOutput(fx, truth.width, truth.height, outQuad, output)
        var total = 0
        var lost = 0
        for (v in 0 until truth.height) for (u in 0 until truth.width) {
            if (!ink[v * truth.width + u]) continue
            total++
            val (x, y) = map.map(u, v)
            if (darkestAround(output, x, y) >= INK_LOST_LUMA) lost++
        }
        return if (total == 0) null else 100f * lost / total
    }

    /** Darkest luma in the 3x3 output pixels around (x, y); 255 when (x, y) is outside the output. */
    private fun darkestAround(img: Argb, x: Float, y: Float): Int {
        val cx = floor(x).toInt()
        val cy = floor(y).toInt()
        if (cx !in 0 until img.width || cy !in 0 until img.height) return 255
        var m = 255
        for (yy in maxOf(0, cy - 1)..minOf(img.height - 1, cy + 1)) {
            for (xx in maxOf(0, cx - 1)..minOf(img.width - 1, cx + 1)) m = minOf(m, luma(img.pixels[yy * img.width + xx]))
        }
        return m
    }

    /**
     * Text/background separation (reported, not gated): median output luma where the truth is ground
     * minus median output luma where it is ink, each truth pixel read where it landed in [output].
     */
    fun separation(fx: ScanFixture, output: Argb, outQuad: Quad): Int? {
        val truth = fx.truth() ?: return null
        val ink = fx.ink() ?: return null
        val map = TruthToOutput(fx, truth.width, truth.height, outQuad, output)
        val ground = IntArray(256)
        val inked = IntArray(256)
        for (v in 0 until truth.height) for (u in 0 until truth.width) {
            val (x, y) = map.map(u, v)
            val ox = floor(x).toInt()
            val oy = floor(y).toInt()
            if (ox !in 0 until output.width || oy !in 0 until output.height) continue
            val l = luma(output.pixels[oy * output.width + ox])
            if (ink[v * truth.width + u]) inked[l]++ else ground[l]++
        }
        if (ground.sum() == 0 || inked.sum() == 0) return null
        return percentile(ground, ground.sum(), 0.5) - percentile(inked, inked.sum(), 0.5)
    }

    /** Hue kept on strongly coloured truth ink (chroma >= 60); null when the truth has none. */
    fun hue(fx: ScanFixture, output: Argb, outQuad: Quad): Hue? {
        val truth = fx.truth() ?: return null
        val ink = fx.ink() ?: return null
        val map = TruthToOutput(fx, truth.width, truth.height, outQuad, output)
        var n = 0
        var kept = 0
        var errSum = 0.0
        for (v in 0 until truth.height) for (u in 0 until truth.width) {
            val i = v * truth.width + u
            val t = truth.pixels[i]
            if (!ink[i] || chroma(t) < 60) continue
            val (x, y) = map.map(u, v)
            val ox = floor(x).toInt()
            val oy = floor(y).toInt()
            if (ox !in 0 until output.width || oy !in 0 until output.height) continue
            val o = output.pixels[oy * output.width + ox]
            val d = if (chroma(o) < 25) 180.0 else hueDiff(hueOf(t), hueOf(o))
            n++
            errSum += d
            if (d < 30) kept++
        }
        return if (n == 0) null else Hue(100f * kept / n, (errSum / n).toFloat())
    }

    private fun chroma(p: Int): Int {
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    private fun hueOf(p: Int): Double {
        val r = (p shr 16 and 0xFF).toDouble()
        val g = (p shr 8 and 0xFF).toDouble()
        val b = (p and 0xFF).toDouble()
        return (Math.toDegrees(atan2(sqrt(3.0) * (g - b), 2 * r - g - b)) + 360) % 360
    }

    private fun hueDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)
}
