package com.skeler.scanely.core.pdftools.pdf2office.exact

private const val LONG_TOKEN = 20
private const val LINK_CHARS = "/%=&_?#"
private const val NO_WORD_START = "=/%&_?#)]}.,;:"

/** How one PDF line continues into the next inside a Word text box. */
internal enum class Join { BREAK, SPACE, SOFT_HYPHEN, PARAGRAPH }

/**
 * Whether [block] is written as wrapping paragraphs rather than one hard-broken line per PDF line:
 * level prose of several lines, upright, every line in the block's direction, and no long link
 * broken where it ran out of room (the PDF put no space there and Word would break it at its own
 * place, a slash or hyphen, so such a block keeps its line breaks).
 */
internal fun docxReflows(block: Block, behind: Boolean): Boolean =
    !behind && block.lines.size > 1 && block.angleDeg == 0 && block.lines.none { mixed(block, it) } &&
        block.lines.zipWithNext().none { (line, next) -> brokenLink(line, next) }

private fun mixed(block: Block, line: Segment) =
    (line.bidi && !block.rtl) || line.rtl != block.rtl || line.tokens.isEmpty()

private fun brokenLink(line: Segment, next: Segment): Boolean {
    val first = next.tokens.first().text
    return unbreakable(line.tokens.last().text) || unbreakable(first) || first.first() in NO_WORD_START
}

private fun unbreakable(text: String) =
    "://" in text || "www." in text || (text.length > LONG_TOKEN && text.any { it in LINK_CHARS })

/** "exam-" then "ple" on a left-to-right line: a word hyphenated across the break. */
private fun hyphenBreak(line: Segment, next: Segment): Boolean {
    val lastText = line.tokens.last().text
    return !line.rtl && lastText.length > 1 && lastText.endsWith("-") &&
        lastText[lastText.length - 2].isLetter() && next.tokens.first().text.firstOrNull()?.isLowerCase() == true
}

/**
 * How [line] continues into [next] in a reflowing block whose box spans leftPt..rightPt. Where the
 * next line's first word would have fitted after this one, the PDF ended the line early, so a new
 * paragraph starts (or, for a hyphenated word, the line break stays) and Word cannot pull it up.
 */
internal class DocxLineJoin(private val measurer: TextMeasurer) {

    fun join(line: Segment, next: Segment, leftPt: Float, rightPt: Float): Join {
        val hyphen = hyphenBreak(line, next)
        val fits = spaceWidth(next, hyphen) + firstWordWidth(next) <= room(line, hyphen, leftPt, rightPt)
        return when {
            fits -> if (hyphen) Join.BREAK else Join.PARAGRAPH
            hyphen -> Join.SOFT_HYPHEN
            else -> Join.SPACE
        }
    }

    private fun spaceWidth(next: Segment, hyphen: Boolean): Float {
        if (hyphen) return 0f
        val style = next.tokens.first().style
        return measurer.width(" ", style.family, style.arabic, style.bold, style.italic, style.sizePt)
    }

    private fun firstWordWidth(next: Segment): Float {
        val word = listOf(next.tokens.first()) + next.tokens.drop(1).takeWhile { !it.spaceBefore }
        return word.maxOf { it.u1 } - word.minOf { it.u0 }
    }

    private fun room(line: Segment, hyphen: Boolean, leftPt: Float, rightPt: Float): Float = when {
        line.rtl -> line.u0 - leftPt
        hyphen -> rightPt - (line.tokens.last().u1 - hyphenWidth(line.tokens.last().style))
        else -> rightPt - line.u1
    }

    private fun hyphenWidth(s: TextStyle) = measurer.width("-", s.family, s.arabic, s.bold, s.italic, s.sizePt)
}
