package com.skeler.scanely.core.idcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IdCardLayoutTest {

    private val dpi = IdCardLayout.DEFAULT_DPI
    private fun pxToMm(px: Int) = px * 25.4f / dpi

    @Test
    fun idCardSidesPrintAtTrueSize() {
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1600 to 1010, 1600 to 1010))
        assertEquals(2, p.size)
        for (side in p) {
            assertTrue("width ${pxToMm(side.image.width)} mm", abs(pxToMm(side.image.width) - 85.60f) < 0.5f)
            assertTrue("height ${pxToMm(side.image.height)} mm", abs(pxToMm(side.image.height) - 53.98f) < 0.5f)
            assertTrue(side.snapped)
            assertFalse(side.rotate90)
        }
    }

    @Test
    fun sidesAreCentredStackedAndInsidePrintableMargins() {
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1600 to 1010, 1600 to 1010))
        val pageW = IdCardLayout.pageWidthPx()
        val pageH = IdCardLayout.pageHeightPx()
        val margin = IdCardLayout.mmToPx(10f) // printers can't print the outer ~5 mm; keep 10 mm clear
        for (side in p) {
            assertTrue(abs((pageW - side.box.right) - side.box.left) <= 1) // centred, ±1 px (0.08 mm) rounding
            assertTrue(side.box.left >= margin && side.box.top >= margin)
            assertTrue(side.box.right <= pageW - margin && side.box.bottom <= pageH - margin)
        }
        val gap = p[1].box.top - p[0].box.bottom
        assertEquals(IdCardLayout.mmToPx(IdCardLayout.GAP_MM), gap)
        val above = p[0].box.top
        val below = pageH - p[1].box.bottom
        assertTrue("group centred vertically: $above vs $below", abs(above - below) <= 1)
    }

    @Test
    fun portraitScanIsTurnedToLandscape() {
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1010 to 1600))
        assertTrue(p[0].rotate90)
        assertTrue(p[0].snapped)
        assertTrue(p[0].image.width > p[0].image.height)
    }

    @Test
    fun slightlyOffCropSnapsToTrueSize() {
        // 6 % too wide: the crop handles wobbled, the card is still a card
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1680 to 1000))
        assertTrue(p[0].snapped)
        assertEquals(p[0].box, p[0].image)
    }

    @Test
    fun farOffCropIsFittedWithoutDistortion() {
        // a square crop (the user framed card + table): don't stretch the face
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1200 to 1200))
        val side = p[0]
        assertFalse(side.snapped)
        assertEquals(side.image.width, side.image.height)
        assertTrue(side.image.left >= side.box.left && side.image.right <= side.box.right)
        assertEquals(side.box.height, side.image.height)
    }

    @Test
    fun frontOnlyIsCentredOnThePage() {
        val p = IdCardLayout.layout(IdDocumentKind.ID_CARD, listOf(1600 to 1010))
        assertEquals(1, p.size)
        val pageH = IdCardLayout.pageHeightPx()
        assertTrue(abs(p[0].box.top - (pageH - p[0].box.bottom)) <= 1)
    }

    @Test
    fun passportUsesItsOwnSizeAndOneSide() {
        val p = IdCardLayout.layout(IdDocumentKind.PASSPORT, listOf(1800 to 1270, 1800 to 1270))
        assertEquals(1, p.size) // a passport photo page has one side; extra scans are not placed
        assertTrue(abs(pxToMm(p[0].image.width) - 125f) < 0.5f)
        assertTrue(abs(pxToMm(p[0].image.height) - 88f) < 0.5f)
    }

    @Test
    fun a4At300DpiIsTheStandardPixelSize() {
        assertEquals(2480, IdCardLayout.pageWidthPx())
        assertEquals(3508, IdCardLayout.pageHeightPx())
    }

    @Test
    fun enlargedSidesScaleAlikeAndStayInsideMargins() {
        val pageW = IdCardLayout.pageWidthPx()
        val pageH = IdCardLayout.pageHeightPx()
        val margin = IdCardLayout.mmToPx(IdCardLayout.MARGIN_MM)
        for (kind in IdDocumentKind.entries) {
            val scans = List(kind.sides) { 1600 to (1600 / kind.aspect).toInt() }
            for (scale in IdCardLayout.sizeSteps(kind)) {
                val p = IdCardLayout.layout(kind, scans, scale)
                for (side in p) {
                    assertTrue("$kind x$scale width", abs(pxToMm(side.box.width) - kind.widthMm * scale) < 0.5f)
                    assertTrue("$kind x$scale height", abs(pxToMm(side.box.height) - kind.heightMm * scale) < 0.5f)
                    assertTrue("$kind x$scale inside", side.box.left >= margin && side.box.top >= margin)
                    assertTrue(side.box.right <= pageW - margin && side.box.bottom <= pageH - margin)
                }
            }
        }
    }

    @Test
    fun sizeStepsStartAtTrueSizeAndAScaleAboveTheMaxIsClamped() {
        assertEquals(listOf(1f, 1.25f, 1.5f, 1.75f, 2f), IdCardLayout.sizeSteps(IdDocumentKind.ID_CARD))
        assertEquals(listOf(1f, 1.25f, 1.5f), IdCardLayout.sizeSteps(IdDocumentKind.PASSPORT))
        val passport = IdDocumentKind.PASSPORT
        val huge = IdCardLayout.layout(passport, listOf(1800 to 1270), scale = 5f)
        val max = IdCardLayout.layout(passport, listOf(1800 to 1270), IdCardLayout.maxScale(passport))
        assertEquals(max, huge)
    }
}
