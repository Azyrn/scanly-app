package com.skeler.scanely.core.geometry

import kotlin.math.roundToInt

internal fun sampleBilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
    val cx = x.coerceIn(0f, (w - 1).toFloat())
    val cy = y.coerceIn(0f, (h - 1).toFloat())
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = minOf(x0 + 1, w - 1)
    val y1 = minOf(y0 + 1, h - 1)
    val fx = cx - x0
    val fy = cy - y0

    val p00 = px[y0 * w + x0]
    val p01 = px[y0 * w + x1]
    val p10 = px[y1 * w + x0]
    val p11 = px[y1 * w + x1]

    fun ch(shift: Int): Int {
        val a = (p00 shr shift and 0xFF) * (1 - fx) + (p01 shr shift and 0xFF) * fx
        val b = (p10 shr shift and 0xFF) * (1 - fx) + (p11 shr shift and 0xFF) * fx
        return (a * (1 - fy) + b * fy).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Homography mapping dst rect -> src quad (used to inverse-sample). */
internal fun perspectiveInverse(q: Quad, w: Float, h: Float): FloatArray {
    val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    val a = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
        val dx = dst[i * 2].toDouble()
        val dy = dst[i * 2 + 1].toDouble()
        val sx = q.x(i).toDouble()
        val sy = q.y(i).toDouble()
        a[i * 2] = doubleArrayOf(dx, dy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dy * sx, sx)
        a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, dx, dy, 1.0, -dx * sy, -dy * sy, sy)
    }
    val sol = solve8(a)
    return floatArrayOf(
        sol[0].toFloat(), sol[1].toFloat(), sol[2].toFloat(),
        sol[3].toFloat(), sol[4].toFloat(), sol[5].toFloat(),
        sol[6].toFloat(), sol[7].toFloat(), 1f
    )
}

/** Gauss-Jordan with partial pivoting; rows are [8][9] augmented. */
private fun solve8(a: Array<DoubleArray>): DoubleArray {
    for (col in 0 until 8) {
        var pivot = col
        for (r in col + 1 until 8) {
            if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
        }
        val tmp = a[col]
        a[col] = a[pivot]
        a[pivot] = tmp
        val p = a[col][col]
        if (kotlin.math.abs(p) >= 1e-12) eliminate(a, col, p)
    }
    return DoubleArray(8) { a[it][8] }
}

/** Scales pivot row [col] by 1/[p], then clears column [col] from every other row. */
private fun eliminate(a: Array<DoubleArray>, col: Int, p: Double) {
    for (c in col until 9) a[col][c] /= p
    for (r in 0 until 8) {
        val f = a[r][col]
        if (r != col && f != 0.0) {
            for (c in col until 9) a[r][c] -= f * a[col][c]
        }
    }
}
