package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.engine.FontMatcher
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontMatcherTest {

    /** Stand-in metrics: one average advance per em, per family, close to the real clones'. */
    private val advancePerEm = mapOf(
        OfficialFont.SERIF to 0.46f,
        OfficialFont.SANS to 0.50f,
        OfficialFont.CALIBRI_LIKE to 0.47f,
        OfficialFont.CAMBRIA_LIKE to 0.49f,
        OfficialFont.MONO to 0.60f
    )

    private fun measure(font: OfficialFont, text: String, sizePt: Float): Float =
        text.length * advancePerEm.getValue(font) * sizePt

    private fun widthOf(font: OfficialFont, text: String, sizePt: Float) =
        measure(font, text, sizePt)

    @Test
    fun `a monospaced run is recognised by how wide it is`() {
        val text = "ACCOUNT NUMBER"
        val detected = FontMatcher.select(
            text = text,
            measuredWidthPt = widthOf(OfficialFont.MONO, text, 10f),
            reportedSizePt = 10f,
            measure = ::measure
        )

        assertEquals(OfficialFont.MONO, detected.font)
        assertTrue(detected.isConfident)
    }

    @Test
    fun `a serif run is not mistaken for the widest family`() {
        val text = "Government of India"
        val detected = FontMatcher.select(
            text = text,
            measuredWidthPt = widthOf(OfficialFont.SERIF, text, 11f),
            reportedSizePt = 11f,
            measure = ::measure
        )

        assertEquals(OfficialFont.SERIF, detected.font)
    }

    @Test
    fun `the size is corrected so the match reproduces the run's real width`() {
        val text = "Permanent Account Number"
        // The page shows the run 8% wider than pdfium's reported size would draw it, which is what
        // a scaled text matrix looks like from here.
        val detected = FontMatcher.select(
            text = text,
            measuredWidthPt = widthOf(OfficialFont.SANS, text, 10f) * 1.08f,
            reportedSizePt = 10f,
            measure = ::measure
        )

        assertEquals(OfficialFont.SANS, detected.font)
        assertEquals(10.8f, detected.sizePt, 0.01f)
    }

    /**
     * Width alone cannot separate a narrow font set large from a wide font set small, and this is
     * the case where they coincide exactly. The reported size is the tiebreaker: pdfium is right
     * about size far more often than it is wrong, so the family that explains the width *at that
     * size* wins over the same width reached by rescaling a different family.
     */
    @Test
    fun `where two families explain the same width, the reported size decides`() {
        val text = "ACCOUNT NUMBER"
        val detected = FontMatcher.select(
            text = text,
            measuredWidthPt = widthOf(OfficialFont.SANS, text, 10f) * 1.2f,
            reportedSizePt = 10f,
            measure = ::measure
        )

        assertEquals(OfficialFont.MONO, detected.font)
        assertEquals(10f, detected.sizePt, 0.01f)
    }

    @Test
    fun `an implausible width leaves the reported size alone`() {
        val text = "S P A C E D  O U T"
        val detected = FontMatcher.select(
            text = text,
            measuredWidthPt = widthOf(OfficialFont.SANS, text, 10f) * 4f,
            reportedSizePt = 10f,
            measure = ::measure
        )

        assertEquals(10f, detected.sizePt, 0.01f)
    }

    @Test
    fun `too few characters to tell families apart is reported as unconfident`() {
        val detected = FontMatcher.select(
            text = "Rs",
            measuredWidthPt = 12f,
            reportedSizePt = 10f,
            measure = ::measure
        )

        assertFalse(detected.isConfident)
        assertEquals(10f, detected.sizePt, 0.01f)
    }

    @Test
    fun `a run with no measured width falls back rather than guessing`() {
        val detected = FontMatcher.select(
            text = "a whole paragraph of text",
            measuredWidthPt = 0f,
            reportedSizePt = 9f,
            measure = ::measure
        )

        assertFalse(detected.isConfident)
        assertEquals(9f, detected.sizePt, 0.01f)
    }
}
