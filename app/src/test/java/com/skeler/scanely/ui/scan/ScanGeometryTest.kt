package com.skeler.scanely.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanGeometryTest {

    private fun quad(vararg xy: Float) = NormQuad.of(xy.toList().chunked(2).map { (x, y) -> NormPoint(x, y) })

    @Test
    fun `a convex clockwise outline is accepted`() {
        assertNotNull(quad(0.1f, 0.1f, 0.9f, 0.2f, 0.8f, 0.9f, 0.2f, 0.8f))
    }

    @Test
    fun `a dragged corner that makes the outline concave is refused`() {
        // Bottom-right pulled inside the triangle of the other three.
        assertNull(NormQuad.FULL.withCorner(NormQuad.BR, NormPoint(0.3f, 0.3f)))
    }

    @Test
    fun `a dragged corner that crosses an edge into a bow tie is refused`() {
        assertNull(NormQuad.FULL.withCorner(NormQuad.TR, NormPoint(0.2f, 1f)))
    }

    @Test
    fun `corners in anticlockwise order are refused`() {
        assertNull(quad(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f))
    }

    @Test
    fun `a corner dragged off the photo stays on its edge`() {
        val moved = NormQuad.FULL.withCorner(NormQuad.TL, NormPoint(-0.5f, 0.2f))
        assertEquals(NormPoint(0f, 0.2f), moved?.corners?.get(NormQuad.TL))
    }

    @Test
    fun `turning a quarter clockwise moves the old bottom-left to the top-left`() {
        val q = quad(0.1f, 0.2f, 0.7f, 0.1f, 0.8f, 0.9f, 0.2f, 0.7f)!!
        val turned = q.turnedClockwise()
        // (x, y) -> (1 - y, x); old BL (0.2, 0.7) lands at (0.3, 0.2), the new top-left.
        assertEquals(0.3f, turned.corners[NormQuad.TL].x, 1e-6f)
        assertEquals(0.2f, turned.corners[NormQuad.TL].y, 1e-6f)
        assertNotNull(NormQuad.of(turned.corners))
    }

    @Test
    fun `four quarter turns give the outline back`() {
        val q = quad(0.1f, 0.2f, 0.7f, 0.1f, 0.8f, 0.9f, 0.2f, 0.7f)!!
        val back = q.turned(4)
        q.corners.zip(back.corners).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-6f)
            assertEquals(a.y, b.y, 1e-6f)
        }
    }

    @Test
    fun `fit letterboxes a wide photo and fill crops it`() {
        val fit = placeImage(imageAspect = 2f, viewWidth = 300f, viewHeight = 400f, fill = false)
        assertEquals(ViewRect(0f, 125f, 300f, 150f), fit)
        val fill = placeImage(imageAspect = 2f, viewWidth = 300f, viewHeight = 400f, fill = true)
        assertEquals(ViewRect(-250f, 0f, 800f, 400f), fill)
    }

    @Test
    fun `a 4 by 3 frame fills a 3 by 4 viewfinder exactly once upright`() {
        val rect = placeImage(imageAspect = 3f / 4f, viewWidth = 300f, viewHeight = 400f, fill = true)
        assertEquals(ViewRect(0f, 0f, 300f, 400f), rect)
    }

    @Test
    fun `a touch grabs the nearest corner whose target holds it`() {
        val rect = ViewRect(0f, 0f, 100f, 100f)
        assertEquals(NormQuad.TR, rect.cornerAt(NormQuad.FULL, 90f, 10f, target = 48f))
        assertNull(rect.cornerAt(NormQuad.FULL, 50f, 50f, target = 48f))
    }

    @Test
    fun `the loupe sits above the finger, flips below near the top and keeps off the sides`() {
        assertEquals(200f to 300f, loupeCentre(200f, 396f, 400f, radius = 48f, lift = 96f, margin = 16f))
        assertEquals(200f to 196f, loupeCentre(200f, 100f, 400f, radius = 48f, lift = 96f, margin = 16f))
        assertEquals(64f to 300f, loupeCentre(5f, 396f, 400f, radius = 48f, lift = 96f, margin = 16f))
        assertEquals(336f to 300f, loupeCentre(399f, 396f, 400f, radius = 48f, lift = 96f, margin = 16f))
    }
}
