package com.skeler.scanely.core.pdftools.pdf2office

/** The text side of a paragraph: list markers, and lines joined back into running text. */
internal object ParagraphText {

    /** Each line's text; a bullet's marker, as its own word or glued on, is removed from the first line. */
    fun lineTexts(lines: List<LayoutLine>, kind: BlockKind): List<String> =
        lines.mapIndexed { index, line ->
            val words = if (index == 0 && kind == BlockKind.BULLET) line.words.drop(1) else line.words
            stripLeadingBullet(words.joinToString(" ") { it.text }, index == 0 && kind == BlockKind.BULLET)
        }

    /** One string for the paragraph: lines joined by spaces, a word hyphenated across lines rejoined. */
    fun reflow(texts: List<String>): String {
        val joined = StringBuilder()
        texts.forEach { text ->
            if (text.isEmpty()) return@forEach
            when {
                joined.isEmpty() -> Unit
                isHyphenBreak(joined, text) -> joined.setLength(joined.length - 1)
                else -> joined.append(' ')
            }
            joined.append(text)
        }
        return joined.toString()
    }

    private fun isHyphenBreak(joined: StringBuilder, text: String): Boolean =
        joined.length > 1 && joined.last() == '-' && joined[joined.length - 2].isLetter() &&
            text.first().isLowerCase()

    /** A marker glued to its text ("•Item") is cut off here; a separate marker word was dropped. */
    private fun stripLeadingBullet(text: String, firstOfBullet: Boolean): String =
        if (firstOfBullet && text.isNotEmpty() && isBulletGlyph(text.first())) text.drop(1).trimStart() else text

    fun isBulletMarker(word: String): Boolean =
        (word.length == 1 && (isBulletGlyph(word[0]) || word[0] in DASH_MARKERS)) ||
            (word.length > 1 && isBulletGlyph(word[0]) && word[0] !in DASH_MARKERS)

    /** Symbol and Wingdings bullets arrive as private-use code points. */
    private fun isBulletGlyph(char: Char): Boolean = char in BULLET_GLYPHS || char in PRIVATE_USE

    private val BULLET_GLYPHS = setOf('•', '◦', '▪', '▫', '‣', '●', '○', '■', '□', '·', '∙', '➢', '►', '✓', '✔')
    private val DASH_MARKERS = setOf('-', '–', '—', '*')
    private val PRIVATE_USE = '\uE000'..'\uF8FF'
    val NUMBERED_MARKER = Regex("^(\\d{1,3}|[a-zA-Z]|[ivxIVX]{1,4})[.)]$")
}
