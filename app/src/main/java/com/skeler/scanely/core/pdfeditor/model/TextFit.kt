package com.skeler.scanely.core.pdfeditor.model

import androidx.compose.ui.geometry.Rect

/**
 * How retyped text is made to fit the space it was given.
 *
 * A replacement is almost never the length of what it replaced, and one extra word wrapping over
 * the line below reads as text vanishing into the page. Condensing is far less noticeable than
 * overprinting, so size is squeezed first and wrapping starts only once squeezing would make the
 * text visibly smaller than its neighbours.
 *
 * Shared by the overlay writer and the on-screen preview: the preview is honest only if it is the
 * same arithmetic.
 */
object TextFit {

    /** Past this the replacement no longer passes as the same font at the same size. */
    const val MIN_FIT_RATIO = 0.72f

    /**
     * The size [sizePt] should become so text measuring [singleLineWidthPt] fits
     * [availableWidthPt]. Returns [sizePt] unchanged when it already fits.
     */
    fun fittedSize(sizePt: Float, singleLineWidthPt: Float, availableWidthPt: Float): Float {
        if (sizePt <= 0f || availableWidthPt <= 0f || singleLineWidthPt <= availableWidthPt) {
            return sizePt
        }
        val ratio = (availableWidthPt / singleLineWidthPt).coerceAtLeast(MIN_FIT_RATIO)
        return sizePt * ratio
    }

    /** Text nearer the right edge than this would have nowhere to go. */
    const val MIN_RUN_WIDTH_PT = 60f

    const val PAGE_MARGIN_PT = 24f

    /**
     * A line-height box running from [startXPt] to the page's right margin.
     *
     * Both edges are worked out defensively rather than assumed ordered. A run in the right margin
     * — a page number, a stamp, a signature block — starts past where the margin would put the
     * box's right edge, and an inverted rectangle lays text out one character per line, which looks
     * exactly like the typed words having been dropped.
     */
    fun runToMargin(startXPt: Float, topPt: Float, bottomPt: Float, pageWidthPt: Float): Rect {
        val margin = (pageWidthPt - PAGE_MARGIN_PT).coerceAtLeast(0f)
        val left = startXPt.coerceIn(0f, margin)
        return Rect(left, topPt, maxOf(left + MIN_RUN_WIDTH_PT, margin), bottomPt)
    }
}
