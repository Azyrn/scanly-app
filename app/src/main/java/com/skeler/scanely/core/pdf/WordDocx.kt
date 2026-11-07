package com.skeler.scanely.core.pdf

import android.graphics.Bitmap
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MdSpan
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// The OOXML parts of the .docx files the exports write.

/** A quarter inch, Word's default list indent step, in twentieths of a point. */
private const val TWIPS_PER_INDENT_LEVEL = 360

internal fun ZipOutputStream.writeText(path: String, content: String) {
    putNextEntry(ZipEntry(path))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

internal const val WORD_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

internal const val PACKAGE_RELATIONSHIPS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

internal fun wordRelationships(pageCount: Int): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
    repeat(pageCount) { index ->
        append(
            "<Relationship Id=\"rId${index + 1}\"" +
                " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"" +
                " Target=\"media/image${index + 1}.jpg\"/>"
        )
    }
    append("</Relationships>")
}

internal fun wordDocument(bitmaps: List<Bitmap>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    append(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
    )
    bitmaps.forEachIndexed { index, bitmap ->
        val (cx, cy) = wordImageExtent(bitmap)
        val id = index + 1
        append(
            "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Scan $id\"/><a:graphic>" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
                "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"Scan $id\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>" +
                "<a:blip r:embed=\"rId$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr>" +
                "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData>" +
                "</a:graphic></wp:inline></w:drawing></w:r></w:p>"
        )
        if (index != bitmaps.lastIndex) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
    }
    append(
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body>" +
            "</w:document>"
    )
}

internal fun wordTextDocument(text: String): String = buildString {
    append(WORD_TEXT_HEADER)
    text.lineSequence().forEach { line ->
        append("<w:p><w:r><w:t xml:space=\"preserve\">")
        append(escapeXml(line))
        append("</w:t></w:r></w:p>")
    }
    append(WORD_TEXT_FOOTER)
}

internal fun wordParagraph(
    runs: String,
    indent: String = "",
    properties: String = ""
): String {
    val paragraphProperties = indent + properties
    val prefix = if (paragraphProperties.isEmpty()) "" else "<w:pPr>$paragraphProperties</w:pPr>"
    return "<w:p>$prefix$runs</w:p>"
}

internal fun wordIndent(level: Int): String =
    if (level <= 0) "" else "<w:ind w:left=\"${level * TWIPS_PER_INDENT_LEVEL}\"/>"

internal fun wordLines(lines: List<String>, italic: Boolean = false): String =
    lines.joinToString(WORD_LINE_BREAK) { wordRuns(it, italic = italic) }

/** The line's inline emphasis, carried over as Word run properties. */
internal fun wordRuns(
    text: String,
    bold: Boolean = false,
    italic: Boolean = false,
    size: Int = 0
): String = MarkdownParser.parseInline(text).joinToString("") { span: MdSpan ->
    wordRun(
        text = span.text,
        bold = bold || span.bold,
        italic = italic || span.italic,
        strike = span.strike,
        underline = span.underline || span.link != null,
        mono = span.code,
        size = size
    )
}

internal fun wordRun(
    text: String,
    bold: Boolean = false,
    italic: Boolean = false,
    strike: Boolean = false,
    underline: Boolean = false,
    mono: Boolean = false,
    size: Int = 0
): String = buildString {
    append("<w:r><w:rPr>")
    if (bold) append("<w:b/>")
    if (italic) append("<w:i/>")
    if (strike) append("<w:strike/>")
    if (underline) append("<w:u w:val=\"single\"/>")
    if (mono) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
    if (size > 0) append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
    append("</w:rPr><w:t xml:space=\"preserve\">")
    append(escapeXml(text))
    append("</w:t></w:r>")
}

internal const val WORD_TEXT_HEADER =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"

/**
 * `w:br` is a *run* element. Emitted as a direct child of `w:p` it is invalid, and readers drop
 * it — which is how three labelled lines exported as one run-on `Client: …Date: …Project: …`.
 */
internal const val WORD_LINE_BREAK = "<w:r><w:br/></w:r>"

internal const val PLAIN_FILL_HEX = "EEEEEE"

/** Page width less both margins, from [WORD_TEXT_FOOTER]'s `w:pgSz` and `w:pgMar`. */
internal const val WORD_CONTENT_TWIPS = 11906 - 2 * 720

internal const val WORD_TEXT_FOOTER =
    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>"

private val XML_ESCAPES = mapOf('&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '"' to "&quot;", '\'' to "&apos;")

internal fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        // Control characters are illegal in XML 1.0; Word rejects the whole file as corrupt.
        if (character < ' ' && character != '\t') return@forEach
        val escaped = XML_ESCAPES[character]
        if (escaped != null) append(escaped) else append(character)
    }
}

private fun wordImageExtent(bitmap: Bitmap): Pair<Long, Long> {
    val pageWidth = 5_943_600L // A4 width minus 0.5in margins, in EMUs.
    val pageHeight = 8_229_600L // A4 height minus 0.5in margins, in EMUs.
    return if (bitmap.width.toLong() * pageHeight > bitmap.height.toLong() * pageWidth) {
        pageWidth to (pageWidth * bitmap.height / bitmap.width)
    } else {
        (pageHeight * bitmap.width / bitmap.height) to pageHeight
    }
}
