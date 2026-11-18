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
    val inkTop: Float = top,
    /** Index of this character's font program in [PdfiumPage.fontPrograms], or -1 when it was not read. */
    val fontProgram: Int = -1,
    /** The marked-content id the character is drawn under (a tagged PDF's link to its structure tree), or -1. */
    val mcid: Int = -1
) {
    /** This character standing for another code point (and [flags]), drawn where it was. */
    fun withCode(code: Int, flags: Int = this.flags): PdfiumChar = PdfiumChar(
        code, originX, originY, left, bottom, right, top, size, matrixA, matrixB, matrixC, matrixD, font, fontFlags,
        weight, argb, flags, renderMode, inkLeft, inkBottom, inkRight, inkTop, fontProgram, mcid
    )

    /**
     * Another glyph of this character's font and size on its line: [code] with origin x [x], [advance]
     * wide, ink [ink] as (left, bottom, right, top).
     */
    fun beside(code: Int, x: Float, advance: Float, ink: FloatArray): PdfiumChar = PdfiumChar(
        code, x, originY, x, bottom, x + advance, top, size, matrixA, matrixB, matrixC, matrixD, font, fontFlags,
        weight, argb, flags, renderMode, ink[INK_LEFT], ink[INK_BOTTOM], ink[INK_RIGHT], ink[INK_TOP], fontProgram, mcid
    )

    /** Its text could not be confirmed from its glyph ([FLAG_UNCERTAIN]): a line of such characters is one to read by OCR. */
    val uncertain: Boolean get() = flags and FLAG_UNCERTAIN != 0

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

    companion object {
        private const val FLAG_GENERATED = 1
        private const val FLAG_IN_FORM = 2
        private const val FLAG_INVISIBLE = 4

        /** Set by the layout, never by pdfium: see [uncertain]. */
        const val FLAG_UNCERTAIN = 8
        private const val INK_LEFT = 0
        private const val INK_BOTTOM = 1
        private const val INK_RIGHT = 2
        private const val INK_TOP = 3
    }
}

/**
 * A ruling line drawn on the page: a horizontal or vertical stroke, as the rectangle its line width
 * covers, or a thin filled rectangle (how Word and most table writers draw cell borders). PDF user
 * space, like the characters.
 */
class PdfiumRule(val left: Float, val bottom: Float, val right: Float, val top: Float)

/** A character of the page as read without the PDF's ToUnicode maps: the glyph it draws, and where. */
class PdfiumGlyphId(val glyph: Int, val originX: Float, val originY: Float)

class PdfiumPage(
    val widthPt: Float,
    val heightPt: Float,
    val chars: List<PdfiumChar>,
    val rules: List<PdfiumRule> = emptyList(),
    /** The embedded font programs of the fonts that draw Arabic text, as the PDF holds them. */
    val fontPrograms: List<ByteArray> = emptyList(),
    /**
     * When the page has [fontPrograms]: its characters read again from a copy whose glyph-id fonts have
     * no ToUnicode map, so each code is the glyph drawn ([Qpdf.nativeWithoutToUnicode]). Empty otherwise.
     */
    val glyphIds: List<PdfiumGlyphId> = emptyList(),
    /** A tagged PDF's structure for this page ([PdfStructure.page]); null when the PDF has none. */
    val tags: PdfPageTags? = null
)

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

/**
 * A tagged PDF's structure tree (ISO 32000 §14.7), flattened: what the program that made the PDF said
 * its content is — paragraphs, headings, list items, table cells, notes — and which marked content of
 * which page belongs to each. Word, PowerPoint and Excel write it by default; a PDF without one has no
 * [PdfStructure] and its structure is inferred from how the page looks.
 *
 * Node `i` has the structure type [types]`[i]` (its /S name, e.g. "P", "H1", "LI") and the parent
 * [parents]`[i]` (-1 for a root). [pageNodes]`[page][mcid]` is the node a marked content id of that
 * page belongs to, or -1.
 */
class PdfStructure(val types: Array<String>, val parents: IntArray, private val pageNodes: Array<IntArray?>) {
    fun page(index: Int): PdfPageTags? = pageNodes.getOrNull(index)?.let { PdfPageTags(this, it) }
}

/** One page's link into a [PdfStructure]: [nodes]`[mcid]` is the structure node, or -1. */
class PdfPageTags(val structure: PdfStructure, val nodes: IntArray) {
    fun node(mcid: Int): Int = if (mcid in nodes.indices) nodes[mcid] else -1
}
