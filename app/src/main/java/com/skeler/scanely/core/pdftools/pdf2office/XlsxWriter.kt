package com.skeler.scanely.core.pdftools.pdf2office

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val NS_SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val SML = "application/vnd.openxmlformats-officedocument.spreadsheetml"
private const val TYPE_WORKBOOK = "$SML.sheet.main+xml"
private const val TYPE_WORKSHEET = "$SML.worksheet+xml"
private const val TYPE_SHEET_STYLES = "$SML.styles+xml"
private const val REL_WORKSHEET = "$NS_OFFICE_RELS/worksheet"
private const val REL_SHEET_STYLES = "$NS_OFFICE_RELS/styles"

/**
 * A .xlsx written sheet by sheet, each sheet streamed row by row into its zip entry. Text goes in
 * as inline strings rather than a shared-string table, which would have to be held in memory until
 * the end; numbers go in as numbers so the user can sum a converted column straight away.
 */
internal class XlsxWriter(out: OutputStream) {

    private val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_BYTES))
    private val sheetNames = mutableListOf<String>()

    /** Rows past Excel's limit are dropped; [rows] is consumed lazily. */
    fun addSheet(name: String, rows: Sequence<List<String>>) {
        sheetNames += uniqueName(name)
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet${sheetNames.size}.xml"))
        // Not closed: closing would close the zip under it. Flushed before the entry closes.
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write(XML_HEADER)
        writer.write("<worksheet xmlns=\"$NS_SHEET\"><sheetData>")
        var rowNumber = 0
        for (row in rows.take(MAX_ROWS)) {
            rowNumber++
            // An empty row is numbered but not written.
            if (row.all { it.isEmpty() }) continue
            writer.write("<row r=\"$rowNumber\">")
            row.take(MAX_COLUMNS).forEachIndexed { column, value ->
                if (value.isNotEmpty()) writer.write(cell("${columnName(column)}$rowNumber", value))
            }
            writer.write("</row>")
        }
        writer.write("</sheetData></worksheet>")
        writer.flush()
        zip.closeEntry()
    }

    val sheetCount: Int get() = sheetNames.size

    /** Closes every part and the zip. A workbook must hold a sheet, so an empty one is added if needed. */
    fun finish(emptySheetName: String) {
        if (sheetNames.isEmpty()) addSheet(emptySheetName, emptySequence())
        zip.writeEntry(
            "xl/workbook.xml",
            XML_HEADER + "<workbook xmlns=\"$NS_SHEET\" xmlns:r=\"$NS_OFFICE_RELS\"><sheets>" +
                sheetNames.mapIndexed { index, name ->
                    "<sheet name=\"${xmlText(name)}\" sheetId=\"${index + 1}\" r:id=\"rIdSheet${index + 1}\"/>"
                }.joinToString("") +
                "</sheets></workbook>"
        )
        zip.writeEntry(
            "xl/_rels/workbook.xml.rels",
            relationships(
                sheetNames.indices.map {
                    relationship("rIdSheet${it + 1}", REL_WORKSHEET, "worksheets/sheet${it + 1}.xml")
                } +
                    relationship("rIdStyles", REL_SHEET_STYLES, "styles.xml")
            )
        )
        zip.writeEntry("xl/styles.xml", STYLES)
        zip.writeEntry("_rels/.rels", packageRelationships("xl/workbook.xml"))
        zip.writeEntry(
            "[Content_Types].xml",
            contentTypes(
                defaults = mapOf("rels" to TYPE_RELATIONSHIPS, "xml" to "application/xml"),
                overrides = mapOf(
                    "/xl/workbook.xml" to TYPE_WORKBOOK,
                    "/xl/styles.xml" to TYPE_SHEET_STYLES
                ) + sheetNames.indices.associate { "/xl/worksheets/sheet${it + 1}.xml" to TYPE_WORKSHEET }
            )
        )
        zip.close()
    }

    private fun cell(reference: String, value: String): String {
        val number = numericValue(value)
        return if (number != null) {
            "<c r=\"$reference\"><v>$number</v></c>"
        } else {
            "<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                xmlText(value.take(MAX_CELL_CHARS)) + "</t></is></c>"
        }
    }

    /** Excel's rules: at most 31 characters, none of `[]:*?/\`, unique ignoring case. */
    private fun uniqueName(name: String): String {
        val base = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(MAX_SHEET_NAME).ifEmpty { "Sheet" }
        var candidate = base
        var suffix = 2
        while (sheetNames.any { it.equals(candidate, ignoreCase = true) }) {
            val tail = " ($suffix)"
            candidate = base.take(MAX_SHEET_NAME - tail.length) + tail
            suffix++
        }
        return candidate
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_ROWS = 1_048_576
        private const val MAX_COLUMNS = 16_384
        private const val MAX_CELL_CHARS = 32_767
        private const val MAX_SHEET_NAME = 31
        private const val ALPHABET = 26

        /** Past this many significant digits Excel rounds, which would silently change an ID. */
        private const val MAX_DIGITS = 15

        private val NUMBER = Regex("^[-+]?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?$")

        /**
         * The value a cell should hold as a number, or null to keep it as text. Thousands
         * separators are accepted; a leading zero ("007") stays text, since the zero is the point.
         */
        internal fun numericValue(text: String): String? {
            val trimmed = text.trim()
            if (!NUMBER.matches(trimmed)) return null
            val plain = trimmed.replace(",", "").removePrefix("+")
            val digits = plain.trimStart('-')
            if (digits.length > 1 && digits[0] == '0' && digits[1] != '.') return null
            if (digits.count { it.isDigit() } > MAX_DIGITS) return null
            return runCatching { BigDecimal(plain).toPlainString() }.getOrNull()
        }

        internal fun columnName(index: Int): String {
            val name = StringBuilder()
            var remaining = index + 1
            while (remaining > 0) {
                val digit = (remaining - 1) % ALPHABET
                name.insert(0, 'A' + digit)
                remaining = (remaining - 1) / ALPHABET
            }
            return name.toString()
        }

        private val STYLES = XML_HEADER + "<styleSheet xmlns=\"$NS_SHEET\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }
}
