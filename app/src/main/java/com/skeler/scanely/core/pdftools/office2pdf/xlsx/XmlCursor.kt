package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * The few pull-parser operations the reader uses. An interface rather than XmlPullParser itself so
 * the reader runs in plain JVM tests, where android.util.Xml is only a stub (the test adapts StAX).
 *
 * Names are local names (namespaces processed), so `x:row` in a prefixed workbook reads as `row`.
 * [depth] is the element's depth at both its start and end event, root = 1.
 */
internal interface XmlCursor {
    fun next(): Int

    val name: String

    val depth: Int

    val text: String

    /** The first attribute with this local name, whatever its namespace. */
    fun attr(localName: String): String?

    companion object {
        const val END_DOCUMENT = 1
        const val START = 2
        const val END = 3
        const val TEXT = 4
        const val OTHER = 5
    }
}

/** A part was not well-formed XML, or not the shape OOXML promises. */
internal class XlsxFormatException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A part or the whole workbook unpacked past the size the converter is willing to read. */
internal class XlsxTooLargeException(message: String) : IllegalStateException(message)

internal class PullXmlCursor(stream: InputStream) : XmlCursor {
    private val parser: XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(stream, null)
    }

    /**
     * Android's parser reports a truncated part ("<row>" and then EOF) as a normal end of document
     * rather than throwing, so unclosed elements are counted here and turned into the same error.
     */
    private var open = 0

    override fun next(): Int {
        return when (nextEvent()) {
            XmlPullParser.START_TAG -> XmlCursor.START.also { open++ }
            XmlPullParser.END_TAG -> XmlCursor.END.also { open-- }
            XmlPullParser.TEXT -> XmlCursor.TEXT
            XmlPullParser.END_DOCUMENT -> {
                if (open != 0) throw XlsxFormatException("unclosed element")
                XmlCursor.END_DOCUMENT
            }
            else -> XmlCursor.OTHER
        }
    }

    private fun nextEvent(): Int = try {
        parser.next()
    } catch (e: XmlPullParserException) {
        throw XlsxFormatException("malformed XML", e)
    }

    override val name: String get() = parser.name.orEmpty()

    override val depth: Int get() = parser.depth

    override val text: String get() = parser.text.orEmpty()

    override fun attr(localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }
}

/** From a start tag, consumes everything up to and including its end tag. */
internal fun XmlCursor.skipElement() {
    val start = depth
    while (true) {
        when (next()) {
            XmlCursor.END -> if (depth == start) return
            XmlCursor.END_DOCUMENT -> throw XlsxFormatException("unclosed element")
        }
    }
}

/** From a start tag, the text directly and indirectly inside it, ending on its end tag. */
internal fun XmlCursor.readText(): String {
    val start = depth
    val builder = StringBuilder()
    while (true) {
        when (next()) {
            XmlCursor.TEXT -> builder.append(text)
            XmlCursor.END -> if (depth == start) return builder.toString()
            XmlCursor.END_DOCUMENT -> throw XlsxFormatException("unclosed element")
        }
    }
}

/**
 * From an `si` or `is` start tag: the plain text of a (possibly rich) string. Runs are joined, and
 * phonetic guides (`rPh`) are dropped because Excel doesn't print them either.
 */
internal fun XmlCursor.readRichText(): String {
    val start = depth
    val builder = StringBuilder()
    while (true) {
        when (next()) {
            XmlCursor.START -> when (name) {
                "t" -> builder.append(readText())
                "rPh" -> skipElement()
            }
            XmlCursor.END -> if (depth == start) return decodeEscapes(builder.toString())
            XmlCursor.END_DOCUMENT -> throw XlsxFormatException("unclosed string")
        }
    }
}

private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

/** OOXML writes characters XML can't carry (tabs, CRs) as `_xHHHH_`. */
internal fun decodeEscapes(value: String): String =
    if (!value.contains("_x")) value else ESCAPE.replace(value) { it.groupValues[1].toInt(HEX).toChar().toString() }

/** Shared across every part of one workbook, so many small parts can't add up to a bomb either. */
internal class ByteBudget(var remaining: Long)

/**
 * Counts what a part really unpacks to (the zip header's sizes are the attacker's to choose), and
 * refuses two shapes a spreadsheet never has: any DOCTYPE (OOXML never carries one, and refusing it
 * outright closes entity expansion whatever the platform parser would do with an internal subset),
 * and a single run of text or markup longer than [MAX_RUN_BYTES]. A parser buffers a whole text
 * node in memory, so without the second check one megabyte-deflated run of spaces is an OOM.
 */
internal class GuardedStream(
    input: InputStream,
    private val partLimit: Long,
    private val budget: ByteBudget
) : FilterInputStream(input) {
    private var count = 0L
    private var matched = 0
    private var sinceTag = 0

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) account(b, off, read)
        return read
    }

    private fun account(b: ByteArray, off: Int, len: Int) {
        count += len
        budget.remaining -= len
        if (count > partLimit || budget.remaining < 0) throw XlsxTooLargeException("workbook unpacks too large")
        for (i in off until off + len) inspect(b[i])
    }

    /** Tracks the current run's length and any `<!DOCTYPE` being spelled out, byte by byte. */
    private fun inspect(byte: Byte) {
        sinceTag = if (byte == TAG_OPEN) 0 else sinceTag + 1
        if (sinceTag > MAX_RUN_BYTES) throw XlsxFormatException("text run too long")
        matched = when (byte) {
            DOCTYPE[matched] -> matched + 1
            DOCTYPE[0] -> 1
            else -> 0
        }
        if (matched == DOCTYPE.size) throw XlsxFormatException("DOCTYPE in a workbook part")
    }

    private companion object {
        val DOCTYPE = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
        const val TAG_OPEN = '<'.code.toByte()

        /** A cell holds at most 32,767 characters; escaped as UTF-8 that is well under this. */
        const val MAX_RUN_BYTES = 1024 * 1024
    }
}

private const val HEX = 16
