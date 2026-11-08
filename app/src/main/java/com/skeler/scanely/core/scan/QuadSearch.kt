package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

/** A candidate document outline in work-image pixels, with what the evidence says about it. */
internal class Candidate(val quad: Quad, val confidence: Float, val score: Float, val insideBrighter: BooleanArray)

/**
 * Builds quads from every 4 of the strongest lines (two pairs of roughly opposite sides) and keeps
 * the best one. A side counts as seen where edge pixels with a matching gradient lie along it; a
 * document is usually brighter than what surrounds it (paper on a desk, a lit screen in its bezel,
 * a board in its frame), which is what tells a laptop's screen from its lid.
 */
internal object QuadSearch {

    /** Opposite sides of a page photographed at a steep angle still converge by less than this. */
    private const val MAX_OPPOSITE_DEG = 50

    /** Adjacent sides of a page meet at more than this even in strong perspective. */
    private const val MIN_ADJACENT_DEG = 30

    /** Smallest document: an ID card at arm's length still covers ~5% of the frame. */
    private const val MIN_AREA_FRACTION = 0.04f

    /** Corners may sit this far (fraction of the short side) outside the frame. */
    private const val OUTSIDE_TOLERANCE = 0.02f

    /** Shortest side, as a fraction of the frame's short side. */
    private const val MIN_SIDE_FRACTION = 0.12f

    /** Weight of size in the score: enough to prefer a page over a table on it, not a lid over its screen. */
    private const val AREA_EXPONENT = 0.25

    fun best(edges: EdgeMap, lines: List<Line>): Candidate? {
        val sides = SideEvidence(edges)
        val all = ArrayList<Candidate>()
        for (four in quadruples(lines.size)) {
            // the three ways to split four lines into two pairs of opposite sides
            for (split in SPLITS) {
                val quad = assemble(Sides(lines, four, split), edges.width, edges.height) ?: continue
                sides.judge(quad, edges.width * edges.height)?.let { all += it }
            }
        }
        val best = all.maxByOrNull { it.score } ?: return null
        return framedInside(best, all) ?: best
    }

    /**
     * The best quad nested in [outer] by a thin band of even width on all four sides, as seen as
     * [outer] itself: [outer] is then a frame (a whiteboard's aluminium edge and its lip) and this is
     * the surface in it. A screen's dark title bar moves one side only, so it is no frame.
     */
    private fun framedInside(outer: Candidate, all: List<Candidate>): Candidate? {
        val short = (0 until 4).minOf { QuadShape.side(outer.quad, it) }
        return all.filter { c ->
            c !== outer && c.confidence >= FRAME_CONFIDENCE * outer.confidence &&
                (0 until 4).all { s ->
                    QuadShape.inset(outer.quad, c.quad, s) in MIN_INSET_PX..MAX_INSET_OF_SHORT * short
                }
        }.maxByOrNull { it.score }
    }

    /** A framed surface is seen about as well as its frame. */
    private const val FRAME_CONFIDENCE = 0.9f

    /** Every side of a framed surface sits at least this far (pixels) inside the frame's... */
    private const val MIN_INSET_PX = 1f

    /** ...and at most this share of the frame's short side (a frame is a band, not a margin). */
    private const val MAX_INSET_OF_SHORT = 0.05f

