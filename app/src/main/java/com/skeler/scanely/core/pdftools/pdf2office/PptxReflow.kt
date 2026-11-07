package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.core.pdftools.pdf2office.exact.Block
import com.skeler.scanely.core.pdftools.pdf2office.exact.OutRun
import com.skeler.scanely.core.pdftools.pdf2office.exact.Segment
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextMeasurer

private const val REFLOW_SLACK_PT = 1.5f
private const val REFLOW_SLACK_EM = 1f
private const val FULL_SCALE_PERCENT = 100

/**
 * Upright left-to-right prose of several lines is one wrapping paragraph; anything else keeps its
 * line breaks. So does a block with raised or lowered text (BERT_LARGE, footnote marks): Impress
 * sets it narrower than the PDF, the joined lines rewrap and every line below them moves.
 */
internal fun pptxReflows(block: Block): Boolean =
    block.lines.size > 1 && block.angleDeg == 0 &&
        block.lines.none { line -> line.rtl || line.bidi || line.tokens.any { it.raisePt != 0f } }

/**
 * A reflowed box's right edge: its longest line plus a little rounding room, so PowerPoint breaks
 * the joined lines where the PDF did and rewraps them when the text is edited.
 */
internal fun reflowRightPt(block: Block): Float =
    block.lines.maxOf { it.u1 } + maxOf(REFLOW_SLACK_PT, block.lines.maxOf { it.sizePt } * REFLOW_SLACK_EM)

/**
 * The PDF ended [previous] early (a paragraph's last line, a list item) when the next line's
 * first word would have fit after it: that break stays a line break, or the word would move up.
 */
internal fun fitsAfter(previous: Segment, line: Segment, rightPt: Float, measurer: TextMeasurer): Boolean {
    val first = line.tokens.first()
    val s = previous.tokens.last().style
    val space = measurer.width(" ", s.family, s.arabic, s.bold, s.italic, s.sizePt)
    return previous.u1 + space + (first.u1 - first.u0) <= rightPt
}

/** "exam-" then "ple": one word again, its hyphen a soft one that shows only where the line still breaks. */
internal fun hyphenJoins(previous: Segment, line: Segment): Boolean {
    val end = previous.tokens.last().text
    return end.length > 1 && end.endsWith('-') && end[end.length - 2].isLetter() &&
        line.tokens.first().text.firstOrNull()?.isLowerCase() == true
}

internal fun joiningSpace(previous: Segment): OutRun =
    OutRun(" ", previous.tokens.last().style, 0, FULL_SCALE_PERCENT, 0)
