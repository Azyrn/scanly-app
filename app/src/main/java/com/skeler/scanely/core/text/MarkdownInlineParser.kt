package com.skeler.scanely.core.text

private val LINK = Regex("\\[([^]]*)]\\(([^)]*)\\)")

/** Everything [Markdown.escape] puts a backslash in front of. */
private const val ESCAPABLE = "\\`*_[]<|#>-+"

/** One pass over a line's inline Markdown, toggling bold/italic/strike/underline as it goes. */
internal class MarkdownInlineParser(private val source: String) {
    private val spans = mutableListOf<MdSpan>()
    private val buffer = StringBuilder()
    private var bold = false
    private var italic = false
    private var strike = false
    private var underline = false
    private var i = 0

    fun parse(): List<MdSpan> {
        while (i < source.length) step()
        flush()
        return spans
    }

    private fun flush() {
        if (buffer.isNotEmpty()) {
            spans += MdSpan(buffer.toString(), bold, italic, strike, underline)
            buffer.clear()
        }
    }

    // A marker only opens when a closer exists ahead; a stray marker stays literal text.
    private fun closes(marker: String, from: Int): Boolean =
        source.indexOf(marker, from, ignoreCase = true) != -1

    private fun step() {
        when {
            isEscape() -> {
                buffer.append(source[i + 1])
                i += 2
            }
            source.startsWith("***", i) -> marker("***", boldItalicActive()) {
                bold = !bold
                italic = !italic
            }
            source.startsWith("**", i) -> marker("**", opens("**", bold)) { bold = !bold }
            source.startsWith("~~", i) -> marker("~~", opens("~~", strike)) { strike = !strike }
            source.startsWith("<u>", i, ignoreCase = true) ->
                marker("<u>", closes("</u>", i + "<u>".length)) { underline = true }
            source.startsWith("</u>", i, ignoreCase = true) -> marker("</u>", underline) { underline = false }
            else -> singleChar(source[i])
        }
    }

    private fun isEscape(): Boolean = source[i] == '\\' && i + 1 < source.length && source[i + 1] in ESCAPABLE

    /** `***` toggles while either style is on, or opens when both closers lie ahead. */
    private fun boldItalicActive(): Boolean {
        val after = i + "***".length
        return bold || italic || (closes("**", after) && closes("*", after))
    }

    /** A [token] toggles while its style is [on], or opens when its closer lies ahead. */
    private fun opens(token: String, on: Boolean): Boolean = on || closes(token, i + token.length)

    /** At a [token]: toggles when [active] allows it, else keeps the token as literal text. */
    private inline fun marker(token: String, active: Boolean, toggle: () -> Unit) {
        if (active) {
            flush()
            toggle()
        } else {
            buffer.append(source, i, i + token.length)
        }
        i += token.length
    }

    private fun singleChar(c: Char) {
        when {
            c == '*' && isEmphasis(source, i) -> marker("*", opens("*", italic)) { italic = !italic }
            c == '`' -> codeSpan()
            c == '[' -> link()
            else -> {
                buffer.append(c)
                i++
            }
        }
    }

    private fun codeSpan() {
        val end = source.indexOf('`', i + 1)
        if (end == -1) {
            buffer.append('`')
            i++
        } else {
            flush()
            spans += MdSpan(source.substring(i + 1, end), code = true)
            i = end + 1
        }
    }

    private fun link() {
        val match = LINK.matchAt(source, i)
        if (match == null) {
            buffer.append('[')
            i++
        } else {
            val (label, url) = match.destructured
            flush()
            spans += MdSpan(label, bold, italic, strike, underline, link = url)
            i += match.value.length
        }
    }
}

/**
 * Whether the `*` at [index] is an emphasis marker rather than a literal asterisk.
 *
 * A lone `*` is emphasis only next to a word, so `5 * 3` stays arithmetic. The end-of-line case is
 * the one worth stating: a marker there can only ever *close*, and treating it as literal left
 * `*(5 min)*` at the end of a heading showing a stray asterisk with the italic never turned back
 * off. Opening at the end is still impossible, and the caller's own "does a closer exist ahead"
 * check is what keeps a trailing `hello*` literal.
 */
private fun isEmphasis(source: String, index: Int): Boolean {
    val after = source.getOrNull(index + 1)
    if (after == '*') return false
    val before = source.getOrNull(index - 1)
    if (after == null || after.isWhitespace()) {
        return before != null && !before.isWhitespace()
    }
    return true
}
