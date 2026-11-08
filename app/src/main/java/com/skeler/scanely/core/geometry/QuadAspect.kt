package com.skeler.scanely.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The true width/height of the rectangle a photographed quad came from.
 *
 * Edge lengths alone are wrong under perspective (a page tilted away looks short and wide). This
 * follows Zhang & He, "Whiteboard scanning and image enhancement" (2007): with the principal point at
 * the image centre, the quad fixes the camera's focal length (the two rectangle axes must be
 * perpendicular), and the focal length turns the two axis vectors into true lengths.
 */
object QuadAspect {

    /** Phone main cameras sit near 26 mm equivalent: focal ~0.8 of the long side. Used when the quad cannot fix it. */
    private const val DEFAULT_FOCAL_PER_LONG_SIDE = 0.8

    /** Below this the quad's sides are (near) parallel and say nothing about the focal length. */
    private const val PARALLEL_EPS = 1e-9

    /** [q] is TL, TR, BR, BL in pixels of an image [imageWidth] x [imageHeight]. */
    fun estimate(q: Quad, imageWidth: Int, imageHeight: Int): Float {
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        // Zhang's numbering: m1 TL, m2 TR, m3 BL, m4 BR, centred on the principal point
        val m1 = point(q, 0, cx, cy)
        val m2 = point(q, 1, cx, cy)
        val m4 = point(q, 2, cx, cy)
        val m3 = point(q, 3, cx, cy)
        val m1x4 = cross(m1, m4)
        val k2 = dot(m1x4, m3) / dot(cross(m2, m4), m3)
        val k3 = dot(m1x4, m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] } // along the rectangle's width
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] } // along its height
        val zz = n2[2] * n3[2]
        val solved = if (abs(zz) < PARALLEL_EPS) Double.NaN else -(n2[0] * n3[0] + n2[1] * n3[1]) / zz
        val fallback = DEFAULT_FOCAL_PER_LONG_SIDE * max(imageWidth, imageHeight)
        val f2 = if (solved > 0) solved else fallback * fallback
        val w2 = n2[0] * n2[0] + n2[1] * n2[1] + f2 * n2[2] * n2[2]
        val h2 = n3[0] * n3[0] + n3[1] * n3[1] + f2 * n3[2] * n3[2]
        return sqrt(w2 / h2).toFloat()
    }

    private fun point(q: Quad, i: Int, cx: Double, cy: Double) = doubleArrayOf(q.x(i) - cx, q.y(i) - cy, 1.0)

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
