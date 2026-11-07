package com.skeler.scanely.core.ocr.paddle

import kotlin.math.ceil

// A text line's region fills its minimum-area rectangle almost completely when the line is
// straight; a line bent by a page curling into the spine fills the rectangle around its bend far
// less. Measured on 50 phone photos at the 960 px detection size: on every curled page 17-60% of
// the long lines fall below this fill, on every flat page at most 11%.
private const val CURL_FILL = 0.8f

// Only lines long enough to bend count; a short word or a page number is a rectangle either way.
private const val CURL_MIN_ASPECT = 8f
private const val CURL_MIN_PIXELS = 50

/** Detected quads, plus the share of long lines whose region does not fill a straight box. */
class Detection(val quads: List<Quad>, val curledShare: Float)

/** Tallies, per text region, whether a long line is curled (see [CURL_FILL]). */
class CurlCounter {
    private var longLines = 0
    private var curled = 0

    val share: Float get() = if (longLines == 0) 0f else curled.toFloat() / longLines

    fun add(pixels: Int, rect: RotatedRect) {
        val longSide = maxOf(rect.width, rect.height)
        if (pixels < CURL_MIN_PIXELS || longSide < rect.shortSide * CURL_MIN_ASPECT) return
        longLines++
        if (pixels < CURL_FILL * rect.width * rect.height) curled++
    }
}

/** DBNet probability map to text quads. Params from PP-OCRv6 inference.yml. */
class DbPostProcessor(
    private val thresh: Float = 0.2f,
    private val boxThresh: Float = 0.45f,
    private val unclipRatio: Float = 1.4f,
    private val minSize: Float = 3f,
    private val maxCandidates: Int = 3000
) {

    fun extractDetection(prob: FloatArray, w: Int, h: Int, scaleX: Float, scaleY: Float): Detection {
        val curl = CurlCounter()
        return Detection(extract(prob, w, h, scaleX, scaleY, curl), curl.share)
    }

    /** Text quads in source pixels; [curl], when given, tallies every region's curl on the way. */
    fun extract(
        prob: FloatArray,
        w: Int,
        h: Int,
        scaleX: Float,
        scaleY: Float,
        curl: CurlCounter? = null
    ): List<Quad> {
        val visited = BooleanArray(w * h)
        val out = ArrayList<Quad>()
        val stack = IntArray(w * h)

        for (start in 0 until w * h) {
            if (prob[start] > thresh && !visited[start]) {
                if (out.size >= maxCandidates) break
                val region = floodRegion(prob, w, h, start, visited, stack)
                regionQuad(prob, w, h, region, curl)?.let { out.add(Geometry.orderQuad(it.scaled(scaleX, scaleY))) }
            }
        }
        return out
    }

    /** The 4-connected region above [thresh] around [start], marking it in [visited]. */
    private fun floodRegion(
        prob: FloatArray,
        w: Int,
        h: Int,
        start: Int,
        visited: BooleanArray,
        stack: IntArray
    ): Region {
        var top = 0
        stack[top++] = start
        visited[start] = true
        val border = ArrayList<Float>(256)
        var pixels = 0

        while (top > 0) {
            val idx = stack[--top]
            val x = idx % w
            val y = idx / w
            pixels++

            var isBorder = false
            for (d in 0 until 4) {
                val nx = x + NX[d]
                val ny = y + NY[d]
                if (nx !in 0 until w || ny !in 0 until h) {
                    isBorder = true
                    continue
                }
                val n = ny * w + nx
                if (prob[n] <= thresh) {
                    isBorder = true
                } else if (!visited[n]) {
                    visited[n] = true
                    stack[top++] = n
                }
            }
            if (isBorder) {
                border.add(x.toFloat())
                border.add(y.toFloat())
            }
        }
        return Region(pixels, border.toFloatArray())
    }

    /** The region's expanded box in map pixels, or null when it is too small or too faint. */
    private fun regionQuad(prob: FloatArray, w: Int, h: Int, region: Region, curl: CurlCounter?): Quad? {
        val rect = candidateRect(region) ?: return null
        curl?.add(region.pixels, rect)
        if (polygonScore(prob, w, h, rect.quad) < boxThresh) return null

        val expanded = Geometry.minAreaRect(Geometry.unclip(rect.quad, unclipRatio))
        return if (expanded.shortSide < minSize + 2f) null else expanded.quad
    }

    private fun candidateRect(region: Region): RotatedRect? {
        if (region.pixels < 4 || region.border.size < 6) return null
        val rect = Geometry.minAreaRect(region.border)
        return if (rect.shortSide < minSize) null else rect
    }

    /** Mean probability inside the quad (scanline point-in-polygon). */
    private fun polygonScore(prob: FloatArray, w: Int, h: Int, q: Quad): Float {
        val x0 = q.minX.toInt().coerceIn(0, w - 1)
        val x1 = ceil(q.maxX).toInt().coerceIn(0, w - 1)
        val y0 = q.minY.toInt().coerceIn(0, h - 1)
        val y1 = ceil(q.maxY).toInt().coerceIn(0, h - 1)
        if (x1 < x0 || y1 < y0) return 0f

        var sum = 0f
        var count = 0
        for (y in y0..y1) {
            val py = y + 0.5f
            for (x in x0..x1) {
                if (!contains(q, x + 0.5f, py)) continue
                sum += prob[y * w + x]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun contains(q: Quad, x: Float, y: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val yi = q.y(i)
            val yj = q.y(j)
            if ((yi > y) != (yj > y)) {
                val xi = q.x(i)
                val cross = (q.x(j) - xi) * (y - yi) / (yj - yi) + xi
                if (x < cross) inside = !inside
            }
            j = i
        }
        return inside
    }

    private companion object {
        val NX = intArrayOf(1, -1, 0, 0)
        val NY = intArrayOf(0, 0, 1, -1)
    }
}

/** A flooded text region: its pixel count and its border pixels as x, y pairs. */
private class Region(val pixels: Int, val border: FloatArray)
