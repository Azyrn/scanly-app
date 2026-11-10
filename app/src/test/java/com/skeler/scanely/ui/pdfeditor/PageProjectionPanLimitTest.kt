package com.skeler.scanely.ui.pdfeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pan range the editor's pinch rubber-bands beyond (PageViewport). */
class PageProjectionPanLimitTest {
    // A4 portrait in a phone-shaped container: width-bound, fit scale = 1000 / 595.
    private val base = PageProjection(Size(1000f, 2000f), pageWidthPt = 595f, pageHeightPt = 842f)

    @Test
    fun aFittedPageCannotPan() {
        assertEquals(Offset.Zero, base.panLimit())
    }

    @Test
    fun aZoomedPageCanPanByHalfItsOverflow() {
        val limit = base.copy(zoom = 3f).panLimit()
        assertEquals(1000f, limit.x, 0.5f) // 3000 wide in a 1000 container
        val height = 842f * 1000f / 595f * 3f
        assertEquals((height - 2000f) / 2f, limit.y, 0.5f)
    }

    @Test
    fun aQuarterTurnedPageSwapsItsAxes() {
        val turned = base.copy(rotation = 90, zoom = 2f).panLimit()
        val scale = 1000f / 842f * 2f
        assertEquals((842f * scale - 1000f) / 2f, turned.x, 0.5f)
        assertEquals(((595f * scale - 2000f) / 2f).coerceAtLeast(0f), turned.y, 0.5f)
    }
}
