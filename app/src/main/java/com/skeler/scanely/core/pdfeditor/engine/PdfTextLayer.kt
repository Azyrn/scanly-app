package com.skeler.scanely.core.pdfeditor.engine

import androidx.compose.ui.geometry.Rect
import io.legere.pdfiumandroid.PdfTextPage

/**
 * The text layer of one page, in top-left-origin page points.
 *
 * pdfium reports geometry in PDF user space (origin bottom-left, y increasing upward); every
 * rectangle leaving this class is flipped to the top-left origin the canvas and [PdfEdit] model
 * use, so no other layer has to think about it.
 */
class PdfTextLayer(
    private val textPage: PdfTextPage,
    private val pageHeightPt: Float
) {

    val charCount: Int get() = textPage.textPageCountChars()

    fun textIn(range: IntRange): String =
        if (range.isEmpty()) "" else textPage.textPageGetText(range.first, range.count()).orEmpty()

    /** Null when the point isn't on a glyph — the caller should fall back to free-form marking. */
    fun charIndexAt(x: Float, y: Float, tolerancePt: Float = DEFAULT_TOLERANCE_PT): Int? {
        val index = textPage.textPageGetCharIndexAtPos(
            x.toDouble(),
            flipY(y).toDouble(),
            tolerancePt.toDouble(),
            tolerancePt.toDouble()
        )
        return index.takeIf { it >= 0 }
    }

    /**
     * One character's own box. Null for a character pdfium places nowhere — a space at a line
     * break, a soft hyphen — which is a real character in the text with no position on the page.
     *
     * Cheaper and tighter than asking for the range's rectangles: one native call instead of two,
     * and the glyph's own extent rather than a run rectangle merged with its neighbours, which is
     * what a baseline has to be measured from.
     */
    fun charBox(index: Int): Rect? {
        val box = runCatching { textPage.textPageGetCharBox(index) }.getOrNull() ?: return null
        val rect = flip(box.left, box.top, box.right, box.bottom)
        return rect.takeIf { it.width > 0f && it.height > 0f }
    }

    /** Zero where pdfium has no size for the character, which the callers treat as "unknown". */
    fun fontSizeAt(index: Int): Float =
        runCatching { textPage.getFontSize(index).toFloat() }.getOrDefault(0f)

    /**
     * The size the replacement text should be drawn at. Uses the most common size in the range
     * rather than the first character's, so a run starting with a drop cap or a superscript
     * doesn't drag the whole line to the wrong size.
     */
    fun dominantFontSize(range: IntRange): Float {
        if (range.isEmpty()) return DEFAULT_FONT_SIZE_PT
        return range
            .map { fontSizeAt(it) }
            .filter { it > 0f }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: DEFAULT_FONT_SIZE_PT
    }

    /** Word boundaries by whitespace, which is what a double-tap should select. */
    fun wordAt(index: Int): IntRange {
        if (index !in 0 until charCount) return IntRange.EMPTY
        var start = index
        var end = index
        while (start > 0 && !textPage.textPageGetUnicode(start - 1).isWhitespace()) start--
        while (end + 1 < charCount && !textPage.textPageGetUnicode(end + 1).isWhitespace()) end++
        return start..end
    }

    /**
     * The visual line containing [index], found by walking out while the vertical centre stays
     * within half a line height. Used to seed the cover rectangle when retyping.
     */
    fun lineAt(index: Int): IntRange {
        if (index !in 0 until charCount) return IntRange.EMPTY
        val anchor = textPage.textPageGetCharBox(index) ?: return index..index
        val centre = (anchor.top + anchor.bottom) / 2f
        val tolerance = kotlin.math.abs(anchor.top - anchor.bottom).coerceAtLeast(1f) / 2f

        var start = index
        var end = index
        while (start > 0 && sharesLine(start - 1, centre, tolerance)) start--
        while (end + 1 < charCount && sharesLine(end + 1, centre, tolerance)) end++
        return start..end
    }

    private fun sharesLine(index: Int, centre: Float, tolerance: Float): Boolean {
        val box = textPage.textPageGetCharBox(index) ?: return false
        return kotlin.math.abs((box.top + box.bottom) / 2f - centre) <= tolerance
    }

    private fun flipY(y: Float): Float = pageHeightPt - y

    private fun flip(left: Float, top: Float, right: Float, bottom: Float): Rect {
        val yTop = pageHeightPt - maxOf(top, bottom)
        val yBottom = pageHeightPt - minOf(top, bottom)
        return Rect(minOf(left, right), yTop, maxOf(left, right), yBottom)
    }

    private companion object {
        const val DEFAULT_TOLERANCE_PT = 6f
        const val DEFAULT_FONT_SIZE_PT = 11f
    }
}
