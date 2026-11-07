package com.skeler.scanely.core.pdfeditor.signature

/**
 * Where a signature's visible box sits on the page, in unrotated PDF space with the origin at the
 * top-left, and how far the stamp itself has to be turned to look upright once the viewer applies
 * the page's rotation.
 */
data class PlacedAppearance(
    val xPt: Float,
    val yFromTopPt: Float,
    val widthPt: Float,
    val heightPt: Float,
    val bitmapRotationDegrees: Int
)

/**
 * Maps a placement box from what the editor showed onto what the PDF stores. Not the same space.
 *
 * pdfium reports page size with /Rotate already applied, so a marking on a landscape-displayed page
 * is authored against landscape dimensions. A signature widget's /Rect is always in the page's own
 * unrotated coordinates. On a rotated page the two differ by a quarter turn, and skipping this puts
 * the signature off the page rather than slightly out. The bitmap turns the opposite way so the
 * mark reads upright.
 *
 * Android-free so every rotation can be tested rather than checked by signing four documents.
 */
internal object AppearancePlacement {

    /**
     * [displayedWidthPt] and [displayedHeightPt] are the page as the editor showed it, and the box
     * is in that same space with the origin at its top-left — the convention every
     * `PdfEdit.Marking` already uses.
     */
    fun map(
        boxLeftPt: Float,
        boxTopPt: Float,
        boxWidthPt: Float,
        boxHeightPt: Float,
        displayedWidthPt: Float,
        displayedHeightPt: Float,
        pageRotationDegrees: Int
    ): PlacedAppearance = when (normalise(pageRotationDegrees)) {
        QUARTER_TURN -> PlacedAppearance(
            xPt = boxTopPt,
            yFromTopPt = displayedWidthPt - boxLeftPt - boxWidthPt,
            widthPt = boxHeightPt,
            heightPt = boxWidthPt,
            bitmapRotationDegrees = THREE_QUARTER_TURN
        )

        HALF_TURN -> PlacedAppearance(
            xPt = displayedWidthPt - boxLeftPt - boxWidthPt,
            yFromTopPt = displayedHeightPt - boxTopPt - boxHeightPt,
            widthPt = boxWidthPt,
            heightPt = boxHeightPt,
            bitmapRotationDegrees = HALF_TURN
        )

        THREE_QUARTER_TURN -> PlacedAppearance(
            xPt = displayedHeightPt - boxTopPt - boxHeightPt,
            yFromTopPt = boxLeftPt,
            widthPt = boxHeightPt,
            heightPt = boxWidthPt,
            bitmapRotationDegrees = QUARTER_TURN
        )

        else -> PlacedAppearance(
            xPt = boxLeftPt,
            yFromTopPt = boxTopPt,
            widthPt = boxWidthPt,
            heightPt = boxHeightPt,
            bitmapRotationDegrees = 0
        )
    }

    /** /Rotate is a multiple of 90 but may be negative or past a full turn. */
    private fun normalise(degrees: Int): Int = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
}

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
private const val FULL_TURN = 360
