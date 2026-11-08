package com.skeler.scanely.core.scan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The line x cos(theta) + y sin(theta) = rho, theta in [0, 180) degrees; [votes] is how many edge
 * pixels lie on it.
 */
internal class Line(val cos: Float, val sin: Float, val rho: Float, val votes: Int) {
    /** theta rounded to a degree, for the angle checks between sides. */
    val thetaDeg: Int = Math.floorMod(Math.toDegrees(atan2(sin, cos).toDouble()).roundToInt(), HALF_TURN_DEG)

    /** Smallest angle between the two lines, 0..90 degrees. */
    fun angleTo(o: Line): Int = turn(thetaDeg - o.thetaDeg)

    /** Where the two lines cross, or null when they are (near) parallel. */
    fun intersect(o: Line): Pair<Float, Float>? {
        val det = cos * o.sin - sin * o.cos
        if (abs(det) < PARALLEL_DET) return null
        return (rho * o.sin - sin * o.rho) / det to (cos * o.rho - rho * o.cos) / det
    }

    companion object {
        const val HALF_TURN_DEG = 180
        private const val PARALLEL_DET = 1e-3f

        /** An accumulator cell. */
        fun ofCell(thetaDeg: Int, rho: Float, votes: Int) = Line(
            cos(thetaDeg * PI / HALF_TURN_DEG).toFloat(),
            sin(thetaDeg * PI / HALF_TURN_DEG).toFloat(),
            rho,
            votes
        )

        /** The line along [fit], its normal turned into [0, 180) degrees. */
        fun of(fit: FitLine, votes: Int): Line {
            var nx = -fit.dy
            var ny = fit.dx
            if (ny < 0f || (ny == 0f && nx < 0f)) {
                nx = -nx
                ny = -ny
            }
            return Line(nx, ny, fit.px * nx + fit.py * ny, votes)
        }

        /** Unsigned angle between two undirected orientations, 0..90. */
        fun turn(deltaDeg: Int): Int {
            val d = Math.floorMod(deltaDeg, HALF_TURN_DEG)
            return minOf(d, HALF_TURN_DEG - d)
        }
    }
}

/**
 * Straight lines through an [EdgeMap], strongest first.
 *
 * Each edge pixel votes only for orientations within [ORIENTATION_SLACK] degrees of its own gradient,
 * so text and texture do not drown the long document sides. Lines are taken one at a time (the
 * "progressive" Hough transform): after each peak, the edge pixels on that line take their votes
 * back, so a weaker line a few pixels from a stronger parallel one (a board's edge inside its frame,
 * a screen's edge inside its bezel) still stands out as its own peak.
 */
internal object HoughLines {

    /** An edge pixel's gradient direction is good to a few degrees after the blur. */
    private const val ORIENTATION_SLACK = 3

    /** An edge pixel this close (pixels) to a taken line belongs to it. */
    private const val ON_LINE = 1f

    private val COS = FloatArray(Line.HALF_TURN_DEG) { cos(it * PI / Line.HALF_TURN_DEG).toFloat() }
    private val SIN = FloatArray(Line.HALF_TURN_DEG) { sin(it * PI / Line.HALF_TURN_DEG).toFloat() }

    private class Votes(edges: EdgeMap) {
        val maxRho = hypot(edges.width.toFloat(), edges.height.toFloat()).toInt() + 1
        val rhoBins = 2 * maxRho + 1
        val acc = IntArray(Line.HALF_TURN_DEG * rhoBins)

        /** Edge pixels as (x, y, gradient degrees) with pixel-centre coordinates. */
        val xs: FloatArray
        val ys: FloatArray
        val degs: IntArray
        val live: BooleanArray

        init {
            val idx = edges.edge.indices.filter { edges.edge[it] }
            xs = FloatArray(idx.size) { idx[it] % edges.width + HALF_PIXEL }
            ys = FloatArray(idx.size) { idx[it] / edges.width + HALF_PIXEL }
            degs = IntArray(idx.size) { degrees(edges.gy[idx[it]], edges.gx[idx[it]]) }
            live = BooleanArray(idx.size) { true }
            for (p in idx.indices) cast(p, 1)
        }

        /** Adds ([sign] 1) or withdraws (-1) pixel [p]'s votes. */
        fun cast(p: Int, sign: Int) {
            for (d in degs[p] - ORIENTATION_SLACK..degs[p] + ORIENTATION_SLACK) {
                val t = Math.floorMod(d, Line.HALF_TURN_DEG)
                val rho = (xs[p] * COS[t] + ys[p] * SIN[t]).roundToInt()
                acc[t * rhoBins + rho + maxRho] += sign
            }
        }

        fun strongestCell(): Int {
            var best = 0
            for (i in acc.indices) if (acc[i] > acc[best]) best = i
            return best
        }

        /** Withdraws the votes of every live pixel on [line]; returns those pixels as (xs, ys). */
        fun take(line: Line): Pair<List<Float>, List<Float>> {
            val tx = ArrayList<Float>()
            val ty = ArrayList<Float>()
            for (p in xs.indices) {
                if (live[p] && isOn(p, line)) {
                    live[p] = false
                    cast(p, -1)
                    tx += xs[p]
                    ty += ys[p]
                }
            }
            return tx to ty
        }

        private fun isOn(p: Int, line: Line) = Line.turn(degs[p] - line.thetaDeg) <= ORIENTATION_SLACK &&
            abs(xs[p] * line.cos + ys[p] * line.sin - line.rho) <= ON_LINE
    }

    fun find(edges: EdgeMap, maxLines: Int, minVotes: Int): List<Line> {
        val votes = Votes(edges)
        val out = ArrayList<Line>(maxLines)
        while (out.size < maxLines) {
            val cell = votes.strongestCell()
            val count = votes.acc[cell]
            if (count < minVotes) break
            val coarse = Line.ofCell(cell / votes.rhoBins, (cell % votes.rhoBins - votes.maxRho).toFloat(), count)
            val (px, py) = votes.take(coarse)
            if (px.isEmpty()) votes.acc[cell] = 0 // rounding left this cell's voters off the line
            // the cell is good to a degree; the pixels on it fix the line to a fraction of a pixel
            out += FitLine.robust(px, py, FIT_OUTLIER, FIT_ROUNDS)?.let { Line.of(it, count) } ?: coarse
        }
        return out
    }

    private fun degrees(gy: Float, gx: Float) = Math.toDegrees(atan2(gy, gx).toDouble()).roundToInt()

    private const val HALF_PIXEL = 0.5f
    private const val FIT_OUTLIER = 1f
    private const val FIT_ROUNDS = 2
}
