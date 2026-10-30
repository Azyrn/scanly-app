package com.skeler.scanely.core.pdfeditor.engine

/**
 * One character as pdfium sees it, in PDF user space (origin bottom-left, y up).
 *
 * [size] is the font size pdfium reports, which is the nominal `Tf` size: a file that sets 1 pt
 * and scales glyphs with the text matrix reports 1 here. [effectiveSize] multiplies the matrix in,
 * which is the size the reader actually sees.
 */
class PdfiumChar(
    val code: Int,
    val originX: Float,
    val originY: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    val size: Float,
    val matrixA: Float,
    val matrixB: Float,
    val matrixC: Float,
    val matrixD: Float,
    val font: String,
    val fontFlags: Int,
    val weight: Int,
    val argb: Int,
    val flags: Int,
    val renderMode: Int,
    /** The glyph's drawn extent; the loose box (advance width) when pdfium has none. */
    val inkLeft: Float = left,
    val inkBottom: Float = bottom,
    val inkRight: Float = right,
    val inkTop: Float = top
) {
    /** Vertical scale of the text matrix applied to the nominal size. */
    val effectiveSize: Float
        get() = size * kotlin.math.hypot(matrixC, matrixD).let { if (it > 0f) it else 1f }

    /** Inserted by pdfium (a space or line break it inferred), not drawn on the page. */
    val generated: Boolean get() = flags and FLAG_GENERATED != 0

    /** Inside a form XObject: stays in the background picture rather than becoming editable text. */
    val inForm: Boolean get() = flags and FLAG_IN_FORM != 0

    /** Render mode 3: present for search (often an OCR layer) but not painted. */
    val invisible: Boolean get() = flags and FLAG_INVISIBLE != 0

    /** Text matrix rotation in degrees, counter-clockwise; 0 for upright text. */
    val angle: Float get() = Math.toDegrees(kotlin.math.atan2(matrixB, matrixA).toDouble()).toFloat()

    private companion object {
        const val FLAG_GENERATED = 1
        const val FLAG_IN_FORM = 2
        const val FLAG_INVISIBLE = 4
    }
}

class PdfiumPage(val widthPt: Float, val heightPt: Float, val chars: List<PdfiumChar>)

/**
 * A top-level image object that can be written as a picture of its own (upright and opaque): its
 * index among the page's objects, its bounds in PDF user space, and whether its own bytes are a
 * plain JPEG that can be copied out unchanged.
 */
class PdfiumImage(
    val objectIndex: Int,
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    val copyableJpeg: Boolean
)