    /** Every 4 of [n] line indices, ascending. */
    private fun quadruples(n: Int) = sequence {
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            yield(intArrayOf(a, b, c, d))
        }
    }

    /** Positions (in a quadruple) of p1, p2 (opposite) and q1, q2 (opposite) for each split. */
    private val SPLITS = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(0, 2, 1, 3), intArrayOf(0, 3, 1, 2))

    /** Four lines as two pairs of opposite sides: p1 / p2 and q1 / q2. */
    private class Sides(lines: List<Line>, four: IntArray, split: IntArray) {
        val p1 = lines[four[split[0]]]
        val p2 = lines[four[split[1]]]
        val q1 = lines[four[split[2]]]
        val q2 = lines[four[split[3]]]

        /** Opposite sides roughly parallel, every adjacent pair meeting at a real corner. */
        fun pageLike(): Boolean {
            val opposite = p1.angleTo(p2) <= MAX_OPPOSITE_DEG && q1.angleTo(q2) <= MAX_OPPOSITE_DEG
            val adjacent = minOf(minOf(p1.angleTo(q1), p1.angleTo(q2)), minOf(p2.angleTo(q1), p2.angleTo(q2)))
            return opposite && adjacent >= MIN_ADJACENT_DEG
        }
    }

    /** Corners of the quad with sides p1, q1, p2, q2 in turn, or null if it cannot be a document. */
    private fun assemble(s: Sides, w: Int, h: Int): Quad? {
        if (!s.pageLike()) return null
        val corners = listOf(s.p1.intersect(s.q1), s.q1.intersect(s.p2), s.p2.intersect(s.q2), s.q2.intersect(s.p1))
        val pts = FloatArray(8)
        corners.forEachIndexed { i, c ->
            if (c == null) return null
            pts[i * 2] = c.first
            pts[i * 2 + 1] = c.second
        }
        return QuadShape.normalized(pts)?.takeIf { plausible(it, w, h) }
    }

    private fun plausible(q: Quad, w: Int, h: Int): Boolean {
        val short = minOf(w, h)
        val tol = OUTSIDE_TOLERANCE * short
        val inFrame = (0 until 4).all { q.x(it) in -tol..w + tol && q.y(it) in -tol..h + tol }
        val minSide = (0 until 4).minOf { QuadShape.side(q, it) }
        return inFrame && minSide >= MIN_SIDE_FRACTION * short && QuadShape.area(q) >= MIN_AREA_FRACTION * w * h
    }

    /** Scores quads against the edge map. */
    private class SideEvidence(private val edges: EdgeMap) {

        fun judge(q: Quad, frameArea: Int): Candidate? {
            val support = FloatArray(4)
            val brighter = BooleanArray(4)
            var lighter = 0f
            for (s in 0 until 4) {
                val seen = SideSampler.support(edges, q, s)
                if (seen < MIN_SIDE_SUPPORT) return null
                // a side seen along most of its length is simply seen: more coverage is not more
                // "document", and a faint true edge (a screen's dark title bar against its bezel) must
                // not lose to a crisp inner one
                support[s] = minOf(1f, seen / FULL_SIDE_SUPPORT)
                val c = SideSampler.insideMinusOutside(edges.blurred, q, s)
                brighter[s] = c > 0f
                lighter += (c / FULL_POLARITY).coerceIn(0f, 1f)
            }
            val mean = support.average().toFloat()
            val polarity = POLARITY_FLOOR + (1 - POLARITY_FLOOR) * lighter / 4f
            val confidence = mean * sqrt(support.min()) * polarity
            val areaFactor = Math.pow((QuadShape.area(q) / frameArea).toDouble(), AREA_EXPONENT).toFloat()
            return Candidate(q, confidence, confidence * areaFactor, brighter)
        }
    }

    /** A side with edge evidence along this share of its length counts as fully seen. */
    private const val FULL_SIDE_SUPPORT = 0.9f

    /** A side whose edge evidence covers less than this is not a seen side. */
    private const val MIN_SIDE_SUPPORT = 0.3f

    /**
     * Inside brighter than outside by this many levels is fully document-like; less counts in
     * proportion. Graded, because a frame's shadow can make the outside of a board's frame a few
     * levels darker than the frame itself.
     */
    private const val FULL_POLARITY = 16f

    /** Weight left for a quad that is darker than its surroundings on every side. */
    private const val POLARITY_FLOOR = 0.55f
}

/** Shape helpers for corner arrays. */
internal object QuadShape {

    /** Side [i] runs from corner i to corner i + 1. */
    fun side(q: Quad, i: Int): Float {
        val j = (i + 1) % 4
        return hypot(q.x(j) - q.x(i), q.y(j) - q.y(i))
    }

    /** How far (pixels, inwards) side [s] of [inner] lies from side [s] of [outer], at its middle. */
    fun inset(outer: Quad, inner: Quad, s: Int): Float {
        val j = (s + 1) % 4
        val mx = (inner.x(s) + inner.x(j)) / 2
        val my = (inner.y(s) + inner.y(j)) / 2
        val dx = outer.x(j) - outer.x(s)
        val dy = outer.y(j) - outer.y(s)
        // TL, TR, BR, BL on screen: the inside is to the right of each side's direction
        return (dx * (my - outer.y(s)) - dy * (mx - outer.x(s))) / hypot(dx, dy)
    }

    fun area(q: Quad): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q.x(i) * q.y(j) - q.x(j) * q.y(i)
        }
        return abs(s) / 2
    }

    /**
     * Four corners in cyclic order -> convex quad ordered TL, TR, BR, BL (clockwise on screen, starting
     * at the corner nearest the top-left), or null if they do not form a convex quad.
     */
    fun normalized(pts: FloatArray): Quad? {
        var sign = 0
        for (i in 0 until 4) {
            val a = (i + 1) % 4
            val b = (i + 2) % 4
            val cross = (pts[a * 2] - pts[i * 2]) * (pts[b * 2 + 1] - pts[a * 2 + 1]) -
                (pts[a * 2 + 1] - pts[i * 2 + 1]) * (pts[b * 2] - pts[a * 2])
            val s = sign(cross).toInt()
            if (s == 0 || (sign != 0 && s != sign)) return null
            sign = s
        }
        // y points down: a positive cross product turns clockwise on screen
        val order = if (sign > 0) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 3, 2, 1)
        val start = (0 until 4).minBy { pts[order[it] * 2] + pts[order[it] * 2 + 1] }
        return Quad(FloatArray(pts.size) { k -> pts[order[(start + k / 2) % order.size] * 2 + k % 2] })
    }
}
