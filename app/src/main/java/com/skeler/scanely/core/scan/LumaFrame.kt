package com.skeler.scanely.core.scan

/**
 * 8-bit luma, row by row: a CameraX Y plane (rows [rowStride] bytes apart, padding ignored) or a
 * still photo converted with [fromArgb].
 */
class LumaFrame(val data: ByteArray, val width: Int, val height: Int, val rowStride: Int = width) {

    init {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame geometry" }
        require(data.size >= rowStride * (height - 1) + width) { "data shorter than the frame" }
    }

    /**
     * Mean of each [factor] x [factor] block. Pixel (x, y) is centred at (x + 0.5, y + 0.5) at every
     * level, so a point at u in the result is at u * factor in this frame.
     */
    internal fun downscaled(factor: Int): LumaImage {
        val w = width / factor
        val h = height / factor
        val sums = IntArray(w * h)
        for (y in 0 until h * factor) {
            val row = y * rowStride
            val outRow = (y / factor) * w
            for (x in 0 until w) {
                var s = 0
                val base = row + x * factor
                for (k in 0 until factor) s += data[base + k].toInt() and BYTE
                sums[outRow + x] += s
            }
        }
        val inv = 1f / (factor * factor)
        return LumaImage(w, h, FloatArray(w * h) { sums[it] * inv })
    }

    companion object {
        private const val BYTE = 0xFF
        private const val R_WEIGHT = 77
        private const val G_WEIGHT = 150
        private const val B_WEIGHT = 29
        private const val WEIGHT_SHIFT = 8

        /** Rec. 601 luma of ARGB pixels (8-bit fixed point). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): LumaFrame {
            require(pixels.size == width * height) { "pixels must be width * height" }
            val out = ByteArray(pixels.size) { i ->
                val p = pixels[i]
                val r = p shr 16 and BYTE
                val g = p shr 8 and BYTE
                val b = p and BYTE
                ((r * R_WEIGHT + g * G_WEIGHT + b * B_WEIGHT) shr WEIGHT_SHIFT).toByte()
            }
            return LumaFrame(out, width, height)
        }
    }
}

/** Float luma at one pyramid level; pixel (x, y) covers [x, x + 1) x [y, y + 1). */
internal class LumaImage(val width: Int, val height: Int, val px: FloatArray) {

    /** Bilinear value at continuous point (u, v), clamped to the image. */
    fun sample(u: Float, v: Float): Float {
        val x = (u - HALF).coerceIn(0f, (width - 1).toFloat())
        val y = (v - HALF).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = px[y0 * width + x0] * (1 - fx) + px[y0 * width + x1] * fx
        val bottom = px[y1 * width + x0] * (1 - fx) + px[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    private companion object {
        const val HALF = 0.5f
    }
}
