package com.skeler.scanely.core.text

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Bullet(val text: String, val indent: Int, val checked: Boolean?) : MdBlock
    data class Numbered(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Equation(val latex: String) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock {
        /** What a text-only surface (plain view, print, Word) shows in the image's place. */
        val label: String get() = if (alt.isBlank()) "[Image]" else "[Image: $alt]"
    }
    data object Divider : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER = Regex("^\\s*([-*_])\\s*\\1\\s*\\1[\\s\\-*_]*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    private val CHECKBOX = Regex("^\\[([ xX])]\\s+(.*)$")
    private val TABLE_DIVIDER = Regex("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$")
    private val LINK = Regex("\\[([^]]*)]\\(([^)]*)\\)")
    private val IMAGE = Regex("^!\\[([^]]*)]\\(([^)]*)\\)$")

    /** Everything [Markdown.escape] puts a backslash in front of. */
    private const val ESCAPABLE = "\\`*_[]<|#>-+"

    /** Longer than this above a fenced answer is content, not the model clearing its throat. */
    private const val MAX_LEAD_IN = 200

    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lines()
        return lines.indices.any { index ->
            val trimmed = lines[index].trim()
            (trimmed.startsWith("|") && trimmed.endsWith("|")) ||
                HEADING.matches(trimmed) ||
                trimmed.startsWith("```") ||
                trimmed.startsWith("- ") ||
                trimmed == "$$" || isInlineEquation(trimmed) ||
                IMAGE.matches(trimmed) ||
                "**" in trimmed ||
                isTableStart(lines, index)
        }
    }

    /**
     * A translated document the model answered with inside a ``` fence, plus whatever lead-in it
     * wrote above it ("Here is the translation:"). Left alone when [source] was itself one fenced
     * block, since then the fence is the document and not a wrapper around it.
     */
    fun unwrapAddedFence(source: String, translated: String): String {
        val trimmed = translated.trim()
        if (source.trim().startsWith("```")) return trimmed

        val lines = trimmed.lines()
        val fences = lines.indices.filter { lines[it].trimStart().startsWith("```") }
        if (fences.size != 2 || fences.last() != lines.lastIndex) return trimmed

        val (open, close) = fences
        val lead = lines.take(open).joinToString("\n").trim()
        if (lead.length > MAX_LEAD_IN) return trimmed

        return lines.subList(open + 1, close).joinToString("\n").trim()
    }

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.toList())
                paragraph.clear()
            }
            if (quote.isNotEmpty()) {
                blocks += MdBlock.Quote(quote.toList())
                quote.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            when {
                line.startsWith("```") -> {
                    flush()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        body += lines[i]
                        i++
                    }
                    blocks += MdBlock.Code(body.joinToString("\n"))
                }

                line.isEmpty() -> flush()

                // Display math: `$$…$$` on one line, or a bare `$$` fence closed lines later.
                // `$$50 off` is prose, not an opener, so money never swallows the document.
                isInlineEquation(line) -> {
                    flush()
                    blocks += MdBlock.Equation(line.removeSurrounding("$$").trim())
                }

                line == "$$" -> {
                    flush()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                        body += lines[i].trim()
                        i++
                    }
                    if (i < lines.size) {
                        lines[i].trim().removeSuffix("$$").trim()
                            .takeIf { it.isNotEmpty() }?.let { body += it }
                    }
                    blocks += MdBlock.Equation(body.joinToString("\n").trim())
                }

                IMAGE.matches(line) -> {
                    flush()
                    val (alt, url) = IMAGE.find(line)!!.destructured
                    blocks += MdBlock.Image(alt.trim(), url.trim())
                }

                isTableStart(lines, i) -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    val bordered = lines[i].trim().startsWith("|")
                    while (i < lines.size && isTableRow(lines[i].trim(), bordered)) {
                        val cells = lines[i].trim()
                        if (!TABLE_DIVIDER.matches(cells)) rows += splitRow(cells)
                        i++
                    }
                    i--
                    if (rows.isNotEmpty()) {
                        val width = rows.maxOf { it.size }
                        val padded = rows.map { row -> row + List(width - row.size) { "" } }
                        blocks += MdBlock.Table(padded.first(), padded.drop(1))
                    }
                }

                DIVIDER.matches(line) -> {
                    flush()
                    blocks += MdBlock.Divider
                }

                HEADING.matches(line) -> {
                    flush()
                    val (hashes, text) = HEADING.find(line)!!.destructured
                    blocks += MdBlock.Heading(hashes.length, text.trim())
                }

                line.startsWith(">") -> {
                    if (paragraph.isNotEmpty()) flush()
                    quote += line.removePrefix(">").trim()
                }

                BULLET.matches(raw) -> {
                    flush()
                    val (indent, text) = BULLET.find(raw)!!.destructured
                    val box = CHECKBOX.find(text)
                    blocks += if (box != null) {
                        val (mark, rest) = box.destructured
                        MdBlock.Bullet(rest, indent.length, mark.isNotBlank())
                    } else {
                        MdBlock.Bullet(text, indent.length, null)
                    }
                }

                NUMBERED.matches(raw) -> {
                    flush()
                    val (indent, marker, text) = NUMBERED.find(raw)!!.destructured
                    blocks += MdBlock.Numbered(marker, text, indent.length)
                }

                else -> {
                    if (quote.isNotEmpty()) flush()
                    paragraph += raw.trimEnd()
                }
            }
            i++
        }
        flush()
        return blocks
    }

    /** Reading view: same content, Markdown syntax taken back out, tables aligned with spaces. */
    fun toPlainText(source: String): String = buildString {
        parse(source).forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    appendLine(plain(block.text))
                    appendLine()
                }
                is MdBlock.Paragraph -> {
                    block.lines.forEach { appendLine(plain(it)) }
                    appendLine()
                }
                is MdBlock.Bullet -> {
                    val box = when (block.checked) {
                        true -> "☑ "
                        false -> "☐ "
                        null -> ""
                    }
                    appendLine(" ".repeat(block.indent) + "• " + box + plain(block.text))
                }
                is MdBlock.Numbered ->
                    appendLine(" ".repeat(block.indent) + block.marker + " " + plain(block.text))
                is MdBlock.Quote -> {
                    block.lines.forEach { appendLine(plain(it)) }
                    appendLine()
                }
                is MdBlock.Code -> {
                    appendLine(block.text)
                    appendLine()
                }
                is MdBlock.Table -> {
                    appendLine(alignTable(block))
                }
                is MdBlock.Equation -> {
                    appendLine(MathText.prettify(block.latex))
                    appendLine()
                }
                is MdBlock.Image -> {
                    appendLine(block.label)
                    appendLine()
                }
                MdBlock.Divider -> appendLine()
            }
        }
    }.trim()

    fun parseInline(source: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        var underline = false
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += MdSpan(buffer.toString(), bold, italic, strike, underline)
                buffer.clear()
            }
        }

        // A marker only opens when a closer exists ahead; a stray marker stays literal text.
        fun closes(marker: String, from: Int): Boolean =
            source.indexOf(marker, from, ignoreCase = true) != -1

        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length && source[i + 1] in ESCAPABLE -> {
                    buffer.append(source[i + 1])
                    i += 2
                }
                source.startsWith("***", i) -> {
                    if (bold || italic || (closes("**", i + 3) && closes("*", i + 3))) {
                        flush()
                        bold = !bold
                        italic = !italic
                    } else {
                        buffer.append("***")
                    }
                    i += 3
                }
                source.startsWith("**", i) -> {
                    if (bold || closes("**", i + 2)) {
                        flush()
                        bold = !bold
                    } else {
                        buffer.append("**")
                    }
                    i += 2
                }
                source.startsWith("~~", i) -> {
                    if (strike || closes("~~", i + 2)) {
                        flush()
                        strike = !strike
                    } else {
                        buffer.append("~~")
                    }
                    i += 2
                }
                source.startsWith("<u>", i, ignoreCase = true) -> {
                    if (closes("</u>", i + 3)) {
                        flush()
                        underline = true
                    } else {
                        buffer.append(source, i, i + 3)
                    }
                    i += 3
                }
                source.startsWith("</u>", i, ignoreCase = true) -> {
                    if (underline) {
                        flush()
                        underline = false
                    } else {
                        buffer.append(source, i, i + 4)
                    }
                    i += 4
                }
                c == '*' && isEmphasis(source, i) -> {
                    if (italic || closes("*", i + 1)) {
                        flush()
                        italic = !italic
                    } else {
                        buffer.append(c)
                    }
                    i += 1
                }
                c == '`' -> {
                    val end = source.indexOf('`', i + 1)
                    if (end == -1) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        spans += MdSpan(source.substring(i + 1, end), code = true)
                        i = end + 1
                    }
                }
                c == '[' -> {
                    val match = LINK.matchAt(source, i)
                    if (match == null) {
                        buffer.append(c)
                        i++
                    } else {
                        val (label, url) = match.destructured
                        flush()
                        spans += MdSpan(label, bold, italic, strike, underline, link = url)
                        i += match.value.length
                    }
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return spans
    }

    private fun alignTable(table: MdBlock.Table): String {
        val rows = listOf(table.header) + table.rows
        val widths = table.header.indices.map { column ->
            rows.maxOf { plain(it.getOrElse(column) { "" }).length }
        }
        return rows.joinToString("\n") { row ->
            table.header.indices.joinToString("   ") { column ->
                plain(row.getOrElse(column) { "" }).padEnd(widths[column])
            }.trimEnd()
        } + "\n"
    }

    /** The text of a line with its inline Markdown markers taken out. */
    fun plain(text: String): String = parseInline(text).joinToString("") { it.text }

    /** `$$…$$` with content between, on one line. */
    private fun isInlineEquation(line: String): Boolean =
        line.length > 4 && line.startsWith("$$") && line.endsWith("$$")

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        val line = lines[index].trim()
        val next = lines.getOrNull(index + 1)?.trim() ?: return false
        if (line.startsWith("|")) return TABLE_DIVIDER.matches(next) || next.startsWith("|")
        // A pipe table rewritten without its outer pipes — what a translator hands back. The
        // separator row is what tells it apart from prose that happens to contain a pipe.
        return '|' in line && '|' in next && TABLE_DIVIDER.matches(next)
    }

    private fun isTableRow(line: String, bordered: Boolean): Boolean =
        if (bordered) line.startsWith("|") else '|' in line

    /** Splits on unescaped pipes only, so `\|` stays inside its cell. */
    private fun splitRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var endedOnPipe = false
        var i = if (line.startsWith("|")) 1 else 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> {
                    current.append(c).append(line[i + 1])
                    endedOnPipe = false
                    i += 2
                }
                c == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    endedOnPipe = true
                    i++
                }
                else -> {
                    current.append(c)
                    endedOnPipe = false
                    i++
                }
            }
        }
        if (!endedOnPipe) cells += current.toString().trim()
        return cells
    }

    /** A lone `*` is emphasis only next to a word, so `5 * 3` stays arithmetic. */

    /**
     * Whether the `*` at [index] is an emphasis marker rather than a literal asterisk.
     *
     * The end-of-line case is the one worth stating: a marker there can only ever *close*, and
     * treating it as literal left `*(5 min)*` at the end of a heading showing a stray asterisk
     * with the italic never turned back off. Opening at the end is still impossible, and the
     * caller's own "does a closer exist ahead" check is what keeps a trailing `hello*` literal.
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
}
