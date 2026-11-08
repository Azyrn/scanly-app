package com.skeler.scanely.core.geometry

/** A plane-to-plane projective map; [m] is the row-major 3x3 matrix. */
class Homography private constructor(private val m: DoubleArray) {

    fun map(x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return ((m[0] * x + m[1] * y + m[2]) / w).toFloat() to ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
    }

    /** The map back, via the adjugate (the scale of a homography is free, so no determinant division). */
    fun inverse(): Homography = Homography(
        doubleArrayOf(
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3]
        )
    )

    companion object {
        /** Maps the [w] x [h] rectangle at the origin onto [q], corner for corner (TL, TR, BR, BL). */
        fun rectToQuad(q: Quad, w: Float, h: Float): Homography {
            val f = perspectiveInverse(q, w, h)
            return Homography(DoubleArray(f.size) { f[it].toDouble() })
        }
    }
}
