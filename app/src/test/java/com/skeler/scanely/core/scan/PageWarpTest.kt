package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Homography
import com.skeler.scanely.core.geometry.PinholeCamera
import com.skeler.scanely.core.idcard.IdDocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageWarpTest {

    /** A 2x2-cell checkerboard page (cells 0 / 255) seen through [cam]; returns photo pixels and quad. */
    private fun photographed(cam: PinholeCamera, wUnits: Double, hUnits: Double, tilt: Double) =
        cam.project(wUnits, hUnits, tiltDeg = tilt, yawDeg = 8.0).let { q ->
            val pageW = 1000f
            val pageH = (1000.0 * hUnits / wUnits).toFloat()
            val back = Homography.rectToQuad(q, pageW, pageH).inverse()
            val px = IntArray(cam.imageWidth * cam.imageHeight) { i ->
                val (u, v) = back.map(i % cam.imageWidth + 0.5f, i / cam.imageWidth + 0.5f)
                val inside = u in 0f..pageW && v in 0f..pageH
                val dark = inside && ((u < pageW / 2) xor (v < pageH / 2))
                val c = if (!inside) 90 else if (dark) 0 else 255
                (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
            px to q
        }

    private fun luma(p: Int) = (p shr 8) and 0xFF

    @Test
    fun tiltedPageComesOutFlatWithItsTrueShape() {
        val cam = PinholeCamera(1500, 2000)
        val (px, q) = photographed(cam, 210.0, 297.0, tilt = 35.0)
        val page = PageWarp.warp(px, cam.imageWidth, cam.imageHeight, q)
        val aspect = page.width.toFloat() / page.height
        assertTrue("aspect $aspect", abs(aspect / (210f / 297f) - 1f) < 0.02f)
        // cells: top-left and bottom-right white, the other two black; boundaries mid-axis
        val w = page.width
        val h = page.height
        val row = h / 4
        val switchX = (1 until w).first { luma(page.pixels[row * w + it]) < 128 }
        assertEquals("vertical boundary", w / 2f, switchX.toFloat(), w * 0.01f)
        val col = 3 * w / 4
        val switchY = (1 until h).first { luma(page.pixels[it * w + col]) > 128 }
        assertEquals("horizontal boundary", h / 2f, switchY.toFloat(), h * 0.01f)
        // the corner pixels are the page, not the background (90)
        assertEquals(255, luma(page.pixels[2 * w + 2]))
        assertEquals(0, luma(page.pixels[2 * w + w - 3]))
        assertEquals(255, luma(page.pixels[(h - 3) * w + w - 3]))
        assertEquals(0, luma(page.pixels[(h - 3) * w + 2]))
    }

    @Test
    fun longSideIsCappedAt2200() {
        val cam = PinholeCamera(3000, 4000)
        val q = cam.project(210.0, 297.0, tiltDeg = 0.0, distance = 330.0)
        val size = PageWarp.pageSize(q, cam.imageWidth, cam.imageHeight)
        assertEquals(PageWarp.MAX_LONG_SIDE, maxOf(size.width, size.height))
        assertTrue(abs(size.width.toFloat() / size.height - 210f / 297f) < 0.01f)
    }

    @Test
    fun idModeForcesTheCardShapeInTheCardsOrientation() {
        val cam = PinholeCamera(4000, 3000)
        // the card photographed a bit squashed: forced aspect must still be exactly ID-1
        val landscape = cam.project(85.6, 50.0, tiltDeg = 25.0)
        val s1 = PageWarp.pageSize(landscape, cam.imageWidth, cam.imageHeight, IdDocumentKind.ID_CARD.aspect)
        assertEquals(IdDocumentKind.ID_CARD.aspect, s1.width.toFloat() / s1.height, 0.01f)
        val portrait = cam.project(53.98, 85.6, tiltDeg = 25.0)
        val s2 = PageWarp.pageSize(portrait, cam.imageWidth, cam.imageHeight, IdDocumentKind.ID_CARD.aspect)
        assertEquals(1f / IdDocumentKind.ID_CARD.aspect, s2.width.toFloat() / s2.height, 0.01f)
        val passport = cam.project(125.0, 88.0, tiltDeg = 10.0)
        val s3 = PageWarp.pageSize(passport, cam.imageWidth, cam.imageHeight, IdDocumentKind.PASSPORT.aspect)
        assertEquals(IdDocumentKind.PASSPORT.aspect, s3.width.toFloat() / s3.height, 0.01f)
    }
}
