package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One run of a written line. [spacingTwips] is added after every character of the run (Word's
 * `w:spacing`, twentieths of a point) and is never negative; [scalePercent] is `w:w`, the
 * horizontal scale of the glyphs; [raiseHalfPoints] is `w:position`.
 */
class OutRun(
    val text: String,
    val style: TextStyle,
    val spacingTwips: Int,
    val scalePercent: Int,
    val raiseHalfPoints: Int
)

/**
 * Makes a line come out the width it had in the PDF whatever font the reader substitutes, as long
 * as it is the metric twin [TextMeasurer] measured with. Every word is stretched to its PDF width
 * (or up to the next word when no space separates them) and every space to fill the gap before the
 * next word.
 *
 * Controls: character spacing, added after every character (ECMA-376 §17.3.2.35), and horizontal glyph
 * scaling (`w:w`). LibreOffice drops a negative value after a run's last character (a one-character
 * space run with negative spacing is ignored outright), so a space that must shrink is scaled to the
 * nearest percent and topped up with positive spacing; a word up to [SHRINK_BY_SPACING] too wide
 * shrinks by negative spacing between its letters, which scored better than scaling or leaving it wide.
 *
 * Tuned against the fidelity corpus (scripts/fidelity): of the variants tried — exact per-letter
 * twips, scale-only, tracking the written width instead of the target — this one scored best on
 * every file.
 */
object ExactRuns {

    private const val TWIPS_PER_POINT = 20f
    private const val MAX_TWIPS = 31_680
    private const val FULL_SCALE = 100
    private const val MIN_SCALE = 1
    private const val ARABIC_NATURAL = 0.06f
    private const val ARABIC_BEARING_EM = 0.08f
    private const val MIN_ARABIC_SCALE = 40
    private const val MAX_ARABIC_SCALE = 250

    private const val SHRINK_BY_SPACING = 0.12f

    /** Half a twip either way and the nearest whole percent of a space are about this much. */
    private const val CLOSE_ENOUGH_PT = 0.06f

    /** [startPt] is where the line begins along its reading direction (left edge, or right for RTL). */
    fun runs(segment: Segment, startPt: Float, measurer: TextMeasurer): List<OutRun> {
        if (segment.bidi) return bidiRuns(segment, measurer)
        val out = ArrayList<OutRun>()
        val tokens = segment.tokens
        var cursor = if (segment.rtl) -startPt else startPt
        for ((index, token) in tokens.withIndex()) {
            val next = tokens.getOrNull(index + 1)
            val word = fitted(token.text, token.style, reach(segment, token, next) - cursor, raise(token), measurer)
            out += word
            // Where the word really ends (a twip of rounding per letter can add up along a line), so
            // the space after it fills the gap that is left, not the gap the word was meant to leave.
            cursor += written(word, measurer)
            if (next != null && next.spaceBefore) {
                val gap = along(segment, next) - cursor
                out += fitted(" ", next.style, gap, raise(next), measurer)
                cursor += gap
            }
        }
        return merge(out)
    }

    /**
     * A line with right-to-left text. Its tokens are in reading order, which on the page runs back
     * and forth, so positions are not walked as a cursor from the line's edge: every word is fitted
     * to its own width on the page and every space to its own gap, and Word's layout of the visual
     * order puts each where it was.
     *
     * Arabic letters join, so a word is never given letter spacing or split into two runs. It is
     * left at its natural width when that is within [ARABIC_NATURAL] of the page's (LibreOffice
     * draws a word scaled with `w:w` with a dotless final yeh), and the space after it takes up the
     * difference; only a word that is further off is scaled, as a whole, which keeps the shaping.
     */
    private fun bidiRuns(segment: Segment, measurer: TextMeasurer): List<OutRun> {
        val out = ArrayList<OutRun>()
        val tokens = segment.tokens
        // How much wider than the page the line has come out so far; the next space gives it back.
        var excess = 0f
        for ((index, token) in tokens.withIndex()) {
            if (token.spaceBefore) {
                val previous = tokens.getOrNull(index - 1)
                // A space between two Arabic words is Arabic, so it stays in their right-to-left run.
                val style = token.style.copy(arabic = token.style.arabic && (previous?.style?.arabic ?: true))
                // The gap between two drawn extents is the space less the side bearings both words add.
                val wanted = token.gapBeforePt - excess - bearing(token, previous)
                excess = if (wanted >= 0f) 0f else -wanted
                out += fitted(" ", style, wanted.coerceAtLeast(0f), 0, measurer)
            }
            val target = token.u1 - token.u0
            if (token.style.arabic) {
                // The page gives the drawn extent of the word; the font lays out its advance, which is
                // wider by the bearings of the first and last letters.
                val run = arabicWord(token, target + token.style.sizePt * ARABIC_BEARING_EM, measurer)
                out += run
                excess += measure(token.text, token.style, measurer) * run.scalePercent / FULL_SCALE - target -
                    token.style.sizePt * ARABIC_BEARING_EM
            } else {
                out += fitted(token.text, token.style, target, raise(token), measurer)
            }
        }
        return merge(out)
    }

    /** What a space between two Arabic words loses to their bearings; zero beside anything else. */
    private fun bearing(token: Token, previous: Token?): Float =
        if (token.style.arabic && previous?.style?.arabic == true) token.style.sizePt * ARABIC_BEARING_EM else 0f

