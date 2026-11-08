package com.skeler.scanely.core.idcard

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** What is being copied: its true size and how many sides it has. */
enum class IdDocumentKind(val widthMm: Float, val heightMm: Float, val sides: Int) {
    /** ISO/IEC 7810 ID-1: national ID cards, residence permits, driving licences, bank cards. */
    ID_CARD(85.60f, 53.98f, 2),

    /** ISO/IEC 7810 ID-3: a passport's photo page. */
    PASSPORT(125f, 88f, 1);

    val aspect: Float get() = widthMm / heightMm

    /** Corner radius of the real card, used for the clip; passports have square-ish pages. */
    val cornerMm: Float get() = if (this == ID_CARD) 3.18f else 1f
}

/** A rectangle in page pixels. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where one scanned side goes on the page.
 *
 * [box] is the true-size slot for the document; [image] is where the scan is drawn inside it — equal to
 * [box] when the scan was snapped to true size, smaller (centred) when its shape was too far off to
 * stretch. [rotate90] tells the renderer to turn a portrait scan to landscape first.
 */
data class SidePlacement(val box: PxRect, val image: PxRect, val rotate90: Boolean, val snapped: Boolean)

/**
 * Photocopier-style A4 layout: every side at its true printed size (or one enlargement), centred horizontally, the sides
 * stacked with a gap and the group centred vertically. Pure geometry — page pixels at [dpi].
 */
object IdCardLayout {

    const val A4_WIDTH_MM = 210f
    const val A4_HEIGHT_MM = 297f
    const val DEFAULT_DPI = 300

    /** Gap between front and back, like the space a person leaves on a copier glass. */
    const val GAP_MM = 16f

    /** Clear space kept on every page edge at any size: printers can't print the outer ~5 mm. */
    const val MARGIN_MM = 10f

    /** The sizes offered after true size (1.0), as copier enlargements; only those that fit are offered. */
    private val ENLARGEMENTS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    /** A scan whose shape is within this of the true shape is stretched to true size (crop wobble). */
    const val SNAP_TOLERANCE = 0.08f

    fun mmToPx(mm: Float, dpi: Int = DEFAULT_DPI): Int = (mm / MM_PER_INCH * dpi).roundToInt()

    private const val MM_PER_INCH = 25.4f

    fun pageWidthPx(dpi: Int = DEFAULT_DPI) = mmToPx(A4_WIDTH_MM, dpi)

    fun pageHeightPx(dpi: Int = DEFAULT_DPI) = mmToPx(A4_HEIGHT_MM, dpi)

    /** The largest scale at which every side of [kind], with the gap, fits inside the page margins. */
    fun maxScale(kind: IdDocumentKind): Float {
        val byWidth = (A4_WIDTH_MM - 2 * MARGIN_MM) / kind.widthMm
        val byHeight = (A4_HEIGHT_MM - 2 * MARGIN_MM - (kind.sides - 1) * GAP_MM) / (kind.sides * kind.heightMm)
        return min(byWidth, byHeight)
    }

    /** The sizes the user can pick for [kind]: true size first, then the enlargements that fit. */
    fun sizeSteps(kind: IdDocumentKind): List<Float> = ENLARGEMENTS.filter { it <= maxScale(kind) }

    /**
     * @param scale 1 = true size (the default, what a copier does); larger enlarges every side alike,
     * clamped to [maxScale] so the page never overflows.
     * @param sides pixel sizes (width to height) of the scans, in page order; at most [IdDocumentKind.sides]
     * are placed, extra scans are ignored by the caller.
     */
    fun layout(
        kind: IdDocumentKind,
        sides: List<Pair<Int, Int>>,
        scale: Float = 1f,
        dpi: Int = DEFAULT_DPI
    ): List<SidePlacement> {
        if (sides.isEmpty()) return emptyList()
        val placed = sides.take(kind.sides)
        val s = scale.coerceIn(1f, maxScale(kind))
        val boxW = mmToPx(kind.widthMm * s, dpi)
        val boxH = mmToPx(kind.heightMm * s, dpi)
        val gap = mmToPx(GAP_MM, dpi)
        val pageW = pageWidthPx(dpi)
        val pageH = pageHeightPx(dpi)
        val groupH = placed.size * boxH + (placed.size - 1) * gap
        val left = (pageW - boxW) / 2
        var top = (pageH - groupH) / 2
        return placed.map { (w, h) ->
            val box = PxRect(left, top, left + boxW, top + boxH)
            top += boxH + gap
            place(kind, box, w, h)
        }
    }

    private fun place(kind: IdDocumentKind, box: PxRect, w: Int, h: Int): SidePlacement {
        val rotate = h > w
        val (sw, sh) = if (rotate) h to w else w to h
        val aspect = sw.toFloat() / sh
        val off = abs(aspect / kind.aspect - 1f)
        if (off <= SNAP_TOLERANCE) return SidePlacement(box, box, rotate, snapped = true)
        // Too far off to stretch without distorting faces and text: fit inside the slot, centred.
        val scale = min(box.width.toFloat() / sw, box.height.toFloat() / sh)
        val iw = (sw * scale).roundToInt()
        val ih = (sh * scale).roundToInt()
        val il = box.left + (box.width - iw) / 2
        val it = box.top + (box.height - ih) / 2
        return SidePlacement(box, PxRect(il, it, il + iw, it + ih), rotate, snapped = false)
    }
}
