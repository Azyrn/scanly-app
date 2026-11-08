package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sharpens a coarse quad on a finer image: along each side, finds the strongest brightness step
 * across the side (with the side's polarity), to sub-pixel precision, fits a straight line through
 * those points with outliers dropped, and intersects the four lines.
 */
internal object QuadRefiner {

    /** Points along a side are this far apart (pixels of the fine image). */
    private const val STEP = 3f

    /** A step weaker than this (luma levels over 2 px) is not the side's edge. */
    private const val MIN_STEP = 4f

    /** Points further than this from the fitted line are dropped, then the line is refitted. */
    private const val OUTLIER_PX = 1.5f

    /** A side keeps its coarse line unless this share of its points found the edge. */
    private const val MIN_FOUND = 0.3f

    private const val FIT_ROUNDS = 3

    /** A derivative peak flatter than this has no sub-pixel position. */
    private const val FLAT = 1e-6f
    private const val HALF = 0.5f

    /**
     * [q] is in [img] pixels; [radius] is how far (pixels) across each side to search.
     * [sigma] (pixels) is how far the coarse sides can be off: a step that far from the coarse line
     * counts ~60% of one on it, so a stronger edge a few pixels away (the outer edge of a board
     * frame's lip) does not pull the side off the weaker true one.
     */
    fun refine(img: LumaImage, q: Quad, radius: Int, insideBrighter: BooleanArray, sigma: Float): Quad {
        val lines = Array(4) { s -> fitSide(img, q, s, Search(radius, sigma, insideBrighter[s])) ?: lineOf(q, s) }
        val pts = FloatArray(8)
        for (i in 0 until 4) {
            val c = lines[(i + 3) % 4].intersect(lines[i]) ?: return q
            pts[i * 2] = c.first
            pts[i * 2 + 1] = c.second
        }
        return QuadShape.normalized(pts) ?: q
    }

    /** How one side is searched: [radius] pixels either way, [sigma] closeness weight, polarity. */
    private class Search(val radius: Int, val sigma: Float, brighterInside: Boolean) {
        val sign = if (brighterInside) 1f else -1f
    }

    private fun fitSide(img: LumaImage, q: Quad, s: Int, search: Search): FitLine? {
        val radius = search.radius
        val side = SideSampler.Side(q, s)
        val n = side.count(STEP)
        val xs = ArrayList<Float>(n)
        val ys = ArrayList<Float>(n)
        val profile = FloatArray(2 * radius + 3)
        for (k in 0 until n) {
            val t = side.t(k, n)
            val px = side.x0 + side.dx * t
            val py = side.y0 + side.dy * t
            for (i in profile.indices) {
                val o = (i - radius - 1).toFloat()
                profile[i] = img.sample(px + side.nx * o, py + side.ny * o)
            }
            val off = strongestStep(profile, search) ?: continue
            xs += px + side.nx * off
            ys += py + side.ny * off
        }
        if (xs.size < MIN_FOUND * n) return null
        return FitLine.robust(xs, ys, OUTLIER_PX, FIT_ROUNDS)
    }

    /**
     * Offset (pixels, along the inward normal) of the strongest signed derivative in [profile],
     * weighted by closeness to the coarse line; index i is offset i - radius - 1; parabolic sub-pixel
     * peak on the raw derivative.
     */
    private fun strongestStep(profile: FloatArray, search: Search): Float? {
        val radius = search.radius
        val sign = search.sign
        var bestI = -1
        var bestWeighted = 0f
        for (i in 1 until profile.size - 1) {
            val d = sign * (profile[i + 1] - profile[i - 1])
            val o = (i - radius - 1) / search.sigma
            val weighted = d * exp(-HALF * o * o)
            if (d > MIN_STEP && weighted > bestWeighted) {
                bestWeighted = weighted
                bestI = i
            }
        }
        if (bestI < 0) return null
        val best = sign * (profile[bestI + 1] - profile[bestI - 1])
        // the parabola needs the derivative one step either side, which reads two profile steps out
        if (bestI - 2 < 0 || bestI + 2 >= profile.size) return null
        val a = sign * (profile[bestI] - profile[bestI - 2])
        val c = sign * (profile[bestI + 2] - profile[bestI])
        val den = a - 2 * best + c
        val sub = if (abs(den) < FLAT) 0f else (HALF * (a - c) / den).coerceIn(-HALF, HALF)
        return bestI + sub - radius - 1
    }

    private fun lineOf(q: Quad, s: Int): FitLine {
        val j = (s + 1) % 4
        return FitLine.through(q.x(s), q.y(s), q.x(j), q.y(j))
    }
}

/** A line through ([px], [py]) with unit direction ([dx], [dy]). */
internal class FitLine(val px: Float, val py: Float, val dx: Float, val dy: Float) {

    fun intersect(o: FitLine): Pair<Float, Float>? {
        val det = dx * o.dy - dy * o.dx
        if (abs(det) < PARALLEL) return null
        val t = ((o.px - px) * o.dy - (o.py - py) * o.dx) / det
        return px + dx * t to py + dy * t
    }

    fun distance(x: Float, y: Float) = abs((x - px) * dy - (y - py) * dx)

    companion object {
        private const val PARALLEL = 1e-6f

        fun through(x0: Float, y0: Float, x1: Float, y1: Float): FitLine {
            val a = atan2(y1 - y0, x1 - x0)
            return FitLine(x0, y0, cos(a), sin(a))
        }

        /** Total-least-squares line; points beyond [outlier] are dropped and the fit repeated. */
        fun robust(xs: List<Float>, ys: List<Float>, outlier: Float, rounds: Int): FitLine? {
            var keep = xs.indices.toList()
            var line: FitLine? = null
            repeat(rounds) {
                if (keep.size < 2) return line
                val fit = tls(keep.map { xs[it] }, keep.map { ys[it] })
                line = fit
                keep = keep.filter { fit.distance(xs[it], ys[it]) <= outlier }
            }
            return line
        }

        private fun tls(xs: List<Float>, ys: List<Float>): FitLine {
            val mx = xs.average().toFloat()
            val my = ys.average().toFloat()
            var sxx = 0f
            var syy = 0f
            var sxy = 0f
            for (i in xs.indices) {
                val x = xs[i] - mx
                val y = ys[i] - my
                sxx += x * x
                syy += y * y
                sxy += x * y
            }
            val a = 0.5f * atan2(2 * sxy, sxx - syy)
            return FitLine(mx, my, cos(a), sin(a))
        }
    }
}
