package com.skeler.scanely.core.pdftools.office2pdf.pptx

/* Bullet glyphs and auto-numbering labels such as "1.", "a)" or "iv". */

/** Symbol fonts put pictures at letter code points; draw the picture's nearest Unicode instead. */
private val SYMBOL_BULLETS = mapOf(
    "§" to "▪", "Ø" to "➢", "ü" to "✓", "q" to "❑", "v" to "❖", "l" to "●", "n" to "■",
    "·" to "•", "o" to "○", "ð" to "⇨", "Ú" to "➔", "à" to "→"
)

internal fun bulletGlyph(char: String, font: String?): String {
    val f = font?.lowercase().orEmpty()
    if ("wingdings" in f || "symbol" in f || "webdings" in f) return SYMBOL_BULLETS[char] ?: "•"
    return char.ifEmpty { "•" }
}

private const val ROMAN_LIMIT = 3999
private const val ALPHABET = 26

internal fun autoNumber(scheme: String, n: Int): String = punctuate(scheme, numberCore(scheme, n))

/** The scheme's prefix names the counting system: alphaLc, romanUc, arabic… */
private fun numberCore(scheme: String, n: Int): String = when {
    scheme.startsWith("alphaLc") -> alpha(n).lowercase()
    scheme.startsWith("alphaUc") -> alpha(n)
    scheme.startsWith("romanLc") -> roman(n).lowercase()
    scheme.startsWith("romanUc") -> roman(n)
    else -> n.toString()
}

/** The scheme's suffix names the punctuation around the number. */
private fun punctuate(scheme: String, core: String): String {
    return when {
        scheme.endsWith("ParenBoth") -> "($core)"
        scheme.endsWith("ParenR") -> "$core)"
        scheme.endsWith("Period") -> "$core."
        scheme.endsWith("Minus") -> "- $core -"
        else -> core
    }
}

private fun alpha(n: Int): String {
    if (n <= 0) return n.toString()
    val sb = StringBuilder()
    var v = n
    while (v > 0) {
        val rem = (v - 1) % ALPHABET
        sb.append('A' + rem)
        v = (v - 1) / ALPHABET
    }
    return sb.reverse().toString()
}

private val ROMAN = listOf(
    1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
    50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
)

private fun roman(n: Int): String {
    if (n !in 1..ROMAN_LIMIT) return n.toString()
    var v = n
    val sb = StringBuilder()
    for ((value, symbol) in ROMAN) {
        while (v >= value) {
            sb.append(symbol)
            v -= value
        }
    }
    return sb.toString()
}
