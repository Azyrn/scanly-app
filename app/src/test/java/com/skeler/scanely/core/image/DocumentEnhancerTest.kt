package com.skeler.scanely.core.image

import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEnhancerTest {

    private companion object {
        const val FAINT = 0.78f
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun luma(p: Int) = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000

    /**
     * A page under light falling from 235 to 175 across it: black text, faint grey (pencil-like)
     * text, red text, in 6-row bands.
     */
    private val w = 900
    private val h = 600
    private fun page(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val light = 235f - 60f * x / w
        val band = (y / 6) % 8
        val ink = x % 40 < 20 && y in 60 until 540
        val (r, g, b) = when {
            !ink || band >= 3 -> Triple(1f, 1f, 1f)
            band == 0 -> Triple(0.15f, 0.15f, 0.16f)   // black
            band == 1 -> Triple(FAINT, FAINT, FAINT)   // faint grey
            else -> Triple(0.80f, 0.18f, 0.20f)        // red
        }
        rgb((r * light).toInt(), (g * light).toInt(), (b * light).toInt())
    }

    private fun bandOf(i: Int): Int? {
        val x = i % w
        val y = i / w
        if (x % 40 !in 4 until 16 || y !in 66 until 534 || (y % 6) !in 1..4) return null
        return ((y / 6) % 8).takeIf { it < 3 }
    }

    @Test
    fun paperWhitensInkDarkensFaintTextGetsDarkerColourStays() {
        val src = page()
        val out = DocumentEnhancer.enhance(src, w, h)
        var paper = 0L
        var paperN = 0
        for (i in src.indices) if (i / w in 0 until 50) {
            paper += luma(out[i])
            paperN++
        }
        assertTrue("paper ${paper / paperN}", paper / paperN >= 245)
        for (i in src.indices) {
            when (bandOf(i)) {
                0 -> assertTrue("black ${luma(out[i])}", luma(out[i]) <= 25)
                // the photo is dim, so every pixel brightens; what must grow is the text's darkness
                // against its paper: 22% darker than the paper in the photo, more than that after
                1 -> assertTrue("faint grey ${luma(out[i])}", luma(out[i]) < FAINT * 255 - 5)
                2 -> {
                    val p = out[i]
                    val r = p shr 16 and 0xFF
                    val g = p shr 8 and 0xFF
                    assertTrue("red stays red: $r vs $g", r - g >= 80)
                }
                else -> Unit
            }
        }
    }
}
