package com.skeler.scanely.core.pdftools.pdf2office.exact

/**
 * ASCII bracket pairs in drawn (visual) text for [ExactBidi]: finding them, and N0 — a bracket pair
 * takes the direction of what it encloses when that is the paragraph's own; when it encloses only
 * the other direction, that direction if what comes before it in reading order is also that
 * direction, else the paragraph's.
 */
internal object ExactBidiBrackets {

    const val OPENERS = "([{"
    const val CLOSERS = ")]}"

    /** Index of the bracket kind (parenthesis, square, curly) of [code], or -1. */
    fun bracketKind(code: Int): Int {
        if (code > Char.MAX_VALUE.code) return -1
        val open = OPENERS.indexOf(code.toChar())
        return if (open >= 0) open else CLOSERS.indexOf(code.toChar())
    }

    /**
     * Bracket pairs as drawn: neighbours of the same kind, matched by nesting in visual order
     * whichever way each glyph faces. pdfium's codes for brackets in right-to-left text are
     * inconsistent (it mirrors some and not others), so a pair is recognised by position and kind.
     */
    fun pairs(codes: IntArray): List<IntArray> {
        val pairs = ArrayList<IntArray>()
        for (kind in OPENERS.indices) {
            val at = codes.indices.filter { bracketKind(codes[it]) == kind }
            val literal = if (at.isEmpty()) null else nested(codes, at)
            if (literal != null) {
                pairs += literal
            } else if (at.isNotEmpty() && at.size % 2 == 0) {
                // The codes face the wrong way (pdfium reported the logical character for glyphs
                // drawn mirrored): neighbours of one kind pair up in order.
                for (k in at.indices step 2) pairs += intArrayOf(at[k], at[k + 1])
            }
        }
        return pairs
    }

    /** [at] paired as drawn, every opener left of its closer, nesting allowed; null when they don't pair up that way. */
    private fun nested(codes: IntArray, at: List<Int>): List<IntArray>? {
        val stack = ArrayList<Int>()
        val literal = ArrayList<IntArray>()
        for (i in at) {
            if (!isCloser(codes[i])) {
                stack += i
            } else if (stack.isNotEmpty()) {
                literal += intArrayOf(stack.removeAt(stack.size - 1), i)
            } else {
                return null
            }
        }
        return literal.takeIf { stack.isEmpty() && it.size * 2 == at.size }
    }

    private fun isCloser(code: Int) = code <= Char.MAX_VALUE.code && CLOSERS.indexOf(code.toChar()) >= 0

    /** N0 over [resolved] (directions per position, -1 for neutral), innermost pairs first. */
    fun resolve(codes: IntArray, resolved: IntArray, paragraph: Int) {
        for ((open, close) in pairs(codes).sortedBy { it[1] - it[0] }) {
            val inside = (open + 1 until close).map { resolved[it] }
            val direction = when {
                inside.any { it == paragraph } -> paragraph
                inside.any { it >= 0 } -> otherIfContext(resolved, open, close, paragraph)
                else -> -1
            }
            if (direction >= 0) {
                resolved[open] = direction
                resolved[close] = direction
            }
        }
    }

    /**
     * The other direction when what precedes the pair in reading order (left of it for left-to-right
     * paragraphs, right of it for right-to-left ones) is that direction too, else the paragraph's.
     */
    private fun otherIfContext(resolved: IntArray, open: Int, close: Int, paragraph: Int): Int {
        val context = if (paragraph == ExactBidi.LTR) {
            nearest(
                resolved,
                open - 1,
                -1
            )
        } else {
            nearest(resolved, close + 1, 1)
        }
        val other = 1 - paragraph
        return if ((context ?: paragraph) == other) other else paragraph
    }

    private fun nearest(resolved: IntArray, from: Int, step: Int): Int? {
        var i = from
        while (i in resolved.indices) {
            if (resolved[i] >= 0) return resolved[i]
            i += step
        }
        return null
    }
}
