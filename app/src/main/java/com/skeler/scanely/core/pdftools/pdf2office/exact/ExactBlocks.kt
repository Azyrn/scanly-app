package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs

/** Last stage of [ExactLayout]: segments joined into paragraph [Block]s that share one exact pitch. */
internal object ExactBlocks {

    fun blocks(segments: List<Segment>): List<Block> {
        val blocks = ArrayList<Block>()
        var lines = ArrayList<Segment>()
        for (segment in segments) {
            if (lines.isNotEmpty() && !joins(lines, segment)) {
                blocks += block(lines)
                lines = ArrayList()
            }
            lines += segment
        }
        if (lines.isNotEmpty()) blocks += block(lines)
        return blocks
    }

    /** [lines] (that already [joins] line by line) as one block with its pitch and first-line indent. */
    fun block(lines: List<Segment>): Block {
        val p = if (lines.size == 1) singleLineHeight(lines[0]) else pitchOf(lines)
        val indent = if (lines.size == 1) 0f else edge(lines[0]) - edge(lines[1])
        return Block(lines, p, if (lines[0].rtl) -indent else indent)
    }

    /** A single line is as tall as its largest glyph needs, so a big initial isn't clipped. */
    fun singleLineHeight(segment: Segment): Float {
        val tokens = segment.tokens
        val factor = if (tokens.any { it.style.arabic }) ARABIC_LINE_FACTOR else ExactLayout.SINGLE_LINE_FACTOR
        return tokens.maxOf { it.style.sizePt } * factor
    }

    private fun joins(lines: List<Segment>, next: Segment): Boolean {
        val last = lines.last()
        return sameKind(last, next) && pitchFits(last, next) && aligned(lines, next) && evenPitch(lines + next)
    }

    /** Upright, the same direction and about the same size. */
    private fun sameKind(last: Segment, next: Segment): Boolean =
        last.angleDeg == 0 && next.angleDeg == 0 && last.rtl == next.rtl && abs(
            last.sizePt - next.sizePt
        ) <= SIZE_TOLERANCE_PT

    private fun pitchFits(last: Segment, next: Segment): Boolean {
        val delta = last.baselineV - next.baselineV
        // A tight pitch would clip the dots below an Arabic baseline: such lines get a box each.
        if (last.tokens.any { it.style.arabic } && delta < last.sizePt * ARABIC_LINE_FACTOR) return false
        return delta >= last.sizePt * PITCH_MIN && delta <= last.sizePt * PITCH_MAX
    }

    private fun aligned(lines: List<Segment>, next: Segment): Boolean {
        val last = lines.last()
        val reference = if (lines.size >= 2) edge(lines[1]) else edge(last)
        // A first-line indent is only modelled for left-to-right text; right-to-left lines must align.
        val limit = if (lines.size == 1 && !last.rtl) last.sizePt * INDENT_LIMIT else EDGE_TOLERANCE_PT
        return abs(edge(next) - reference) <= limit
    }

    /**
     * One exact pitch carries every line of a block, so every baseline must sit where that pitch
     * puts it; otherwise the error adds up line by line.
     */
    private fun evenPitch(candidate: List<Segment>): Boolean {
        val pitch = pitchOf(candidate)
        val first = candidate.first().baselineV
        return candidate.withIndex().all { (i, line) -> abs(first - i * pitch - line.baselineV) <= PITCH_TOLERANCE_PT }
    }

    /** LibreOffice clips a line to its exact height; Arabic dots and marks below the baseline need this much (x size). */
    private const val ARABIC_LINE_FACTOR = 1.7f
    private const val EDGE_TOLERANCE_PT = 1.5f
    private const val INDENT_LIMIT = 6f
    private const val PITCH_MIN = 0.9f
    private const val PITCH_MAX = 2.5f
    private const val PITCH_TOLERANCE_PT = 0.3f
    private const val SIZE_TOLERANCE_PT = 0.6f
}

/** The one line pitch that fits every baseline of a block, first to last. */
private fun pitchOf(lines: List<Segment>): Float = (lines.first().baselineV - lines.last().baselineV) / (lines.size - 1)

/** The edge a block's lines line up on: left for left-to-right text, right for right-to-left. */
private fun edge(segment: Segment): Float = if (segment.rtl) segment.u1 else segment.u0
