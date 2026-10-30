package com.skeler.scanely.core.pdftools.pdf2office.exact

/**
 * The Unicode bidirectional algorithm run backwards: a PDF gives characters in the order they sit
 * on the page, a Word paragraph wants them in the order they are read, and Word lays the reading
 * order out with the same algorithm the PDF's producer used. Given the characters left to right as
 * drawn, this finds the embedding level each one must have had and the reading order that renders
 * back to the same picture.
 *
 * The rules are the ones that decide where things land in real text (UAX #9 W/N/I rules, simplified
 * to what can be known from the page): letters of right-to-left scripts are level 1; left-to-right
 * letters and numbers sit one level up from the paragraph; a neutral between two characters of the
 * same direction takes that direction, otherwise the paragraph's; a bracket pair follows what is
 * inside it (N0). Pure and JVM-testable.
 */
internal object ExactBidi {

    const val RTL = 0
    const val LTR = 1

    /** How a number acts on the neutrals around it: as the paragraph's direction, or fixed. */
    const val PARAGRAPH = -2

    /** Characters a mirroring font draws as their counterpart in right-to-left text. */
    private const val MIRROR_A = "([{<«‹⟨≤"
    private const val MIRROR_B = ")]}>»›⟩≥"

    fun isMarkCode(code: Int): Boolean = when (Character.getType(code)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** The glyph-for-glyph counterpart of a bracket-like character, or the character itself. */
    fun mirror(code: Int): Int {
        if (code > Char.MAX_VALUE.code) return code
        val a = MIRROR_A.indexOf(code.toChar())
        if (a >= 0) return MIRROR_B[a].code
        val b = MIRROR_B.indexOf(code.toChar())
        return if (b >= 0) MIRROR_A[b].code else code
    }

    /** Reading order: element k is the visual index of the k-th character read. */
    fun readingOrder(levels: IntArray): IntArray {
        val order = IntArray(levels.size) { it }
        // Undoing the display reordering: reverse runs from the lowest level up (UAX #9 L2 in reverse).
        for (level in 1..(levels.maxOrNull() ?: 0)) reverseRuns(order, levels, level)
        return order
    }

    /** Reverses every run of [order] whose levels are at least [level]. */
    private fun reverseRuns(order: IntArray, levels: IntArray, level: Int) {
        var i = 0
        while (i < order.size) {
            if (levels[order[i]] >= level) {
                var j = i
                while (j + 1 < order.size && levels[order[j + 1]] >= level) j++
                order.reverse(i, j + 1)
                i = j + 1
            } else {
                i++
            }
        }
    }

    /**
     * [codes] with every bracket pair as the eye sees it: the opener's shape on the left, the
     * closer's on the right, whichever way pdfium reported them (in right-to-left text it hands over
     * the logical character for some and the mirrored one for others). The left-to-right pass of
     * [ExactBidiLevels.levels] then sees them as drawn.
     */
    fun canonicalBrackets(codes: IntArray): IntArray {
        val out = codes.copyOf()
        for ((left, right) in ExactBidiBrackets.pairs(codes)) {
            val kind = ExactBidiBrackets.bracketKind(codes[left])
            out[left] = ExactBidiBrackets.OPENERS[kind].code
            out[right] = ExactBidiBrackets.CLOSERS[kind].code
        }
        return out
    }

    /** UAX #9 W1–W7, N0–N2 and I1–I2 for one paragraph of reading-order [codes] ([ExactBidiForward]). */
    fun forwardLevels(codes: IntArray, rtl: Boolean): IntArray = ExactBidiForward.levels(codes, rtl)

    /** What a producer draws for [codes] read in order: the codes left to right, mirrored ones swapped at odd levels. */
    fun display(codes: IntArray, levels: IntArray, mirrored: Boolean): IntArray {
        val order = IntArray(codes.size) { it }
        val top = levels.maxOrNull() ?: 0
        val lowest = levels.filter { it % 2 == 1 }.minOrNull() ?: (top + 1)
        for (level in top downTo lowest) reverseRuns(order, levels, level)
        return IntArray(codes.size) { k ->
            val c = codes[order[k]]
            if (mirrored && levels[order[k]] % 2 == 1) mirror(c) else c
        }
    }

    /** Reading order of [visual] (glyphs left to right): codes in reading order and each one's visual index. */
    class Reading(val codes: IntArray, val order: IntArray, val levels: IntArray, val verified: Boolean = true)

    /**
     * Finds a reading order that the forward algorithm lays out exactly as it was drawn, trying the
     * ambiguous choices (how numbers influence neutrals, whether the producer drew mirrored glyphs)
     * in turn. When none reproduces the drawing, the first guess is used.
     */
    fun reading(drawn: IntArray, rtl: Boolean): Reading {
        val visual = canonicalBrackets(drawn)
        var first: Reading? = null
        // (how numbers act on neutrals, whether numbers sit two levels up as after an Arabic letter)
        val choices = listOf(PARAGRAPH to false, LTR to false, RTL to false, RTL to true, LTR to true)
        for ((numbers, high) in choices) {
            for (mirrored in booleanArrayOf(true, false)) {
                val guess = ExactBidiLevels.levels(visual, rtl, numbers, high)
                val order = readingOrder(guess)
                val codes = IntArray(order.size) { k ->
                    val c = visual[order[k]]
                    if (mirrored && guess[order[k]] % 2 == 1) mirror(c) else c
                }
                val forward = forwardLevels(codes, rtl)
                if (sameDrawing(display(codes, forward, mirrored), drawn)) return Reading(codes, order, forward)
                if (first == null) first = Reading(codes, order, forward, verified = false)
            }
        }
        return first!!
    }

    /** Equal drawings, not telling which way a bracket faces (see [ExactBidiBrackets.pairs]). */
    private fun sameDrawing(a: IntArray, b: IntArray): Boolean = a.size == b.size && a.indices.all {
        a[it] == b[it] || (
            ExactBidiBrackets.bracketKind(
                a[it]
            ) >= 0 && ExactBidiBrackets.bracketKind(a[it]) == ExactBidiBrackets.bracketKind(b[it])
            )
    }
}
