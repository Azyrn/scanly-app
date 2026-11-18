package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class Align { LEFT, CENTER, RIGHT }

/** How one flowing paragraph is set: its exact line, where its lines start and wrap, and its tab stops. */
internal class ParaSetting(
    val pitchPt: Float,
    val align: Align,
    /** Where the first line's text starts, and the lines after it. */
    val firstPt: Float,
    val leftPt: Float,
    /** The paragraph wraps at this x: just past its widest line. */
    val wrapPt: Float,
    val stops: List<Float> = emptyList(),
    /** Every line ends in a line break: a line holds a long link, which Word would break at its own place. */
    val hardBreaks: Boolean = false
) {
    val lineTwips: Int = (pitchPt * TWIPS).roundToInt().coerceAtLeast(1)
}

/** Works out a flowing paragraph's [ParaSetting] from its lines on the page. */
internal object FlowSettings {

    /** Room past a paragraph's widest line, so a rounding difference never wraps it (as a reflowing text box). */
    const val WRAP_SLACK_PT = 1.5f
    const val EDGE_TOLERANCE_PT = 1.5f
    private const val SINGLE_SLACK_EM = 2f

    /**
     * [joined]: the next page's first paragraph when this one runs on into it. [labelStop]: where a
     * numbered item's text starts, a tab stop of its own unless that is the indent. [bodyRightPt]: the
     * right edge of the section's body.
     */
    fun of(para: Para, joined: Para?, labelStop: Float?, bodyRightPt: Float): ParaSetting {
        val pitch = if (joined != null) joinedPitch(para, joined) else para.pitchPt
        val lines = para.lines + joined?.lines.orEmpty()
        return when (alignment(lines)) {
            Align.CENTER -> centred(lines, pitch)
            Align.RIGHT -> rightAligned(lines, pitch)
            Align.LEFT -> {
                val left = if (para.lines.size == 1 && joined != null) joined.lines.first().u0 else para.leftPt
                leftAligned(lines, pitch, left, labelStop, bodyRightPt)
            }
        }
    }

    /** The pitch of a paragraph that runs over a page: its first part's, unless that is a single line. */
    fun joinedPitch(last: Para, first: Para): Float =
        if (last.lines.size == 1 && first.lines.size > 1) first.pitchPt else last.pitchPt

    private fun centred(lines: List<ParaLine>, pitch: Float): ParaSetting {
        val centre = lines.map { (it.u0 + it.u1) / 2 }.average().toFloat()
        val half = lines.maxOf { it.u1 - it.u0 } / 2 + WRAP_SLACK_PT
        return ParaSetting(pitch, Align.CENTER, centre - half, centre - half, centre + half)
    }

    private fun rightAligned(lines: List<ParaLine>, pitch: Float): ParaSetting {
        val right = lines.maxOf { it.u1 }
        val left = right - lines.maxOf { it.u1 - it.u0 } - WRAP_SLACK_PT * 2
        return ParaSetting(pitch, Align.RIGHT, left, left, right)
    }

    private fun leftAligned(
        lines: List<ParaLine>,
        pitch: Float,
        left: Float,
        labelStop: Float?,
        bodyRightPt: Float
    ): ParaSetting {
        val stops = lines.flatMap { line -> line.segments.drop(1).map { it.u0 } } +
            listOfNotNull(labelStop?.takeIf { abs(it - left) > EDGE_TOLERANCE_PT })
        val hard = lines.zipWithNext().any { (line, next) -> brokenLink(line.segments.last(), next.segments.first()) }
        val widest = lines.maxBy { it.u1 }
        // Lines that never wrap get room to the body's edge, so no rounding of a width breaks one.
        val wrap = if (lines.size == 1 || hard) {
            max(widest.u1 + widest.sizePt * SINGLE_SLACK_EM, bodyRightPt)
        } else {
            widest.u1 + WRAP_SLACK_PT
        }
        return ParaSetting(pitch, Align.LEFT, lines.first().u0, left, wrap, stops.distinct().sorted(), hard)
    }

    /** Lines that share a centre but not a left edge are centred; a shared right edge alone is right-aligned. */
    private fun alignment(lines: List<ParaLine>): Align {
        if (lines.size < 2 || lines.any { it.segments.size > 1 }) return Align.LEFT
        val centres = lines.map { (it.u0 + it.u1) / 2 }
        val rights = lines.map { it.u1 }
        return when {
            leftAligned(lines) -> Align.LEFT
            spread(centres) <= EDGE_TOLERANCE_PT * 2 -> Align.CENTER
            lines.size > 2 && spread(rights) <= EDGE_TOLERANCE_PT -> Align.RIGHT
            else -> Align.LEFT
        }
    }

    /** The lines after the first share a left edge, or the first two start together. */
    private fun leftAligned(lines: List<ParaLine>): Boolean {
        val sameLeft = lines.size > 2 && spread(lines.drop(1).map { it.u0 }) <= EDGE_TOLERANCE_PT
        return sameLeft || abs(lines[0].u0 - lines[1].u0) <= EDGE_TOLERANCE_PT
    }

    private fun spread(values: List<Float>): Float = values.max() - values.min()
}
