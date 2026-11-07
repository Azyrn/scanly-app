package com.skeler.scanely.core.pdf

import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MathText
import com.skeler.scanely.core.text.MdBlock

/** Heading sizes in Word half-points for levels 1-3; deeper levels share the last size. */
private val HEADING_HALF_POINTS = listOf(36, 30, 26)
private const val DEEP_HEADING_HALF_POINTS = 24

internal fun wordMarkdownDocument(blocks: List<MdBlock>): String = buildString {
    append(WORD_TEXT_HEADER)
    blocks.forEach { block ->
        when (block) {
            is MdBlock.Bullet, is MdBlock.Numbered, is MdBlock.Table -> append(wordListOrTable(block))
            else -> append(wordProse(block))
        }
    }
    append(WORD_TEXT_FOOTER)
}

private fun wordProse(block: MdBlock): String = when (block) {
    is MdBlock.Heading -> wordParagraph(wordRuns(block.text, bold = true, size = headingHalfPoints(block.level)))

    is MdBlock.Paragraph -> wordParagraph(wordLines(block.lines))

    is MdBlock.Quote -> wordParagraph(
        wordLines(block.lines, italic = true),
        indent = wordIndent(1),
        properties = "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"6\" " +
            "w:color=\"888888\"/></w:pBdr>"
    )

    is MdBlock.Code -> wordParagraph(
        block.text.lines().joinToString(WORD_LINE_BREAK) { wordRun(it, mono = true) },
        properties = "<w:shd w:val=\"clear\" w:fill=\"F2F2F2\"/>"
    )

    is MdBlock.Equation -> wordParagraph(
        MathText.prettify(block.latex).lines()
            .joinToString(WORD_LINE_BREAK) { wordRun(it, italic = true) },
        properties = "<w:jc w:val=\"center\"/>"
    )

    is MdBlock.Image -> wordParagraph(wordRun(block.label, italic = true))

    MdBlock.Divider -> wordParagraph(
        "",
        properties = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" " +
            "w:color=\"999999\"/></w:pBdr>"
    )

    else -> ""
}

private fun headingHalfPoints(level: Int): Int =
    HEADING_HALF_POINTS.getOrElse(level - 1) { DEEP_HEADING_HALF_POINTS }

private fun wordListOrTable(block: MdBlock): String = when (block) {
    is MdBlock.Bullet -> {
        val marker = when (block.checked) {
            true -> "☑ "
            false -> "☐ "
            null -> "• "
        }
        wordParagraph(wordRun(marker) + wordRuns(block.text), indent = wordIndent(block.indent))
    }

    is MdBlock.Numbered -> wordParagraph(
        wordRun(block.marker + " ", bold = true) + wordRuns(block.text),
        indent = wordIndent(block.indent)
    )

    is MdBlock.Table -> wordTable(block)

    else -> ""
}

private fun wordTable(table: MdBlock.Table): String = buildString {
    val fill = PLAIN_FILL_HEX
    append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
    listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
        append("<w:$edge w:val=\"single\" w:sz=\"6\" w:color=\"555555\"/>")
    }
    append("</w:tblBorders><w:tblLayout w:type=\"autofit\"/></w:tblPr>")
    append(wordTableGrid(table))

    append("<w:tr>")
    table.header.forEach { cell ->
        append("<w:tc><w:tcPr><w:shd w:val=\"clear\" w:fill=\"$fill\"/></w:tcPr>")
        append(wordParagraph(wordRuns(cell, bold = true)))
        append("</w:tc>")
    }
    append("</w:tr>")

    table.rows.forEach { row ->
        append("<w:tr>")
        table.header.indices.forEach { column ->
            append("<w:tc>")
            append(wordParagraph(wordRuns(row.getOrElse(column) { "" })))
            append("</w:tc>")
        }
        append("</w:tr>")
    }
    // Word treats two adjacent tables as one without a paragraph between them.
    append("</w:tbl><w:p/>")
}

/**
 * Required: the schema for `w:tbl` is `(w:tblPr, w:tblGrid, rows…)`, and a reader that finds no
 * grid is entitled to drop the whole table. Android's Word viewer does exactly that — it drew
 * the headings around three tables that were silently gone.
 *
 * The widths are only a starting hint, proportional to the text each column holds; `tblW` at
 * 100% and the reader's own autofit settle the rest.
 */
private fun wordTableGrid(table: MdBlock.Table): String = buildString {
    val rows = listOf(table.header) + table.rows
    val weights = table.header.indices.map { column ->
        rows.maxOf { MarkdownParser.plain(it.getOrElse(column) { "" }).length }.coerceIn(4, 40)
    }
    val total = weights.sum().coerceAtLeast(1)
    append("<w:tblGrid>")
    weights.forEach { append("<w:gridCol w:w=\"${WORD_CONTENT_TWIPS * it / total}\"/>") }
    append("</w:tblGrid>")
}
