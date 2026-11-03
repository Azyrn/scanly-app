package com.skeler.scanely.core.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class CurledLinesTest {

    private val w = 400
    private val h = 200

    /** A probability map with text lines 8 px tall; [bend] px of sag at each line's middle. */
    private fun page(lines: Int, bend: Float): FloatArray {
        val prob = FloatArray(w * h)
        for (line in 0 until lines) {
            val top = 10 + line * 30
            for (x in 20 until 380) {
                val sag = (bend * sin(Math.PI * (x - 20) / 360)).roundToInt()
                for (y in top + sag until top + sag + 8) prob[y * w + x] = 0.9f
            }
        }
        return prob
    }

    @Test
    fun straightLinesAreNotCurled() {
        val detection = DbPostProcessor().extractDetection(page(5, 0f), w, h, 1f, 1f)

        assertEquals(5, detection.quads.size)
        assertEquals(0f, detection.curledShare, 0f)
    }

    @Test
    fun linesBentIntoTheSpineAreCurled() {
        val detection = DbPostProcessor().extractDetection(page(5, 5f), w, h, 1f, 1f)

        assertEquals(1f, detection.curledShare, 0f)
        assertEquals(5, detection.quads.size)
    }

    @Test
    fun confidentTextIgnoresRejectedLines() {
        val lines = listOf(RecLine("abcd", 0.5f), RecLine("  xy ", 1f), RecLine("garbage", 0.2f))

        assertEquals(4f, confidentText(lines), 1e-6f)
    }
}
