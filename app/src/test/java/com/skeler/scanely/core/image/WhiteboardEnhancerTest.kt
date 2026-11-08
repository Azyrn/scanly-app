package com.skeler.scanely.core.image

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class WhiteboardEnhancerTest {

    private data class Board(val pixels: IntArray, val w: Int, val h: Int, val ink: Array<Ink?>)

    private enum class Ink(val r: Int, val g: Int, val b: Int) {
        BLACK(35, 35, 40), RED(200, 35, 40), BLUE(30, 60, 190), GREEN(30, 150, 60)
    }

    /**
     * A board photographed under bad light: brightness falls from 235 to 140 across the board, a warm
     * tint, a glare spot, a shadow band, sensor noise, and strokes of four marker colours.
     */
    private fun board(w: Int = 1100, h: Int = 800, seed: Int = 1): Board {
        val rnd = Random(seed)
        val ink = arrayOfNulls<Ink>(w * h)
        // strokes: horizontal and diagonal lines, 6 px thick, each colour twice
        Ink.entries.forEachIndexed { k, colour ->
            val y0 = 90 + k * 170
            for (x in 60 until w - 60) for (t in 0 until 6) ink[(y0 + t) * w + x] = colour
            for (s in 0 until 500) {
                val x = 80 + s
                val y = y0 + 30 + s / 6
                if (y + 6 < h) for (t in 0 until 6) ink[(y + t) * w + x] = colour
            }
        }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var light = 235f - 95f * x / w                      // falling light
            light *= shadow(y)                                   // shadow band, soft-edged like a real one
            val dx = x - 820f; val dy = y - 220f
            val glare = 60f * exp(-(dx * dx + dy * dy) / (2 * 70f * 70f))
            val i = y * w + x
            val c = ink[i]
            val (r0, g0, b0) = if (c == null) Triple(1f, 1f, 1f) else Triple(c.r / 255f, c.g / 255f, c.b / 255f)
            fun ch(v: Float, tint: Float) = (v * light * tint + glare + rnd.nextInt(-6, 7)).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(r0, 1.0f) shl 16) or (ch(g0, 0.95f) shl 8) or ch(b0, 0.85f)
        }
        return Board(px, w, h, ink)
    }

    /** 0.62 inside rows 380..470, easing to 1 over a 24 px penumbra on each side. */
    private fun shadow(y: Int): Float {
        val d = maxOf(380 - y, y - 470, 0)
        return if (d >= 24) 1f else 0.62f + 0.38f * d / 24f
    }

    private fun luma(p: Int) = 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)

    private fun hue(r: Double, g: Double, b: Double): Double {
        val deg = Math.toDegrees(atan2(sqrt(3.0) * (g - b), 2 * r - g - b))
        return (deg + 360) % 360
    }

    private fun hueDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    @Test
    fun boardBecomesEvenWhiteUnderBadLight() {
        val b = board()
        val out = WhiteboardEnhancer.enhance(b.pixels, b.w, b.h)
        // board pixels away from strokes (strokes blur nothing here, but keep a 3 px margin)
        val values = ArrayList<Double>()
        for (y in 3 until b.h - 3) for (x in 3 until b.w - 3) {
            val near = (-3..3).any { d -> b.ink[(y + d) * b.w + x] != null || b.ink[y * b.w + x + d] != null }
            if (!near) values += luma(out[y * b.w + x])
        }
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val white = values.count { it >= 254.5 }.toDouble() / values.size
        assertTrue("board mean $mean", mean >= 252)
        assertTrue("board sd $sd", sd < 4)
        assertTrue("pure-white share $white", white > 0.97)
    }

    @Test
    fun strokesKeepTheirColourAndStayDark() {
        val b = board()
        val out = WhiteboardEnhancer.enhance(b.pixels, b.w, b.h)
        for (colour in Ink.entries) {
            var r = 0.0; var g = 0.0; var bl = 0.0; var n = 0; var dark = 0
            for (i in out.indices) if (b.ink[i] == colour) {
                val p = out[i]
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; bl += p and 0xFF; n++
                if (luma(p) < 170) dark++
            }
            r /= n; g /= n; bl /= n
            val darkShare = dark.toDouble() / n
            assertTrue("$colour legible: $darkShare of stroke pixels dark", darkShare > 0.95)
            if (colour == Ink.BLACK) {
                val chroma = maxOf(r, g, bl) - minOf(r, g, bl)
                assertTrue("black stays neutral, chroma $chroma", chroma < 40)
            } else {
                val want = hue(colour.r.toDouble(), colour.g.toDouble(), colour.b.toDouble())
                val got = hue(r, g, bl)
                assertTrue("$colour hue $got vs $want", hueDiff(got, want) < 15)
            }
        }
    }

    @Test
    fun thickStrokeIsNotMistakenForBoard() {
        // a 200 px filled marker shape on a full page: every block inside it is ink-only
        val w = 2200; val h = 1650
        val px = IntArray(w * h) { 0xFF000000.toInt() or (210 shl 16) or (205 shl 8) or 195 }
        for (y in 700 until 900) for (x in 600 until 1600) px[y * w + x] = 0xFF000000.toInt() or (40 shl 16) or (45 shl 8) or 150
        val out = WhiteboardEnhancer.enhance(px, w, h)
        val inside = luma(out[800 * w + 1100])
        assertTrue("thick stroke stays dark: $inside", inside < 120)
    }

    @Test
    fun fastEnoughForAFullPage() {
        val b = board(2200, 1650, seed = 3)
        WhiteboardEnhancer.enhance(b.pixels, b.w, b.h) // warm-up
        val start = System.nanoTime()
        WhiteboardEnhancer.enhance(b.pixels, b.w, b.h)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("whiteboard 2200x1650 on JVM: $ms ms")
        assertTrue("took $ms ms", ms < 2000)
    }
}
