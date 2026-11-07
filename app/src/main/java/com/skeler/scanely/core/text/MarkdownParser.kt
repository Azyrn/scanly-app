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

    /** Longer than this above a fenced answer is content, not the model clearing its throat. */
    private const val MAX_LEAD_IN = 200

    private val MARKDOWN_LINE_TESTS: List<(String) -> Boolean> = listOf(
        { it.startsWith("|") && it.endsWith("|") },
        { HEADING.matches(it) },
        { it.startsWith("```") },
        { it.startsWith("- ") },
        { it == "$$" || isInlineEquation(it) },
        { IMAGE.matches(it) },
        { "**" in it },
    )

    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lines()
        return lines.indices.any { index ->
            val trimmed = lines[index].trim()
            MARKDOWN_LINE_TESTS.any { test -> test(trimmed) } || isTableStart(lines, index)
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

    fun parse(source: String): List<MdBlock> =
        MarkdownBlockParser(source.replace("\r\n", "\n").split("\n")).parse()

    /** Reading view: same content, Markdown syntax taken back out, tables aligned with spaces. */
    fun toPlainText(source: String): String = buildString {
        parse(source).forEach { block -> appendPlain(block) }
    }.trim()

    private fun StringBuilder.appendPlain(block: MdBlock) {
        when (block) {
            is MdBlock.Bullet -> appendLine(plainBullet(block))
            is MdBlock.Numbered ->
                appendLine(" ".repeat(block.indent) + block.marker + " " + plain(block.text))
            is MdBlock.Table -> appendLine(alignTable(block))
            MdBlock.Divider -> appendLine()
            else -> appendBlock(plainBlockText(block))
        }
    }

    /** [text] on its own lines, then a blank line. */
    private fun StringBuilder.appendBlock(text: String) {
        appendLine(text)
        appendLine()
    }

    fun parseInline(source: String): List<MdSpan> = MarkdownInlineParser(source).parse()

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
}

private fun plainBullet(block: MdBlock.Bullet): String {
    val box = when (block.checked) {
        true -> "☑ "
        false -> "☐ "
        null -> ""
    }
    return " ".repeat(block.indent) + "• " + box + MarkdownParser.plain(block.text)
}

/** The text of a block that stands as its own paragraph in the reading view. */
private fun plainBlockText(block: MdBlock): String = when (block) {
    is MdBlock.Heading -> MarkdownParser.plain(block.text)
    is MdBlock.Paragraph -> block.lines.joinToString("\n") { MarkdownParser.plain(it) }
    is MdBlock.Quote -> block.lines.joinToString("\n") { MarkdownParser.plain(it) }
    is MdBlock.Code -> block.text
    is MdBlock.Equation -> MathText.prettify(block.latex)
    is MdBlock.Image -> block.label
    else -> ""
}
