package com.skeler.scanely.core.pdftools.pdf2office.exact

/**
 * The forward bidirectional algorithm (UAX #9 W1–W7, N0–N2, I1–I2) for one paragraph in reading
 * order, which [ExactBidi.reading] uses to check a guessed reading order against what was drawn.
 */
internal object ExactBidiForward {

    private const val T_L = ExactBidiWeak.T_L
    private const val T_R = ExactBidiWeak.T_R
    private const val T_EN = ExactBidiWeak.T_EN
    private const val T_AN = ExactBidiWeak.T_AN
    private const val T_ON = ExactBidiWeak.T_ON

    fun levels(codes: IntArray, rtl: Boolean): IntArray {
        val type = IntArray(codes.size) { ExactBidiWeak.typeOf(codes[it]) }
        // Start of sequence and embedding direction are the paragraph's: one and the same here.
        val sos = if (rtl) T_R else T_L
        ExactBidiWeak.resolve(type, sos)
        resolvePairs(codes, type, sos)
        resolveNeutrals(type, sos)
        val base = if (rtl) 1 else 0
        return IntArray(type.size) { k -> implicitLevel(type[k], base) }
    }

    private fun strongOf(t: Int) = when (t) {
        T_L -> T_L
        T_R, T_EN, T_AN -> T_R
        else -> -1
    }

    /** N0: bracket pairs, in order of their opener. */
    private fun resolvePairs(codes: IntArray, type: IntArray, embedding: Int) {
        for ((open, close) in bracketPairs(codes, type).sortedBy { it[0] }) {
            val direction = pairDirection(type, open, close, embedding)
            if (direction >= 0) {
                type[open] = direction
                type[close] = direction
            }
        }
    }

    /** BD16: neutral brackets matched in reading order, an opener with the nearest open one of its kind. */
    private fun bracketPairs(codes: IntArray, type: IntArray): List<IntArray> {
        val stack = ArrayList<Int>()
        val pairs = ArrayList<IntArray>()
        for (k in codes.indices) {
            val c = codes[k]
            if (type[k] != T_ON || c >= Char.MAX_VALUE.code) continue
            val closer = ExactBidiBrackets.CLOSERS.indexOf(c.toChar())
            if (ExactBidiBrackets.OPENERS.indexOf(c.toChar()) >= 0) {
                stack += k
            } else if (closer >= 0) {
                val at = stack.indexOfLast { ExactBidiBrackets.OPENERS.indexOf(codes[it].toChar()) == closer }
                if (at >= 0) {
                    pairs += intArrayOf(stack[at], k)
                    while (stack.size > at) stack.removeAt(stack.size - 1)
                }
            }
        }
        return pairs
    }

    /** The direction N0 gives a pair: the embedding's if it encloses that, else the context's other one; -1 for none. */
    private fun pairDirection(type: IntArray, open: Int, close: Int, embedding: Int): Int {
        val inside = (open + 1 until close).map { strongOf(type[it]) }
        return when {
            inside.any { it == embedding } -> embedding
            inside.any { it >= 0 } -> {
                val context = (open - 1 downTo 0).map { strongOf(type[it]) }.firstOrNull { it >= 0 } ?: embedding
                if (context == embedding) {
                    embedding
                } else if (embedding == T_L) {
                    T_R
                } else {
                    T_L
                }
            }
            else -> -1
        }
    }

    /** N1, N2: a run of neutrals between two of one direction takes it, otherwise the embedding's. */
    private fun resolveNeutrals(type: IntArray, sos: Int) {
        val n = type.size
        var i = 0
        while (i < n) {
            if (strongOf(type[i]) >= 0) {
                i++
            } else {
                var j = i
                while (j < n && strongOf(type[j]) < 0) j++
                val before = if (i > 0) strongOf(type[i - 1]) else strongOf(sos)
                val after = if (j < n) strongOf(type[j]) else strongOf(sos)
                val direction = if (before == after) before else sos
                for (k in i until j) type[k] = direction
                i = j
            }
        }
    }

    /** I1, I2. */
    private fun implicitLevel(t: Int, base: Int): Int = if (base % 2 == 0) {
        when (t) {
            T_R -> base + 1
            T_AN, T_EN -> base + 2
            else -> base
        }
    } else {
        when (t) {
            T_L, T_EN, T_AN -> base + 1
            else -> base
        }
    }
}
