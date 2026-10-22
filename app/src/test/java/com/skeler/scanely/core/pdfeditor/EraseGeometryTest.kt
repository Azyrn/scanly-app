package com.skeler.scanely.core.pdfeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.skeler.scanely.core.pdfeditor.model.InkStroke
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.ShapeKind
import com.skeler.scanely.core.pdfeditor.model.eraseWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseGeometryTest {

    private fun ink(from: Offset, to: Offset, width: Float = 2f) = PdfEdit.Ink(
        pageIndex = 0,
        strokes = listOf(InkStroke(listOf(from, to), Color.Black, width))
    )

    private fun strokesOf(markings: List<PdfEdit.Marking>): List<InkStroke> =
        markings.filterIsInstance<PdfEdit.Ink>().flatMap { it.strokes }

    @Test
    fun `misses leave the marking untouched`() {
        val stroke = ink(Offset(0f, 0f), Offset(100f, 0f))
        assertNull(stroke.eraseWith(Offset(50f, 500f), radius = 10f))
    }

    @Test
    fun `rubbing the middle of a stroke leaves both ends`() {
        val stroke = ink(Offset(0f, 0f), Offset(100f, 0f))

        val survivors = stroke.eraseWith(Offset(50f, 0f), radius = 10f)

        val pieces = strokesOf(survivors!!)
        assertEquals(2, pieces.size)
        // The cut lands on the circle, not on the nearest sample.
        assertEquals(39f, pieces[0].points.last().x, 0.5f)
        assertEquals(61f, pieces[1].points.first().x, 0.5f)
    }

    @Test
    fun `rubbing an end shortens the stroke without splitting it`() {
        val stroke = ink(Offset(0f, 0f), Offset(100f, 0f))

        val pieces = strokesOf(stroke.eraseWith(Offset(0f, 0f), radius = 20f)!!)

        assertEquals(1, pieces.size)
        assertEquals(21f, pieces.first().points.first().x, 0.5f)
        assertEquals(100f, pieces.first().points.last().x, 0.5f)
    }

    @Test
    fun `covering the whole stroke removes it`() {
        val stroke = ink(Offset(0f, 0f), Offset(10f, 0f))
        assertEquals(emptyList<PdfEdit.Marking>(), stroke.eraseWith(Offset(5f, 0f), radius = 50f))
    }

    /** A fast drag samples far apart; the cut still has to land where the finger was. */
    @Test
    fun `a disc between two samples still cuts the segment`() {
        val stroke = ink(Offset(0f, 0f), Offset(400f, 0f))

        val pieces = strokesOf(stroke.eraseWith(Offset(200f, 0f), radius = 10f)!!)

        assertEquals(2, pieces.size)
        assertTrue(pieces[0].points.last().x < 200f)
        assertTrue(pieces[1].points.first().x > 200f)
    }

    @Test
    fun `stroke width widens the reach so touching a fat stroke's edge erases it`() {
        val fat = ink(Offset(0f, 0f), Offset(100f, 0f), width = 30f)

        // 16pt away from the centreline: clear of a 2pt stroke, inside a 30pt one.
        assertNull(ink(Offset(0f, 0f), Offset(100f, 0f)).eraseWith(Offset(50f, 16f), radius = 1f))
        assertTrue(fat.eraseWith(Offset(50f, 16f), radius = 1f) != null)
    }

    @Test
    fun `highlight loses only the quad that was rubbed`() {
        val highlight = PdfEdit.Highlight(
            pageIndex = 0,
            quads = listOf(Rect(0f, 0f, 50f, 10f), Rect(60f, 0f, 100f, 10f)),
            color = Color.Yellow
        )

        val survivors = highlight.eraseWith(Offset(80f, 5f), radius = 5f)

        val kept = (survivors!!.single() as PdfEdit.Highlight).quads
        assertEquals(listOf(Rect(0f, 0f, 50f, 10f)), kept)
    }

    /**
     * The old bounding-box test deleted a diagonal stroke from anywhere in its box. The corner of
     * the box is empty page, and erasing from empty page is what made the tool feel broken.
     */
    @Test
    fun `empty space inside a diagonal stroke's bounding box is not a hit`() {
        val diagonal = ink(Offset(0f, 0f), Offset(100f, 100f))
        assertNull(diagonal.eraseWith(Offset(95f, 5f), radius = 10f))
    }

    @Test
    fun `an unfilled shape is only hit near its outline`() {
        val outline = PdfEdit.Shape(
            pageIndex = 0,
            kind = ShapeKind.RECTANGLE,
            box = Rect(0f, 0f, 100f, 100f),
            strokeColor = Color.Black,
            fillColor = null,
            strokeWidthPt = 2f
        )

        assertNull(outline.eraseWith(Offset(50f, 50f), radius = 5f))
        assertEquals(emptyList<PdfEdit.Marking>(), outline.eraseWith(Offset(0f, 50f), radius = 5f))
    }

    @Test
    fun `a filled shape is hit anywhere inside it`() {
        val filled = PdfEdit.Shape(
            pageIndex = 0,
            kind = ShapeKind.RECTANGLE,
            box = Rect(0f, 0f, 100f, 100f),
            strokeColor = Color.Black,
            fillColor = Color.Red,
            strokeWidthPt = 2f
        )

        assertEquals(emptyList<PdfEdit.Marking>(), filled.eraseWith(Offset(50f, 50f), radius = 5f))
    }
}
