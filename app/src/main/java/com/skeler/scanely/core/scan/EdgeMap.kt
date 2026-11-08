package com.skeler.scanely.core.scan

import kotlin.math.abs

/**
 * Canny-style edges of a small luma image: binomial blur, Sobel gradient, non-maximum suppression
 * across the gradient. [blurred] is kept for the inside/outside brightness checks.
 */
internal class EdgeMap(val blurred: LumaImage, val gx: FloatArray, val gy: FloatArray, val edge: BooleanArray) {

    val width get() = blurred.width
    val height get() = blurred.height

    companion object {
        /**
         * Smallest Sobel magnitude kept as an edge: a 6-level luma step after the blur (Sobel on the
         * blurred step peaks at 2.5x the step). Camera noise at preview size stays under ~3 levels.
         */
        const val MIN_MAGNITUDE = 15f

        /** tan(67.5 deg): beyond it the gradient is treated as horizontal/vertical, else diagonal. */
        private const val TAN_67_5 = 2.4142f

        fun of(src: LumaImage): EdgeMap {
            val blurred = blur(src)
            val w = src.width
            val h = src.height
            val gx = FloatArray(w * h)
            val gy = FloatArray(w * h)
            val p = blurred.px
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                gx[i] = (p[i - w + 1] + 2 * p[i + 1] + p[i + w + 1]) - (p[i - w - 1] + 2 * p[i - 1] + p[i + w - 1])
                gy[i] = (p[i + w - 1] + 2 * p[i + w] + p[i + w + 1]) - (p[i - w - 1] + 2 * p[i - w] + p[i - w + 1])
            }
            return EdgeMap(blurred, gx, gy, suppress(gx, gy, w, h))
        }

        /** Keeps a pixel only where its magnitude peaks along the gradient direction. */
        private fun suppress(gx: FloatArray, gy: FloatArray, w: Int, h: Int): BooleanArray {
            val edge = BooleanArray(w * h)
            val min2 = MIN_MAGNITUDE * MIN_MAGNITUDE
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val m = gx[i] * gx[i] + gy[i] * gy[i]
                if (m < min2) continue
                val step = neighbourStep(gx[i], gy[i], w)
                val a = gx[i + step] * gx[i + step] + gy[i + step] * gy[i + step]
                val b = gx[i - step] * gx[i - step] + gy[i - step] * gy[i - step]
                edge[i] = m >= a && m > b
            }
            return edge
        }

        /** Index offset to the neighbour along the gradient (one of 4 quantised directions). */
        private fun neighbourStep(gx: Float, gy: Float, w: Int): Int {
            val ax = abs(gx)
            val ay = abs(gy)
            return when {
                ax > TAN_67_5 * ay -> 1
                ay > TAN_67_5 * ax -> w
                (gx > 0) == (gy > 0) -> w + 1
                else -> w - 1
            }
        }

        /** Separable 1-4-6-4-1 binomial blur, edges clamped. */
        private fun blur(src: LumaImage): LumaImage {
            val w = src.width
            val h = src.height
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) tmp[y * w + x] = taps(src.px, y * w, x, 1, w)
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = taps(tmp, x, y, w, h)
            return LumaImage(w, h, out)
        }

        /** Binomial weights 1-4-6-4-1, summing to 16. */
        private val BINOMIAL = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)

        /** [BINOMIAL] around index [i] of a line starting at [base] with element [step], [n] long. */
        private fun taps(a: FloatArray, base: Int, i: Int, step: Int, n: Int): Float {
            var sum = 0f
            for (k in BINOMIAL.indices) {
                val at = (i + k - BINOMIAL.size / 2).coerceIn(0, n - 1)
                sum += BINOMIAL[k] * a[base + at * step]
            }
            return sum
        }
    }
}
