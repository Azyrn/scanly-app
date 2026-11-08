package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Walks one side of a quad (corner i to corner i + 1). Samples skip the ends: rounded card corners
 * and page corners lifted by a shadow are not straight-edge evidence.
 */
internal object SideSampler {

    /** Fraction of the side skipped at each end. */
    const val END_SKIP = 0.08f

    /** Each end of a side is also judged on its own, over this share of its samples. */
    private const val END_STRETCH = 0.15f
    private const val END_SLACK = 0.5f

    /** Sample spacing in pixels. */
    private const val STEP = 1.5f

    /** Edge pixels whose gradient is within ~20 degrees of the side's normal support it. */
    private const val MIN_NORMAL_COS = 0.94f

    /**
     * Inside and outside brightness are read at these fractions of the quad's shortest side from the
     * edge (at least a pixel). A board's frame, a bezel, a title bar or a shadow is a band whose
     * width scales with the object, not with the image: a document side's inside stays brighter than
     * its outside at every pair of these distances. A frame's outer edge fails that (the wall beyond
     * is brighter than the frame), a screen's edge passes it even when a dark title bar sits on it
     * (the bezel is darker still), which is what tells them from the edges next to them.
     */
    private val POLARITY_FRACTIONS = floatArrayOf(0.01f, 0.02f, 0.03f)

    /** Unit inward normal of side [s] (towards the quad's centre) and its start/end points. */
    class Side(q: Quad, s: Int) {
        private val j = (s + 1) % 4
        val x0 = q.x(s)
        val y0 = q.y(s)
        val dx = q.x(j) - x0
        val dy = q.y(j) - y0
        val length = hypot(dx, dy)
        val nx: Float
        val ny: Float

        init {
            val cx = (0 until 4).sumOf { q.x(it).toDouble() }.toFloat() / 4
            val cy = (0 until 4).sumOf { q.y(it).toDouble() }.toFloat() / 4
            var ux = -dy / length
            var uy = dx / length
            if ((cx - x0) * ux + (cy - y0) * uy < 0) {
                ux = -ux
                uy = -uy
            }
            nx = ux
            ny = uy
        }

        fun count(step: Float) = maxOf(2, ((1 - 2 * END_SKIP) * length / step).toInt())

        fun t(k: Int, n: Int) = END_SKIP + (1 - 2 * END_SKIP) * k / (n - 1)
    }

    /**
     * Share of samples along side [s] with a matching edge pixel within one pixel across the side,
     * capped by its emptier end stretch ([END_STRETCH] of the samples) plus [END_SLACK]. A line running
     * on past a real corner (a screen's side carried on through the bezel to the keyboard) has an end
     * with no edge at all; a real side's end may be broken up (title-bar text, icons) but not empty.
     */
    fun support(edges: EdgeMap, q: Quad, s: Int): Float {
        val side = Side(q, s)
        val n = side.count(STEP)
        val hit = BooleanArray(n) { k ->
            val t = side.t(k, n)
            val px = side.x0 + side.dx * t
            val py = side.y0 + side.dy * t
            hasEdge(edges, px, py, side) ||
                hasEdge(edges, px + side.nx, py + side.ny, side) ||
                hasEdge(edges, px - side.nx, py - side.ny, side)
        }
        val end = maxOf(1, (n * END_STRETCH).toInt())
        fun share(from: Int, to: Int) = (from until to).count { hit[it] }.toFloat() / (to - from)
        return minOf(share(0, n), END_SLACK + minOf(share(0, end), share(n - end, n)))
    }

    private fun hasEdge(edges: EdgeMap, x: Float, y: Float, side: Side): Boolean {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        if (ix !in 0 until edges.width || iy !in 0 until edges.height) return false
        val i = iy * edges.width + ix
        if (!edges.edge[i]) return false
        val gx = edges.gx[i]
        val gy = edges.gy[i]
        return abs(gx * side.nx + gy * side.ny) >= MIN_NORMAL_COS * sqrt(gx * gx + gy * gy)
    }

    /** Darkest inside band mean minus brightest outside band mean of side [s], over [POLARITY_FRACTIONS]. */
    fun insideMinusOutside(img: LumaImage, q: Quad, s: Int): Float {
        val side = Side(q, s)
        val n = side.count(STEP * 2)
        val shortest = (0 until 4).minOf { QuadShape.side(q, it) }
        var inside = Float.MAX_VALUE
        var outside = -Float.MAX_VALUE
        for (f in POLARITY_FRACTIONS) {
            val d = maxOf(1f, f * shortest)
            var sumIn = 0f
            var sumOut = 0f
            for (k in 0 until n) {
                val t = side.t(k, n)
                val px = side.x0 + side.dx * t
                val py = side.y0 + side.dy * t
                sumIn += img.sample(px + side.nx * d, py + side.ny * d)
                sumOut += img.sample(px - side.nx * d, py - side.ny * d)
            }
            inside = minOf(inside, sumIn / n)
            outside = maxOf(outside, sumOut / n)
        }
        return inside - outside
    }
}
