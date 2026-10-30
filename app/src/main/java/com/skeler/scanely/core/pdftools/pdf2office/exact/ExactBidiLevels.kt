package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactBidi.LTR
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactBidi.RTL

/** The inverse pass of [ExactBidi]: a guess at the embedding levels drawn glyphs must have had. */
internal object ExactBidiLevels {

    private const val NUMBER = 2
    private const val NEUTRAL = 3
    private const val SEPARATOR = 4
    private const val TERMINATOR = 5

    /**
     * Embedding levels of [codes] (in visual order, left to right) for a paragraph that runs
     * right to left when [rtl]. Level parity is the direction: odd right to left.
     */
    fun levels(
        codes: IntArray,
        rtl: Boolean,
        numbers: Int = ExactBidi.PARAGRAPH,
        numbersHigh: Boolean = false
    ): IntArray {
        val kind = IntArray(codes.size) { kindOf(codes[it]) }
        mergeNumbers(kind)
        val paragraph = if (rtl) RTL else LTR
        // Direction a position acts as, for neutrals: numbers count as right to left inside right
        // to left paragraphs, as left to right in left to right ones.
        val numberDirection = if (numbers == ExactBidi.PARAGRAPH) paragraph else numbers
        val resolved = IntArray(codes.size) { directionOf(kind[it], numberDirection) }
        ExactBidiBrackets.resolve(codes, resolved, paragraph)
        fillNeutrals(resolved, paragraph)
        val up = if (rtl) 2 else 0
        return IntArray(codes.size) { k ->
            when {
                kind[k] == NUMBER -> if (numbersHigh) 2 else up
                resolved[k] == RTL -> 1
                else -> up
            }
        }
    }

    private fun directionOf(kind: Int, numberDirection: Int): Int = when (kind) {
        RTL, LTR -> kind
        NUMBER -> numberDirection
        else -> -1
    }

    /** A run of neutrals between two of one direction takes it, otherwise the paragraph's. */
    private fun fillNeutrals(resolved: IntArray, paragraph: Int) {
        val n = resolved.size
        var i = 0
        while (i < n) {
            if (resolved[i] >= 0) {
                i++
            } else {
                var j = i
                while (j < n && resolved[j] < 0) j++
                val before = if (i > 0) resolved[i - 1] else paragraph
                val after = if (j < n) resolved[j] else paragraph
                val direction = if (before == after) before else paragraph
                for (k in i until j) resolved[k] = direction
                i = j
            }
        }
    }

    private fun kindOf(code: Int): Int = when (Character.getDirectionality(code)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> RTL
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> LTR
        Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> NUMBER
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
        Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR -> SEPARATOR
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> TERMINATOR
        else -> NEUTRAL
    }

    /** "1,000", "10:30" and "50%": separators between numbers and terminators next to one belong to it. */
    private fun mergeNumbers(kind: IntArray) {
        val original = kind.copyOf()
        for (i in 1 until kind.size - 1) {
            if (original[i] == SEPARATOR && original[i - 1] == NUMBER && original[i + 1] == NUMBER) kind[i] = NUMBER
        }
        mergeTerminators(kind)
        for (i in kind.indices) if (kind[i] == SEPARATOR || kind[i] == TERMINATOR) kind[i] = NEUTRAL
    }

    /** Terminators after a number, then those before one, become part of it. */
    private fun mergeTerminators(kind: IntArray) {
        for (i in 1 until kind.size) if (kind[i] == TERMINATOR && kind[i - 1] == NUMBER) kind[i] = NUMBER
        for (i in kind.size - 2 downTo 0) if (kind[i] == TERMINATOR && kind[i + 1] == NUMBER) kind[i] = NUMBER
    }
}
