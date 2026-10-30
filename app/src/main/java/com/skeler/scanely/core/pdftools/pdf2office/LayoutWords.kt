package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.abs
import kotlin.math.max

/**
 * Characters into words: a word breaks at whitespace, at a character the layer places nowhere,
 * and at a gap wider than a quarter em (or a jump backwards, or onto another line).
 */
internal object LayoutWords {

    fun words(chars: List<LayoutChar>): List<LayoutWord> {
        val builder = WordBuilder()
        for ((index, char) in chars.withIndex()) {
            when {
                char.char in SOFT_HYPHENS || isLineEndHyphen(chars, index) -> builder.softHyphen()
                breaksWord(char) -> builder.flush()
                else -> builder.add(char)
            }
        }
        builder.flush()
        return builder.words
    }

    /** pdfium's unmapped glyph (U+FFFE) drawn at the end of a line is the hyphen it broke a word with. */
    private fun isLineEndHyphen(chars: List<LayoutChar>, index: Int): Boolean =
        chars[index].char == UNMAPPED && chars[index].hasBox && endsLine(chars, index)

    /** Whitespace, a character placed nowhere, or one that doesn't print. */
    private fun breaksWord(char: LayoutChar): Boolean = !char.hasBox || char.char.isWhitespace() || !isPrintable(
        char.char
    )

    /** The word being collected, plus every word finished so far. */
    private class WordBuilder {
        val words = ArrayList<LayoutWord>()
        private val text = StringBuilder()
        private var box: LayoutBox? = null
        private val sizes = ArrayList<Float>()
        private var maxHeight = 0f
        private var previous: LayoutChar? = null

        /** A soft hyphen inside a word is kept as a plain one; one before any letter is dropped. */
        fun softHyphen() {
            if (box != null) text.append('-')
        }

        fun add(char: LayoutChar) {
            val last = previous
            // A combining mark (an Arabic tanween or shadda, an accent) sits above or below its
            // letter rather than after it: it belongs to the open word, and the next letter is
            // measured from the letter, not from the mark.
            if (last != null && Character.getType(char.char) == Character.NON_SPACING_MARK.toInt()) {
                text.append(char.char)
                return
            }
            if (last != null && startsNewWord(last, char)) flush()
            val charBox = LayoutBox(char.left, char.top, char.right, char.bottom)
            box = box?.union(charBox) ?: charBox
            text.append(char.char)
            if (char.fontSize > 0f) sizes += char.fontSize
            maxHeight = max(maxHeight, char.bottom - char.top)
            previous = char
        }

        fun flush() {
            val wordBox = box
            if (wordBox != null && text.isNotBlank()) {
                words += LayoutWord(text.toString(), wordBox, wordSize(sizes, maxHeight))
            }
            text.setLength(0)
            box = null
            sizes.clear()
            maxHeight = 0f
            previous = null
        }
    }

    /**
     * pdfium reports a hyphen that breaks a word at the end of a line as U+FFFE — the same code
     * it uses for a glyph it can't map. Only at a line end is it a hyphen.
     */
    private fun endsLine(chars: List<LayoutChar>, index: Int): Boolean {
        val char = chars[index]
        // A few characters at most (line-break spaces): a page of unmapped glyphs stays linear.
        for (next in index + 1 until minOf(chars.size, index + 1 + HYPHEN_LOOKAHEAD)) {
            val candidate = chars[next]
            if (candidate.hasBox && !candidate.char.isWhitespace()) {
                return candidate.top > char.bottom || candidate.bottom < char.top
            }
        }
        return true
    }

    private fun startsNewWord(previous: LayoutChar, next: LayoutChar): Boolean {
        // pdfium gives every letter of a ligature ("ffi") the ligature glyph's box, which would
        // otherwise read as a jump backwards.
        if (sameGlyph(previous, next)) return false
        val em = max(charEm(previous), charEm(next))
        // Overlap rather than centres: a comma or a "p" sits lower than an "h" on the same line.
        if (next.top > previous.bottom || next.bottom < previous.top) return true
        // Right-to-left letters advance leftwards: the gap runs from this letter's left edge.
        val rtl = TextDirection.isRtlChar(previous.char) || TextDirection.isRtlChar(next.char)
        val gap = if (rtl) previous.left - next.right else next.left - previous.right
        return gap > em * WORD_GAP_EM || gap < -em * BACKWARD_GAP_EM
    }

    private fun sameGlyph(a: LayoutChar, b: LayoutChar): Boolean =
        abs(a.left - b.left) < SAME_GLYPH_PT && abs(a.right - b.right) < SAME_GLYPH_PT &&
            abs(a.top - b.top) < SAME_GLYPH_PT && abs(a.bottom - b.bottom) < SAME_GLYPH_PT

    /**
     * pdfium reports the size set by the font operator, which is 1 in documents that scale text
     * with the text matrix instead. A size that small, or one the glyphs plainly outgrow, is
     * replaced by an estimate from the tallest glyph (capitals are about 0.7 em).
     */
    private fun wordSize(sizes: List<Float>, maxHeight: Float): Float {
        val reported = if (sizes.isEmpty()) 0f else sizes.sorted()[sizes.size / 2]
        val fromHeight = maxHeight / CAP_HEIGHT_EM
        return if (reported < MIN_TRUSTED_SIZE_PT || maxHeight > reported * OUTGROWN_RATIO) {
            fromHeight.coerceAtLeast(MIN_TRUSTED_SIZE_PT)
        } else {
            reported
        }
    }

    private fun charEm(char: LayoutChar): Float =
        if (char.fontSize >= MIN_TRUSTED_SIZE_PT) char.fontSize else (char.bottom - char.top) / CAP_HEIGHT_EM

    fun isPrintable(char: Char): Boolean =
        !char.isISOControl() && char != '\uFFFE' && char != '\uFFFF' && char != '\uFFFD'

    private const val WORD_GAP_EM = 0.25f
    private const val BACKWARD_GAP_EM = 0.5f
    private const val CAP_HEIGHT_EM = 0.72f
    private const val MIN_TRUSTED_SIZE_PT = 2f
    private const val OUTGROWN_RATIO = 1.25f
    private const val SAME_GLYPH_PT = 0.01f

    private val SOFT_HYPHENS = setOf('\u00AD', '\u0002')
    private const val UNMAPPED = '\uFFFE'
    private const val HYPHEN_LOOKAHEAD = 4
}
