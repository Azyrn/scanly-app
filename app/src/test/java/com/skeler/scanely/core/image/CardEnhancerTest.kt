package com.skeler.scanely.core.image

import org.junit.Assert.assertTrue
import org.junit.Test

class CardEnhancerTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun luma(p: Int) = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
    private fun chroma(p: Int): Int {
        val c = intArrayOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
        return c.max() - c.min()
    }

    /**
     * A card under light falling 0.95 -> 0.75 across it: pale green security print with darker
     * guilloche lines, a large grey photo box, dark text and a red SPECIMEN mark.
     */
    @Test
    fun printSurvivesTextDarkensColourStays() {
        val w = 856
        val h = 540
        val kind = IntArray(w * h)
        val src = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val light = 0.95f - 0.2f * x / w
            val (k, c) = when {
                x in 40 until 240 && y in 110 until 380 -> 1 to Triple(150, 160, 175)          // photo box
                x in 300 until 700 && y % 60 in 20 until 30 -> 2 to Triple(20, 30, 40)        // text
                x in 560 until 820 && y in 440 until 500 -> 3 to Triple(190, 40, 50)           // SPECIMEN
                (x + y) % 26 < 2 -> 4 to Triple(200, 216, 214)                                 // guilloche
                else -> 0 to Triple(226, 236, 232)                                             // print ground
            }
            kind[i] = k
            rgb((c.first * light).toInt(), (c.second * light).toInt(), (c.third * light).toInt())
        }
        val out = CardEnhancer.enhance(src, w, h)
        val photo = out.indices.filter { kind[it] == 1 }.map { luma(out[it]) }.average()
        val ground = out.indices.filter { kind[it] == 0 }.map { luma(out[it]) }.average()
        val text = out.indices.filter { kind[it] == 2 }.map { luma(out[it]) }.average()
        val guilloche = out.indices.filter { kind[it] == 4 }.map { luma(out[it]) }.average()
        val red = out.indices.filter { kind[it] == 3 }.map { chroma(out[it]) }.average()
        val white = out.count { luma(it) >= 250 }.toFloat() / out.size
        assertTrue("photo box $photo stays a mid grey", photo in 90.0..200.0)
        assertTrue("text $text near black", text <= 30)
        assertTrue("ground $ground light but not white", ground in 215.0..249.0)
        assertTrue("guilloche $guilloche still drawn on ground $ground", ground - guilloche >= 8)
        assertTrue("red chroma $red", red >= 120)
        assertTrue("snapped to white: $white", white < 0.02f)
        // light fall-off flattened: ground at left and right edges within 6 levels
        val left = (0 until h).filter { kind[it * w + 290] == 0 }.map { luma(out[it * w + 290]) }.average()
        val right = (0 until h).filter { kind[it * w + 850] == 0 }.map { luma(out[it * w + 850]) }.average()
        assertTrue("left $left right $right", kotlin.math.abs(left - right) <= 6)
    }
}
