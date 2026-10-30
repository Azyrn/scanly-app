package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumPage
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox

/** How a run of text looks. [sizePt] is the size the reader sees (text matrix applied). */
data class TextStyle(
    val family: OfficeFamily,
    val arabic: Boolean,
    val bold: Boolean,
    val italic: Boolean,
    val sizePt: Float,
    val argb: Int
)

/**
 * A word or part of one in a single style. [u0]/[u1] are its extent along the baseline in points
 * (page x for upright text); [raisePt] lifts a superscript above the line's baseline.
 */
class Token(
    val text: String,
    val style: TextStyle,
    val u0: Float,
    val u1: Float,
    val spaceBefore: Boolean,
    val raisePt: Float,
    /** On a bidirectional line: the width on the page of the space before this token in reading order. */
    val gapBeforePt: Float = 0f
)

/**
 * One stretch of text on one baseline with no column gap inside it. [baselineV] is the baseline's
 * offset across the text direction, in PDF user space (y up for upright text). Tokens are in
 * logical (reading) order; for [rtl] lines that runs right to left on the page.
 */
class Segment(
    val tokens: List<Token>,
    val baselineV: Float,
    val sizePt: Float,
    val rtl: Boolean,
    val angleDeg: Int,
    /** Holds right-to-left text: tokens are in reading order, which is not their order on the page. */
    val bidi: Boolean = false
) {
    val u0: Float get() = tokens.minOf { it.u0 }
    val u1: Float get() = tokens.maxOf { it.u1 }
}

/**
 * Lines that share an edge and a regular pitch, written as one text box holding one paragraph with
 * a line break per line: editable as a paragraph, and every line still lands where it was.
 * [indentPt] is the first line's offset from the others (negative for a hanging first line).
 */
class Block(val lines: List<Segment>, val pitchPt: Float, val indentPt: Float) {
    val rtl: Boolean get() = lines.first().rtl
    val angleDeg: Int get() = lines.first().angleDeg
}

class ExactPageLayout(
    val widthPt: Float,
    val heightPt: Float,
    val blocks: List<Block>,
    /** The text sits behind the page's picture: found and selected in Word, never drawn over the scan. */
    val behindPicture: Boolean = false,
    /** Text laid out as tables ([ExactTables]); none of it is also in [blocks]. */
    val tables: List<ExactTable> = emptyList(),
    /** A possible table was left as text until the layout model looks at the page ([LayoutTables.Found.unconfirmed]). */
    val unconfirmedTables: Boolean = false
) {
    val isEmpty: Boolean get() = blocks.isEmpty() && tables.isEmpty()

    fun copy(unconfirmedTables: Boolean): ExactPageLayout =
        ExactPageLayout(widthPt, heightPt, blocks, behindPicture, tables, unconfirmedTables)
}

/**
 * Turns pdfium's characters into positioned [Block]s. Pure geometry: no Android, no I/O, so it is
 * tested on the JVM with the corpus's real shapes reduced to fixtures. The stages:
 * [ExactGlyphs] (characters to glyphs), [ExactLines] (glyphs to lines and segments, with
 * [ExactBidiSegments] for right-to-left lines), [ExactBlocks] (segments to paragraph blocks).
 */
object ExactLayout {

    /** Default line height for a single line, as a multiple of the font size. */
    const val SINGLE_LINE_FACTOR = 1.2f

    /** LibreOffice (and, unconfirmed, Word) put an exact-height line's baseline at 80% of its height. */
    const val BASELINE_RATIO = 0.8f

    private const val OFF_PAGE_PT = 2f

    /**
     * A page's text as the converters write it: blocks, with tables found and taken out of them.
     * The one entry point for the app and the host fast loop, so the two can't drift apart.
     */
    fun read(page: PdfiumPage, modelTables: List<LayoutBox>? = null): ExactPageLayout =
        ExactTables.detect(analyze(page), modelTables)

    fun analyze(page: PdfiumPage): ExactPageLayout {
        // Text drawn outside the page (a print stamp above the crop box) is never seen in the PDF; placed
        // in an Office page it would land on it, or be pulled to its edge.
        val onPage = page.chars.filter {
            it.originX in -OFF_PAGE_PT..page.widthPt + OFF_PAGE_PT &&
                it.originY in -OFF_PAGE_PT..page.heightPt + OFF_PAGE_PT
        }
        val segments = ExactLines.lines(ExactGlyphs.glyphs(onPage)).flatMap(ExactLines::segments)
        return ExactPageLayout(page.widthPt, page.heightPt, ExactBlocks.blocks(segments))
    }

    /** [lines] (that already join line by line) as one block with its pitch and first-line indent. */
    internal fun block(lines: List<Segment>): Block = ExactBlocks.block(lines)

    /** A single line is as tall as its largest glyph needs, so a big initial isn't clipped. */
    internal fun singleLineHeight(segment: Segment): Float = ExactBlocks.singleLineHeight(segment)
}
