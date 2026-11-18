package com.skeler.scanely.core.pdfeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageReaderRenderSizeTest {

    // Android refuses to draw a bitmap over 100 MB (RecordingCanvas.MAX_BITMAP_SIZE), at 4 bytes a pixel.
    private val drawableLimitPx = 100L * 1024 * 1024 / 4

    @Test
    fun `an ordinary page renders at the requested width`() {
        val (width, height) = PdfPageReader.renderSize(1200, 595f, 842f)
        assertEquals(1200, width)
        assertEquals(1698, height)
    }

    @Test
    fun `A4 and Letter pages keep exactly the widths the app asks for`() {
        for (width in listOf(1080, 1000, 1200)) {
            assertEquals(width, PdfPageReader.renderSize(width, 595f, 842f).first)
            assertEquals(width, PdfPageReader.renderSize(width, 612f, 792f).first)
        }
        assertEquals(1528, PdfPageReader.renderSize(1080, 595f, 842f).second)
        assertEquals(1294, PdfPageReader.renderSize(1000, 612f, 792f).second)
    }

    @Test
    fun `a long receipt page stays drawable and keeps its shape`() {
        // An 80 mm receipt printed onto one 200-inch page, the PDF maximum height of 14400 pt.
        val (width, height) = PdfPageReader.renderSize(1080, 227f, 14_400f)
        assertTrue("$width x $height", width.toLong() * height <= drawableLimitPx)
        assertEquals(14_400f / 227f, height.toFloat() / width, 0.05f)
    }

    @Test
    fun `a degenerate page never asks for an impossible bitmap`() {
        val (width, height) = PdfPageReader.renderSize(1200, 1f, 14_400f)
        assertTrue("$width x $height", width >= 1 && height >= 1)
        assertTrue("$width x $height", width.toLong() * height <= drawableLimitPx)
    }
}
