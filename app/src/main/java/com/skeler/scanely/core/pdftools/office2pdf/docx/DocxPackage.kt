package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.util.zip.ZipFile

/*
 * The OPC layer: which part is the document, and where a relationship id points. Part names are
 * found through relationships rather than assumed, because only Word itself reliably writes
 * "word/document.xml" and "word/media/".
 */

internal const val PART_CAP = 16L * 1024 * 1024

private const val ROOT_RELATIONSHIPS = "_rels/.rels"
private const val DEFAULT_DOCUMENT = "word/document.xml"
private const val HEADER_CAP = 8L * 1024 * 1024

/**
 * The platform parser with DTD processing off. A .docx never has a DOCTYPE; refusing one means a
 * crafted file can't define entities that expand to gigabytes (the zip cap bounds only input).
 */
internal fun platformParser(): XmlPullParser = Xml.newPullParser().apply {
    runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
}

internal fun mainDocumentPath(zip: ZipFile, newParser: () -> XmlPullParser): String {
    val rels = readRelationships(zip, ROOT_RELATIONSHIPS, "", newParser)
    return rels.values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.target
        ?: DEFAULT_DOCUMENT
}

/** The relationships of [partPath], keyed by id, with targets resolved to zip entry names. */
internal fun relationshipsOf(
    zip: ZipFile,
    partPath: String,
    newParser: () -> XmlPullParser
): Map<String, Relationship> {
    val dir = partPath.substringBeforeLast('/', "")
    val name = partPath.substringAfterLast('/')
    val relsPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
    return readRelationships(zip, relsPath, dir, newParser)
}

private fun readRelationships(
    zip: ZipFile,
    relsPath: String,
    baseDir: String,
    newParser: () -> XmlPullParser
): Map<String, Relationship> = parsePart(zip, relsPath, PART_CAP, newParser) { parser ->
    val rels = HashMap<String, Relationship>()
    parser.children { name ->
        val id = parser.attr("Id")
        val target = parser.attr("Target")
        if (name == "Relationship" && id != null && target != null) {
            val external = parser.attr("TargetMode") == "External"
            val resolved = if (external) target else resolvePartPath(baseDir, target)
            rels[id] = Relationship(parser.attr("Type").orEmpty(), resolved, external)
        }
        parser.skip()
    }
    rels
}.orEmpty()

/** "media/a.png" from "word" is "word/media/a.png"; "/x" is absolute; ".." walks up. */
internal fun resolvePartPath(baseDir: String, target: String): String {
    val decoded = if ('%' in target) {
        runCatching { URLDecoder.decode(target.replace("+", "%2B"), "UTF-8") }.getOrDefault(target)
    } else {
        target
    }
    val joined = when {
        decoded.startsWith("/") -> decoded
        baseDir.isEmpty() -> decoded
        else -> "$baseDir/$decoded"
    }
    val segments = ArrayDeque<String>()
    for (segment in joined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return segments.joinToString("/")
}

/**
 * Whether a header or footer part would print anything. Word often keeps empty ones around,
 * and saying "headers were left out" about an empty header would be noise. A part that can't
 * be read counts as empty: it can't be shown either way, and it mustn't fail the conversion.
 */
internal fun partHasContent(zip: ZipFile, path: String, newParser: () -> XmlPullParser): Boolean = try {
    parsePart(zip, path, HEADER_CAP, newParser) { parser ->
        var found = false
        parser.descendants { name ->
            if (!found && (name == "drawing" || name == "pict")) found = true
            if (!found && name == "t" && parser.readText().isNotBlank()) found = true
        }
        found
    } == true
} catch (ignored: Exception) {
    false
}
