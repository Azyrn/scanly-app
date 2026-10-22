package com.skeler.scanely.core.pdfeditor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.skeler.scanely.core.pdfeditor.engine.CompressionSettings
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.TextFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionResultTest {

    private fun result(bytes: Long, original: Long) =
        CompressionResult(CompressionSettings(60, 200), bytes, original)

    @Test
    fun `a saving is reported as a negative percentage`() {
        val outcome = result(bytes = 150_000, original = 200_000)

        assertTrue(outcome.helps)
        assertEquals(-25, outcome.deltaPercent)
        assertEquals(150_000L, outcome.effectiveBytes)
    }

    @Test
    fun `a pass that grew the file says so instead of rounding to zero`() {
        // The reported case: 196 kB in, 201 kB out.
        val outcome = result(bytes = 201_000, original = 196_000)

        assertFalse(outcome.helps)
        assertEquals(3, outcome.deltaPercent)
    }

    @Test
    fun `growth is never what the user ends up with`() {
        val outcome = result(bytes = 201_000, original = 196_000)

        assertEquals(196_000L, outcome.effectiveBytes)
    }

    @Test
    fun `a saving too small to round to a percent is still not claimed as one`() {
        val outcome = result(bytes = 199_600, original = 200_000)

        assertTrue(outcome.helps)
        assertEquals(0, outcome.deltaPercent)
        assertEquals(199_600L, outcome.effectiveBytes)
    }

    @Test
    fun `lossless settings leave images alone`() {
        assertFalse(CompressionSettings(jpegQuality = 0, minImageEdge = 0).touchesImages)
        assertTrue(CompressionSettings(jpegQuality = 1, minImageEdge = 0).touchesImages)
    }
}

class TextFitTest {

    @Test
    fun `text that already fits is left at its own size`() {
        assertEquals(11f, TextFit.fittedSize(11f, singleLineWidthPt = 80f, availableWidthPt = 100f), 0.01f)
    }

    @Test
    fun `text that overruns is condensed to fit`() {
        assertEquals(
            9f,
            TextFit.fittedSize(11f, singleLineWidthPt = 110f, availableWidthPt = 90f),
            0.01f
        )
    }

    @Test
    fun `condensing stops before the text stops matching its neighbours`() {
        val fitted = TextFit.fittedSize(11f, singleLineWidthPt = 400f, availableWidthPt = 100f)

        assertEquals(11f * TextFit.MIN_FIT_RATIO, fitted, 0.01f)
    }

    @Test
    fun `a zero-width box cannot drive the size to nothing`() {
        assertEquals(11f, TextFit.fittedSize(11f, singleLineWidthPt = 50f, availableWidthPt = 0f), 0.01f)
    }

    @Test
    fun `a run starts where it was asked to and reaches the margin`() {
        val box = TextFit.runToMargin(startXPt = 72f, topPt = 100f, bottomPt = 112f, pageWidthPt = 595f)

        assertEquals(72f, box.left, 0.01f)
        assertEquals(595f - TextFit.PAGE_MARGIN_PT, box.right, 0.01f)
    }

    /** A page number or a stamp sits past where the right margin would put the box's far edge. */
    @Test
    fun `a run in the right margin still gets a usable width`() {
        val box = TextFit.runToMargin(startXPt = 560f, topPt = 100f, bottomPt = 112f, pageWidthPt = 595f)

        assertTrue("box must not be inverted", box.right > box.left)
        assertTrue("box must be wide enough for a word", box.width >= TextFit.MIN_RUN_WIDTH_PT)
    }

    @Test
    fun `a start beyond the page is pulled back onto it`() {
        val box = TextFit.runToMargin(startXPt = 9000f, topPt = 0f, bottomPt = 10f, pageWidthPt = 595f)

        assertTrue(box.left <= 595f - TextFit.PAGE_MARGIN_PT)
        assertTrue(box.right > box.left)
    }

    @Test
    fun `a page narrower than its own margin does not invert the box`() {
        val box = TextFit.runToMargin(startXPt = 5f, topPt = 0f, bottomPt = 10f, pageWidthPt = 10f)

        assertTrue(box.right > box.left)
    }
}

/**
 * A replacement lays out in a box wider than the one it covers.
 *
 * Caught on a device, not here: the wider box was being computed and then dropped, because
 * `ReplaceText` had nowhere to carry it and both renderers laid text out in `cover`. A long
 * replacement then wrapped inside the old run's width and painted over the line below — the exact
 * "my typed words vanished" symptom the whole change set exists to remove.
 */
class ReplaceTextLayoutTest {

    private val cover = Rect(100f, 200f, 260f, 212f)

    private fun replacement(textBox: Rect) = PdfEdit.ReplaceText(
        pageIndex = 0,
        cover = cover,
        coverColor = Color.White,
        replacement = "a considerably longer replacement than the original run",
        font = OfficialFont.SANS,
        sizePt = 11f,
        color = Color.Black,
        baselinePt = 210f,
        textBox = textBox
    )

    @Test
    fun `the layout box may run past the cover to the margin`() {
        val edit = replacement(TextFit.runToMargin(cover.left, cover.top, cover.bottom, 595f))

        assertTrue(
            "text must be allowed more width than it hides",
            edit.textBox.width > edit.cover.width
        )
        assertEquals(cover.left, edit.textBox.left, 0.01f)
    }

    @Test
    fun `the cover still describes only what is hidden`() {
        val edit = replacement(TextFit.runToMargin(cover.left, cover.top, cover.bottom, 595f))

        assertEquals(cover, edit.cover)
    }

    /** Sizing against the cover is what keeps a replacement out of its neighbour's space. */
    @Test
    fun `a long replacement is condensed against the cover, not the layout box`() {
        val edit = replacement(TextFit.runToMargin(cover.left, cover.top, cover.bottom, 595f))
        val singleLineWidth = 300f

        val againstCover = TextFit.fittedSize(edit.sizePt, singleLineWidth, edit.cover.width)
        val againstLayout = TextFit.fittedSize(edit.sizePt, singleLineWidth, edit.textBox.width)

        assertTrue(againstCover < againstLayout)
        assertEquals(edit.sizePt, againstLayout, 0.01f)
    }

    @Test
    fun `an edit built without a layout box falls back to the cover`() {
        val edit = PdfEdit.ReplaceText(
            pageIndex = 0,
            cover = cover,
            coverColor = Color.White,
            replacement = "short",
            font = OfficialFont.SANS,
            sizePt = 11f,
            color = Color.Black
        )

        assertEquals(cover, edit.textBox)
    }
}
