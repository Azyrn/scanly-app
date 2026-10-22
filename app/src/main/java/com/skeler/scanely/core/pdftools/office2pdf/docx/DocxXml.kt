package com.skeler.scanely.core.pdftools.office2pdf.docx

import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/*
 * Pull-parser plumbing shared by every part reader. All of it works on local names plus the
 * namespace URI, never on prefixes: "w:" is only a convention, and Strict OOXML files use a
 * different WordprocessingML namespace for the same elements.
 */

private val W_NAMESPACES = setOf(
    "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "http://purl.oclc.org/ooxml/wordprocessingml/main"
)

private val FALSE_VALUES = setOf("0", "false", "off", "none")

internal fun damaged(): ToolException = ToolException(ToolFailure.Damaged)

internal fun XmlPullParser.isW(): Boolean = namespace in W_NAMESPACES

/**
 * Calls [onChild] for each direct child element. [onChild] must consume the child it is handed
 * (read it to its end tag or [skip] it); otherwise the child's own children would be handed out
 * as if they were siblings.
 */
internal inline fun XmlPullParser.children(onChild: (name: String) -> Unit) {
    val start = depth
    // The readers recurse per nesting level; real documents stay far below this, crafted ones
    // would otherwise overflow the stack.
    if (start > MAX_DEPTH) throw damaged()
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> onChild(name)
            XmlPullParser.END_TAG -> if (depth == start) return
            XmlPullParser.END_DOCUMENT -> throw damaged()
        }
    }
}

/** Every start tag below the current element, in document order, without structure. */
internal inline fun XmlPullParser.descendants(onElement: (name: String) -> Unit) {
    val start = depth
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> onElement(name)
            XmlPullParser.END_TAG -> if (depth == start) return
            XmlPullParser.END_DOCUMENT -> throw damaged()
        }
    }
}

internal fun XmlPullParser.skip() {
    descendants { }
}

/** The element's character content; child elements are skipped. */
internal fun XmlPullParser.readText(): String {
    val builder = StringBuilder()
    val start = depth
    while (true) {
        when (next()) {
            XmlPullParser.TEXT -> builder.append(text)
            XmlPullParser.START_TAG -> skip()
            XmlPullParser.END_TAG -> if (depth == start) return builder.toString()
            XmlPullParser.END_DOCUMENT -> throw damaged()
        }
    }
}

internal fun XmlPullParser.attr(localName: String): String? {
    for (index in 0 until attributeCount) {
        if (getAttributeName(index) == localName) return getAttributeValue(index)
    }
    return null
}

/** r:embed, r:id and friends: the same local names as plain attributes, so the namespace decides. */
internal fun XmlPullParser.relAttr(localName: String): String? {
    for (index in 0 until attributeCount) {
        if (getAttributeName(index) == localName &&
            getAttributeNamespace(index).orEmpty().contains("relationships")
        ) {
            return getAttributeValue(index)
        }
    }
    return null
}

/** `<w:b/>` is on; `<w:b w:val="0"/>` is an explicit off that overrides an inherited on. */
internal fun XmlPullParser.toggle(): Boolean = attr("val")?.lowercase() !in FALSE_VALUES

internal fun XmlPullParser.intAttr(localName: String): Int? = parseTwips(attr(localName))

/**
 * Twips are integers in Transitional files, but Strict ones may write a universal measure
 * ("2.54cm", "72pt"). Anything unreadable is treated as absent rather than as damage.
 */
internal fun parseTwips(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val plain = value.toDoubleOrNull()
    if (plain != null) return plain.toInt()
    val number = value.dropLast(2).toDoubleOrNull()
    val factor = TWIPS_PER_UNIT[value.takeLast(2)]
    return if (number != null && factor != null) (number * factor).toInt() else null
}

private val TWIPS_PER_UNIT = mapOf(
    "pt" to 20.0,
    "in" to 1440.0,
    "cm" to 566.93,
    "mm" to 56.693,
    "pc" to 240.0,
    "pi" to 240.0
)

/** "FF0000" → 0xFF0000; "auto" → [AUTO_COLOR], which resets an inherited colour. */
internal fun parseColor(value: String?): Int? {
    if (value == null) return null
    if (value.equals("auto", ignoreCase = true)) return AUTO_COLOR
    return value.takeIf { it.length == HEX_COLOR_LENGTH }?.toIntOrNull(HEX_RADIX)
}

internal const val AUTO_COLOR = -1

/**
 * The part's bytes, or null when it is missing or bigger than [cap]. Entry sizes in the zip
 * directory can lie, so the cap is enforced on what actually comes out of the inflater.
 */
internal fun readPart(zip: ZipFile, path: String, cap: Long): ByteArray? {
    val entry = zip.getEntry(path) ?: return null
    return try {
        CappedInputStream(zip.getInputStream(entry), cap).use { it.readBytes() }
    } catch (ignored: ToolException) {
        null
    } catch (ignored: IOException) {
        // A corrupt entry is one unreadable picture, not a failed document.
        null
    }
}

/** Opens [path] and positions a fresh parser on its root element; null when the part is missing. */
internal fun <T> parsePart(
    zip: ZipFile,
    path: String,
    cap: Long,
    newParser: () -> XmlPullParser,
    read: (XmlPullParser) -> T
): T? {
    val entry = zip.getEntry(path) ?: return null
    return CappedInputStream(zip.getInputStream(entry), cap).use { stream ->
        val parser = newParser()
        parser.setInput(stream, null)
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> break
                XmlPullParser.END_DOCUMENT -> throw damaged()
            }
        }
        read(parser)
    }
}

/** The zip-bomb guard: inflating past [cap] bytes is treated as a damaged file. */
private class CappedInputStream(input: InputStream, private val cap: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) grow(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) grow(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { grow(it) }

    private fun grow(bytes: Long) {
        count += bytes
        if (count > cap) throw damaged()
    }
}

@PublishedApi
internal const val MAX_DEPTH = 256

private const val HEX_COLOR_LENGTH = 6
private const val HEX_RADIX = 16