    private fun arabicWord(token: Token, targetPt: Float, measurer: TextMeasurer): OutRun {
        val measured = measure(token.text, token.style, measurer)
        val percent = if (measured <= 0f || abs(targetPt - measured) <= measured * ARABIC_NATURAL) {
            FULL_SCALE
        } else {
            (targetPt / measured * FULL_SCALE).roundToInt().coerceIn(MIN_ARABIC_SCALE, MAX_ARABIC_SCALE)
        }
        return OutRun(token.text, token.style, 0, percent, raise(token))
    }

    /**
     * [text] made [targetPt] wide: scaled down to the nearest percent when it measures wider, then
     * topped up with positive spacing, split between two runs a twip apart so the total is right to
     * within a twip rather than half a twip per letter.
     */
    private fun fitted(
        text: String,
        style: TextStyle,
        targetPt: Float,
        raise: Int,
        measurer: TextMeasurer
    ): List<OutRun> {
        val measured = measure(text, style, measurer)
        // Within a few hundredths of a point is as close as twips and whole percents can get.
        if (abs(targetPt - measured) <= CLOSE_ENOUGH_PT) return listOf(OutRun(text, style, 0, FULL_SCALE, raise))
        val slightlyWide = measured > 0f && targetPt < measured && measured - targetPt <= measured * SHRINK_BY_SPACING
        if (slightlyWide && text.codePointCount(0, text.length) >= 2) {
            // A word a little too wide shrinks by negative spacing between its letters (LibreOffice
            // drops the one after the run's last letter, so there is one gap fewer than letters;
            // verified on a test document). One run: splitting the word to spread the rounding
            // evenly measured worse, every run boundary costs more than the twip it saves.
            val gaps = text.codePointCount(0, text.length) - 1
            val twips = ((targetPt - measured) * TWIPS_PER_POINT / gaps).roundToInt()
            return listOf(OutRun(text, style, twips, FULL_SCALE, raise))
        }
        val scale = if (measured > 0f && targetPt < measured) {
            (targetPt.coerceAtLeast(0f) / measured * FULL_SCALE).roundToInt().coerceIn(MIN_SCALE, FULL_SCALE)
        } else {
            FULL_SCALE
        }
        return spaced(text, style, (targetPt - measured * scale / FULL_SCALE).coerceAtLeast(0f), scale, raise)
    }

    /** [text] at [scale] with [remainder] points spread as letter spacing, a twip more on the last letters. */
    private fun spaced(text: String, style: TextStyle, remainder: Float, scale: Int, raise: Int): List<OutRun> {
        val points = text.codePoints().toArray()
        val letters = points.size.coerceAtLeast(1)
        val total = (remainder * TWIPS_PER_POINT).roundToInt()
        val base = (total / letters).coerceAtMost(MAX_TWIPS)
        val wider = (total - base * letters).coerceIn(0, letters)
        if (wider == 0 || points.size < 2) return listOf(OutRun(text, style, base, scale, raise))
        return listOf(
            OutRun(String(points, 0, letters - wider), style, base, scale, raise),
            OutRun(String(points, letters - wider, wider), style, (base + 1).coerceAtMost(MAX_TWIPS), scale, raise)
        )
    }

    /** Width LibreOffice gives [runs]: glyphs scaled, spacing after every letter but a negative one after the last. */
    private fun written(runs: List<OutRun>, measurer: TextMeasurer): Float {
        var width = 0f
        for ((i, run) in runs.withIndex()) {
            val letters = run.text.codePointCount(0, run.text.length)
            val counted = if (run.spacingTwips < 0 && i == runs.lastIndex) letters - 1 else letters
            width += measure(
                run.text,
                run.style,
                measurer
            ) * run.scalePercent / FULL_SCALE + run.spacingTwips * counted / TWIPS_PER_POINT
        }
        return width
    }

    private fun measure(text: String, style: TextStyle, measurer: TextMeasurer): Float =
        measurer.width(text, style.family, style.arabic, style.bold, style.italic, style.sizePt)

    /** Neighbouring runs with identical properties become one, so a plain line is few runs. */
    private fun merge(runs: List<OutRun>): List<OutRun> {
        val merged = ArrayList<OutRun>(runs.size)
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.sameLook(run)) {
                merged[merged.lastIndex] =
                    OutRun(last.text + run.text, last.style, last.spacingTwips, last.scalePercent, last.raiseHalfPoints)
            } else {
                merged += run
            }
        }
        return merged
    }
}

/** A position along [segment]'s reading direction: x for left-to-right, mirrored for right-to-left. */
private fun along(segment: Segment, token: Token): Float = if (segment.rtl) -token.u1 else token.u0

/** Where [token] has to end: right up to [next] when no space comes between them, else its own end. */
private fun reach(segment: Segment, token: Token, next: Token?): Float =
    if (next != null && !next.spaceBefore) along(segment, next) else along(segment, token) + (token.u1 - token.u0)

private fun OutRun.sameLook(other: OutRun): Boolean = style == other.style && spacingTwips == other.spacingTwips &&
    scalePercent == other.scalePercent && raiseHalfPoints == other.raiseHalfPoints

/** A superscript's lift in the half-points both writers use. */
private fun raise(token: Token): Int = (token.raisePt * 2).roundToInt()
