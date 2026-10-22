package com.skeler.scanely.core.pdftools.pdf2office

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EMUs (the unit every OOXML drawing uses) per PDF point. */
internal const val EMU_PER_POINT = 12_700L

internal const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

internal const val NS_RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
internal const val NS_OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val NS_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
internal const val REL_OFFICE_DOCUMENT = "$NS_OFFICE_RELS/officeDocument"
internal const val REL_IMAGE = "$NS_OFFICE_RELS/image"
internal const val TYPE_RELATIONSHIPS = "application/vnd.openxmlformats-package.relationships+xml"

/**
 * Text from a PDF as XML character data. Escapes the markup characters and drops what XML 1.0
 * cannot hold at all — control characters, lone surrogates, U+FFFE/FFFF — which PDF text layers
 * do produce, and which make Word refuse the whole file rather than one paragraph.
 */
internal fun xmlText(text: String): String {
    val out = StringBuilder(text.length + ESCAPE_HEADROOM)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char.isHighSurrogate()) {
            // Kept only as a whole pair; a lone half is dropped.
            if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                out.append(char).append(text[index + 1])
                index++
            }
        } else {
            appendXmlChar(out, char)
        }
        index++
    }
    return out.toString()
}

/** One non-high-surrogate character: escaped, kept, or dropped when XML 1.0 can't hold it. */
private fun appendXmlChar(out: StringBuilder, char: Char) {
    val escape = XML_ESCAPES[char]
    when {
        escape != null -> out.append(escape)
        char in KEPT_CONTROLS -> out.append(char)
        char < ' ' || char in NON_CHARACTERS || char.isLowSurrogate() -> Unit
        else -> out.append(char)
    }
}

private val XML_ESCAPES = mapOf('&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '"' to "&quot;", '\'' to "&apos;")
private val KEPT_CONTROLS = setOf('\t', '\n', '\r')
private val NON_CHARACTERS = setOf('\uFFFE', '\uFFFF')

internal fun ZipOutputStream.writeEntry(path: String, content: String) {
    putNextEntry(ZipEntry(path))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

internal fun ZipOutputStream.copyEntry(path: String, source: File) {
    putNextEntry(ZipEntry(path))
    source.inputStream().use { it.copyTo(this) }
    closeEntry()
}

internal fun relationship(id: String, type: String, target: String): String =
    "<Relationship Id=\"$id\" Type=\"$type\" Target=\"${xmlText(target)}\"/>"

internal fun relationships(entries: List<String>): String =
    XML_HEADER + "<Relationships xmlns=\"$NS_RELATIONSHIPS\">" + entries.joinToString("") + "</Relationships>"

/** The package's root relationship, the same in every OOXML format. */
internal fun packageRelationships(mainPart: String): String =
    relationships(listOf(relationship("rId1", REL_OFFICE_DOCUMENT, mainPart)))

internal fun contentTypes(defaults: Map<String, String>, overrides: Map<String, String>): String = buildString {
    append(XML_HEADER)
    append("<Types xmlns=\"$NS_CONTENT_TYPES\">")
    defaults.forEach { (extension, type) -> append("<Default Extension=\"$extension\" ContentType=\"$type\"/>") }
    overrides.forEach { (part, type) -> append("<Override PartName=\"$part\" ContentType=\"$type\"/>") }
    append("</Types>")
}

private const val ESCAPE_HEADROOM = 16
