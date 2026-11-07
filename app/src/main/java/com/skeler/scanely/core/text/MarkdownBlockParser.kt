package com.skeler.scanely.core.text

internal val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val DIVIDER = Regex("^\\s*([-*_])\\s*\\1\\s*\\1[\\s\\-*_]*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
private val CHECKBOX = Regex("^\\[([ xX])]\\s+(.*)$")
private val TABLE_DIVIDER = Regex("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$")
internal val IMAGE = Regex("^!\\[([^]]*)]\\(([^)]*)\\)$")

private const val FENCE = "```"
private const val MATH_FENCE = "$$"

/** One pass over a document's lines into blocks; paragraph and quote lines gather until a break. */
internal class MarkdownBlockParser(private val lines: List<String>) {
    private val blocks = mutableListOf<MdBlock>()
    private val paragraph = mutableListOf<String>()
    private val quote = mutableListOf<String>()
    private var i = 0

    fun parse(): List<MdBlock> {
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            if (!standaloneBlock(line)) lineBlock(raw, line)
            i++
        }
        flush()
        return blocks
    }

    private fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toList())
            paragraph.clear()
        }
        if (quote.isNotEmpty()) {
            blocks += MdBlock.Quote(quote.toList())
            quote.clear()
        }
    }

    /** Fences, blank lines, equations, images and tables; false when [line] is none of them. */
    private fun standaloneBlock(line: String): Boolean {
        when {
            line.startsWith(FENCE) -> code()
            line.isEmpty() -> flush()

            // Display math: `$$…$$` on one line, or a bare `$$` fence closed lines later.
            // `$$50 off` is prose, not an opener, so money never swallows the document.
            isInlineEquation(line) -> {
                flush()
                blocks += MdBlock.Equation(line.removeSurrounding(MATH_FENCE).trim())
            }

            line == MATH_FENCE -> equation()
            IMAGE.matches(line) -> image(line)
            isTableStart(lines, i) -> table()
            else -> return false
        }
        return true
    }

    private fun lineBlock(raw: String, line: String) {
        when {
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

            BULLET.matches(raw) -> bullet(raw)

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
    }

    private fun code() {
        flush()
        val body = mutableListOf<String>()
        i++
        while (i < lines.size && !lines[i].trim().startsWith(FENCE)) {
            body += lines[i]
            i++
        }
        blocks += MdBlock.Code(body.joinToString("\n"))
    }

    private fun equation() {
        flush()
        val body = mutableListOf<String>()
        i++
        while (i < lines.size && !lines[i].trim().endsWith(MATH_FENCE)) {
            body += lines[i].trim()
            i++
        }
        if (i < lines.size) {
            lines[i].trim().removeSuffix(MATH_FENCE).trim()
                .takeIf { it.isNotEmpty() }?.let { body += it }
        }
        blocks += MdBlock.Equation(body.joinToString("\n").trim())
    }

    private fun image(line: String) {
        flush()
        val (alt, url) = IMAGE.find(line)!!.destructured
        blocks += MdBlock.Image(alt.trim(), url.trim())
    }

    private fun table() {
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

    private fun bullet(raw: String) {
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
}

/** `$$…$$` with content between, on one line. */
internal fun isInlineEquation(line: String): Boolean =
    line.length > 2 * MATH_FENCE.length && line.startsWith(MATH_FENCE) && line.endsWith(MATH_FENCE)

internal fun isTableStart(lines: List<String>, index: Int): Boolean {
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
