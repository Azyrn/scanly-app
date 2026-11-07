package com.skeler.scanely.core.ocr.paddle

/**
 * CTC recognition scans crop pixels left-to-right, so RTL lines come out in visual
 * order — the logical string reversed, with embedded Latin/digit runs still LTR
 * (verified on device in PaddleArabicRtlTest). Converts back to logical order.
 */
object RtlText {

    // A blank run wider than this many times the line's own letter pitch is a word gap, not the
    // space between two joined letters. Arabic is cursive: letters within a word sit a pitch apart,
    // words a multiple of it — the inverse of Latin, where the intra-word gaps are the wide ones,
    // which is why this runs only between two Arabic-script neighbours. 2.3 split words inside typed
    // Arabic pages ("عر ض", "مف صل"): 3.0 measured +0.02-0.03 word precision on the Arabic scans, recall
    // unchanged, and the camera paragraph sample unchanged (host sweep, scripts/fidelity/ocr_compare.py).
    private const val WORD_GAP_FACTOR = 3.0f

    private const val ARABIC_BLOCK_START = 0x0600
    private const val ARABIC_BLOCK_END = 0x06FF

    private val MIRROR = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[',
        '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«'
    )

    private fun isRtl(c: Char): Boolean {
        val d = Character.getDirectionality(c)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /**
     * Whether a line reads right-to-left. Digits are deliberately not counted: they are
     * direction-neutral, so a numeric table cell must never drag a line into RTL order.
     */
    fun isRtlDominant(s: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (c in s) {
            if (isRtl(c)) {
                rtl++
            } else if (Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                ltr++
            }
        }
        return rtl > ltr
    }

    /**
     * Share of a string's directional characters that are right-to-left. Digits and neutrals are
     * not counted (as in [isRtlDominant]), so a Latin-with-numbers header does not read as an RTL
     * page and a bilingual ID card stays well below a full Arabic page.
     */
    fun rtlFraction(s: String): Float {
        var rtl = 0
        var strong = 0
        for (c in s) {
            when {
                isRtl(c) -> {
                    rtl++
                    strong++
                }
                Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT -> strong++
            }
        }
        return if (strong == 0) 0f else rtl.toFloat() / strong
    }

    private fun isArabicChar(c: Char): Boolean = c.code in ARABIC_BLOCK_START..ARABIC_BLOCK_END

    private fun isWordGap(prev: RecChar, next: RecChar, limit: Float): Boolean =
        next.x - prev.x > limit &&
            prev.text.isNotBlank() && next.text.isNotBlank() &&
            prev.text.all { isArabicChar(it) } && next.text.all { isArabicChar(it) }

    /**
     * Rebuilds a line's visual-order text from its CTC characters, restoring the word spaces the
     * recognizer dropped. The recognizer emits a space glyph at some word boundaries and not others;
     * where it skipped one it still left the blank timesteps behind, so a gap wider than the line's
     * typical letter pitch is a missing space. Only ever inserted between two Arabic-script letters,
     * never beside an existing space or a Latin run, and a no-op on a line of two characters or
     * fewer. Returns [fallback] when there are no positions to reason about.
     */
    fun recoverWordSpaces(chars: List<RecChar>, fallback: String, factor: Float = WORD_GAP_FACTOR): String {
        if (chars.size < 3) return fallback
        val ordered = chars.sortedBy { it.x }
        val gaps = ordered.zipWithNext { a, b -> b.x - a.x }.filter { it > 0f }.sorted()
        if (gaps.isEmpty()) return fallback
        val pitch = gaps[gaps.size / 2]
        val limit = pitch * factor

        val sb = StringBuilder()
        for (i in ordered.indices) {
            val c = ordered[i]
            if (i > 0 && isWordGap(ordered[i - 1], c, limit)) sb.append(' ')
            sb.append(c.text)
        }
        return sb.toString().replace(Regex(" {2,}"), " ")
    }

    fun visualToLogical(s: String): String {
        if (s.none { isRtl(it) }) return s
        val out = CharArray(s.length) { i ->
            val c = s[s.length - 1 - i]
            MIRROR[c] ?: c
        }
        // Whole-line reversal also reversed the LTR runs; restore them, letting a
        // single joiner between two LTR chars extend the run (3.14, A-4471).
        var i = 0
        while (i < out.size) {
            if (isLtrStrong(out[i])) {
                val end = ltrRunEnd(out, i)
                out.reverse(i, end + 1)
                i = end + 1
            } else {
                i++
            }
        }
        return String(out)
    }
}

// An alef with hamza below with no Arabic letter or mark after it. The letter only ever starts a
// word, so this is the recognizer reading "!" as the glyph it resembles: it did so for every
// exclamation mark on the Arabic test pages, and never wrote a word-final one for real.
private val STRAY_HAMZA_ALEF = Regex("إ(?![\\u0621-\\u065F\\u0670-\\u06D3])")

/** Turns a misread "!" (read as a word-final "إ", which Arabic never writes) back into "!". */
internal fun restoreExclamation(s: String): String =
    if ('إ' in s) s.replace(STRAY_HAMZA_ALEF, "!") else s

private fun isLtrStrong(c: Char): Boolean =
    Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT || c.isDigit()

// A space joins too, so a multi-word Latin phrase inside an RTL line stays one run: without
// it "Version 2.5" is reversed as two runs and comes back out as "2.5 Version". A joiner only
// extends a run when another LTR character follows, so Arabic after a space is unaffected.
//
// Taken from the Unicode bidi class rather than an ASCII set: an Arabic page writes its numbers
// with the Arabic comma and decimal separator, and treating those as line text split
// "١١٩،٩٢" into two runs that came back out swapped, as "٩٢،١١٩".
private fun isRunJoiner(c: Char): Boolean = when (Character.getDirectionality(c)) {
    Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
    Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
    Character.DIRECTIONALITY_ARABIC_NUMBER -> true
    else -> c == ' '
}

/** The last index of the LTR run starting at [start]; a lone joiner between LTR chars extends it. */
private fun ltrRunEnd(chars: CharArray, start: Int): Int {
    var end = start
    var j = start + 1
    while (j < chars.size) {
        when {
            isLtrStrong(chars[j]) -> {
                end = j
                j++
            }
            isRunJoiner(chars[j]) && j + 1 < chars.size && isLtrStrong(chars[j + 1]) -> j++
            else -> return end
        }
    }
    return end
}
