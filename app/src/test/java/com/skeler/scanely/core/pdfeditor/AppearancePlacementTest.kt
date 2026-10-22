package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.AppearancePlacement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A4 portrait unrotated: 595 x 842. Displayed dimensions already have /Rotate applied, so at 90
 * and 270 the editor showed 842 x 595.
 */
class AppearancePlacementTest {

    private val portraitWidth = 595f
    private val portraitHeight = 842f

    /** A box in the lower-right of what the user was looking at, where a signature usually goes. */
    private val boxLeft = 395f
    private val boxTop = 700f
    private val boxWidth = 150f
    private val boxHeight = 60f

    /** The same lower-right box on a page the editor showed in landscape. */
    private val landscapeLeft = 642f
    private val landscapeTop = 475f

    @Test
    fun `an unrotated page passes the box straight through`() {
        val placed = AppearancePlacement.map(
            boxLeft, boxTop, boxWidth, boxHeight, portraitWidth, portraitHeight, 0
        )

        assertEquals(395f, placed.xPt)
        assertEquals(700f, placed.yFromTopPt)
        assertEquals(150f, placed.widthPt)
        assertEquals(60f, placed.heightPt)
        assertEquals(0, placed.bitmapRotationDegrees)
    }

    /**
     * At 90 the displayed page is landscape, so width and height swap and the stamp has to be
     * turned the other way to read upright.
     */
    @Test
    fun `a quarter turn swaps the axes and counter-rotates the stamp`() {
        val displayedWidth = portraitHeight
        val displayedHeight = portraitWidth

        val placed = AppearancePlacement.map(
            landscapeLeft, landscapeTop, boxWidth, boxHeight, displayedWidth, displayedHeight, 90
        )

        assertEquals(475f, placed.xPt)
        assertEquals(842f - 642f - 150f, placed.yFromTopPt)
        assertEquals(60f, placed.widthPt)
        assertEquals(150f, placed.heightPt)
        assertEquals(270, placed.bitmapRotationDegrees)
    }

    @Test
    fun `a half turn mirrors both axes and keeps the box shape`() {
        val placed = AppearancePlacement.map(
            boxLeft, boxTop, boxWidth, boxHeight, portraitWidth, portraitHeight, 180
        )

        assertEquals(595f - 395f - 150f, placed.xPt)
        assertEquals(842f - 700f - 60f, placed.yFromTopPt)
        assertEquals(150f, placed.widthPt)
        assertEquals(60f, placed.heightPt)
        assertEquals(180, placed.bitmapRotationDegrees)
    }

    @Test
    fun `three quarter turns swap the axes the other way`() {
        val displayedWidth = portraitHeight
        val displayedHeight = portraitWidth

        val placed = AppearancePlacement.map(
            landscapeLeft, landscapeTop, boxWidth, boxHeight, displayedWidth, displayedHeight, 270
        )

        assertEquals(595f - 475f - 60f, placed.xPt)
        assertEquals(642f, placed.yFromTopPt)
        assertEquals(60f, placed.widthPt)
        assertEquals(150f, placed.heightPt)
        assertEquals(90, placed.bitmapRotationDegrees)
    }

    /**
     * The mapped box has to land inside the unrotated page at every rotation. Getting a sign wrong
     * puts the signature off the page, which is invisible rather than merely misplaced.
     */
    @Test
    fun `the mapped box stays within the unrotated page at every rotation`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val quarterTurned = rotation == 90 || rotation == 270
            val displayedWidth = if (quarterTurned) portraitHeight else portraitWidth
            val displayedHeight = if (quarterTurned) portraitWidth else portraitHeight

            val placed = AppearancePlacement.map(
                displayedWidth - boxWidth - 50f,
                displayedHeight - boxHeight - 82f,
                boxWidth,
                boxHeight,
                displayedWidth,
                displayedHeight,
                rotation
            )

            assertEquals("rotation $rotation left", true, placed.xPt >= 0f)
            assertEquals("rotation $rotation top", true, placed.yFromTopPt >= 0f)
            assertEquals(
                "rotation $rotation right edge",
                true,
                placed.xPt + placed.widthPt <= portraitWidth
            )
            assertEquals(
                "rotation $rotation bottom edge",
                true,
                placed.yFromTopPt + placed.heightPt <= portraitHeight
            )
        }
    }

    @Test
    fun `a rotation past a full turn is treated as its remainder`() {
        val straight = AppearancePlacement.map(
            boxLeft, boxTop, boxWidth, boxHeight, portraitWidth, portraitHeight, 180
        )
        val wrapped = AppearancePlacement.map(
            boxLeft, boxTop, boxWidth, boxHeight, portraitWidth, portraitHeight, 540
        )
        val negative = AppearancePlacement.map(
            boxLeft, boxTop, boxWidth, boxHeight, portraitWidth, portraitHeight, -180
        )

        assertEquals(straight, wrapped)
        assertEquals(straight, negative)
    }
}
