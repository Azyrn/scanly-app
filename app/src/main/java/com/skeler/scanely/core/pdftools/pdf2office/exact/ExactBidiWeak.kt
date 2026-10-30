package com.skeler.scanely.core.pdftools.pdf2office.exact

/** UAX #9's weak-type rules W1–W7 for [ExactBidiForward]: what numbers, separators and terminators become. */
internal object ExactBidiWeak {

    const val T_L = 0
    const val T_R = 1
    const val T_AL = 2
    const val T_EN = 3
    const val T_AN = 4
    const val T_ES = 5
    const val T_ET = 6
    const val T_CS = 7
    const val T_ON = 8

    private val TYPES: Map<Byte, Int> = mapOf(
        Character.DIRECTIONALITY_LEFT_TO_RIGHT to T_L,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT to T_R,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC to T_AL,
        Character.DIRECTIONALITY_EUROPEAN_NUMBER to T_EN,
        Character.DIRECTIONALITY_ARABIC_NUMBER to T_AN,
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR to T_ES,
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR to T_ET,
        Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR to T_CS
    )

    /** The bidi type of [code] this simplified algorithm tells apart; everything else is other neutral. */
    fun typeOf(code: Int): Int = TYPES[Character.getDirectionality(code)] ?: T_ON

    /** W1–W7 over [type], a paragraph starting at [sos]. */
    fun resolve(type: IntArray, sos: Int) {
        arabicContext(type, sos)
        numberSeparators(type)
        numberTerminators(type)
        // W6: what is left of separators and terminators is neutral.
        for (k in type.indices) if (type[k] == T_ES || type[k] == T_ET || type[k] == T_CS) type[k] = T_ON
        leftToRightNumbers(type, sos)
    }

    /** W2, W3: a European number after an Arabic letter is an Arabic number; Arabic letters are R. */
    private fun arabicContext(type: IntArray, sos: Int) {
        var last = sos
        for (i in type.indices) {
            when (type[i]) {
                T_L, T_R, T_AL -> last = type[i]
                T_EN -> if (last == T_AL) type[i] = T_AN
            }
        }
        for (i in type.indices) if (type[i] == T_AL) type[i] = T_R
    }

    /** W4: a single separator between two numbers of one kind joins them. */
    private fun numberSeparators(type: IntArray) {
        for (i in 1 until type.size - 1) {
            if (type[i] == T_ES && type[i - 1] == T_EN && type[i + 1] == T_EN) type[i] = T_EN
            val numberEachSide = type[i - 1] == type[i + 1] && (type[i - 1] == T_EN || type[i - 1] == T_AN)
            if (type[i] == T_CS && numberEachSide) type[i] = type[i - 1]
        }
    }

    /** W5: a run of terminators beside a European number is part of it. */
    private fun numberTerminators(type: IntArray) {
        var i = 0
        while (i < type.size) {
            if (type[i] == T_ET) {
                var j = i
                while (j < type.size && type[j] == T_ET) j++
                if (besideNumber(type, i, j)) for (k in i until j) type[k] = T_EN
                i = j
            } else {
                i++
            }
        }
    }

    /** A European number right before [start] or at [end]. */
    private fun besideNumber(type: IntArray, start: Int, end: Int): Boolean =
        (start > 0 && type[start - 1] == T_EN) || (end < type.size && type[end] == T_EN)

    /** W7: a European number after a left-to-right letter (or at a left-to-right start) is L. */
    private fun leftToRightNumbers(type: IntArray, sos: Int) {
        var last = sos
        for (k in type.indices) {
            when (type[k]) {
                T_L, T_R -> last = type[k]
                T_EN -> if (last == T_L) type[k] = T_L
            }
        }
    }
}
