package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.ocr.paddle.ReadLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTextLayoutTest {

    @Test
    fun aRecognisedLineBecomesAnInvisibleBlockAtItsPlaceOnThePage() {
        // A 1000 x 2000 px image of a 500 x 1000 pt page: every pixel is half a point.
        val lines = listOf(ReadLine("Hello world", 0.9f, left = 100f, top = 200f, right = 500f, bottom = 240f))

        val layout = ScanTextLayout.layout(lines, 1000, 2000, 500f, 1000f)

        val segment = layout.blocks.single().lines.single()
        val token = segment.tokens.single()
        assertEquals("Hello world", token.text)
        assertEquals(50f, token.u0, 0.01f)
        assertEquals(250f, token.u1, 0.01f)
        assertTrue(layout.behindPicture)
        assertFalse(segment.rtl)
        assertFalse(segment.bidi)
        // 20 pt tall line: baseline 20% of its height above its bottom (page y runs up).
        assertEquals(1000f - (120f - 4f), segment.baselineV, 0.01f)
    }

    @Test
    fun anArabicLineIsRightToLeftWithAnArabicStyle() {
        val lines = listOf(ReadLine("مرحبا بالعالم", 0.9f, 100f, 200f, 500f, 240f))

        val segment = ScanTextLayout.layout(lines, 1000, 2000, 500f, 1000f).blocks.single().lines.single()

        assertTrue(segment.rtl)
        assertTrue(segment.bidi)
        assertTrue(segment.tokens.single().style.arabic)
    }

    @Test
    fun aSliverOfALineIsDropped() {
        val lines = listOf(ReadLine("-", 0.9f, 100f, 200f, 500f, 201f))

        assertTrue(ScanTextLayout.layout(lines, 1000, 2000, 500f, 1000f).isEmpty)
    }
}
