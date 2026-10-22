package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.xlsx.CellKind
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.CellRange
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.NumberFormatter
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.PageOrientation
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxCell
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxFormatException
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxLayout
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxReader
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxSheet
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxSheetReader
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxTooLargeException
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XmlCursor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * The .xlsx reader and number formatting on the JVM. android.util.Xml is a stub here, so parts are
 * parsed through a StAX adapter with the same contract as the app's pull-parser cursor; everything
 * else is the production code path.
 */
class XlsxReaderTest {

    private lateinit var dir: File
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("xlsx-reader-test").toFile()
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        Locale.setDefault(savedLocale)
    }

    // --- workbook ---

    @Test
    fun hiddenSheetsAreSkippedAndCounted() {
        open(workbook()).use { reader ->
            assertEquals(listOf("Data", "Wide"), reader.sheets.map { it.name })
            assertEquals("xl/worksheets/sheet1.xml", reader.sheets[0].path)
            assertEquals(1, reader.notes.hiddenSheets)
        }
    }

    @Test
    fun cellsShowWhatExcelWouldPrint() {
        open(workbook()).use { reader ->
            val sheet = reader.readSheet(reader.sheets[0])

            assertEquals("Name", text(sheet, "A1"))
            assertEquals("Total due", text(sheet, "B1"))
            assertTrue(cell(sheet, "A1")!!.style.font.bold)
            assertEquals("1,234.50", text(sheet, "A2"))
            assertEquals(CellKind.NUMBER, cell(sheet, "A2")!!.kind)
            assertEquals("25.60%", text(sheet, "B2"))
            assertEquals("2023-03-15", text(sheet, "C2"))
            assertEquals("3/15/2023", text(sheet, "D2"))
            assertEquals("TRUE", text(sheet, "A3"))
            assertEquals("#DIV/0!", text(sheet, "B3"))
            assertEquals("42", text(sheet, "C3"))
            assertNull(cell(sheet, "D3"))
            assertEquals("Merged title", text(sheet, "A4"))
            assertEquals("tab\there", text(sheet, "A5"))
        }
    }

    @Test
    fun stylesMergesAndSizesAreRead() {
        open(workbook()).use { reader ->
            val sheet = reader.readSheet(reader.sheets[0])

            assertEquals(listOf(CellRange(3, 0, 3, 2)), sheet.merges)
            val styled = cell(sheet, "E6")!!
            assertEquals("", styled.text)
            assertEquals(0xFFFFFF00.toInt(), styled.style.fill)
            assertTrue(styled.style.borders.any)
            assertEquals(30f, sheet.columns.widths[0])
            assertTrue(1 in sheet.columns.hidden)
            assertEquals(40f, sheet.rows.heightsPt[0])
            assertTrue(0 in sheet.rows.custom)
        }
    }

    @Test
    fun unsupportedContentIsCountedNotDrawn() {
        open(workbook()).use { reader ->
            reader.readSheet(reader.sheets[0])

            assertEquals(1, reader.notes.charts)
            assertEquals(1, reader.notes.pictures)
            assertEquals(1, reader.notes.cachedFormulas)
            assertEquals(1, reader.notes.missingFormulas)
            assertEquals(1, reader.notes.conditionalFormats)
        }
    }

    @Test
    fun wideSheetKeepsEveryColumn() {
        open(workbook()).use { reader ->
            val sheet = reader.readSheet(reader.sheets[1])

            assertEquals(WIDE_COLUMNS, sheet.cells.size)
            assertEquals(WIDE_COLUMNS - 1, sheet.cells.last().col)
            assertEquals("Col 40", sheet.cells.last().text)
        }
    }

    @Test
    fun wideSheetTurnsLandscapeAndSplitsDownThenOver() {
        open(workbook()).use { reader ->
            val layout = XlsxLayout.layout(reader.readSheet(reader.sheets[1])) { _, _ -> 0f }!!

            // 40 default columns of 48 pt: 1,920 pt, 15 columns to a landscape page.
            assertEquals(PageOrientation.LANDSCAPE, layout.frame.orientation)
            assertEquals(48f, layout.colLefts[1], 0.01f)
            assertEquals(listOf(0 to 15, 15 to 30, 30 to 40), layout.slices.map { it.colFrom to it.colTo })
        }
    }

    @Test
    fun hugeSheetIsCutAtAWholeRow() {
        val rows = XlsxSheetReader.MAX_CELLS / 2 + 10
        val sheetXml = buildString {
            append("<worksheet $NS><sheetData>")
            for (r in 1..rows) append("<row r=\"$r\"><c r=\"A$r\"><v>$r</v></c><c r=\"B$r\"><v>1</v></c></row>")
            append("</sheetData></worksheet>")
        }
        open(workbook(sheet1 = sheetXml)).use { reader ->
            val sheet = reader.readSheet(reader.sheets[0])

            assertEquals(XlsxSheetReader.MAX_CELLS, sheet.cells.size)
            assertEquals(XlsxSheetReader.MAX_CELLS / 2, sheet.cutAfterRow)
            assertEquals(XlsxSheetReader.MAX_CELLS / 2, reader.notes.cutAtRow)
        }
    }

    @Test
    fun date1904WorkbooksCountFrom1904() {
        val book = workbook(workbookPr = "<workbookPr date1904=\"1\"/>")
        open(book).use { reader ->
            val sheet = reader.readSheet(reader.sheets[0])
            // Serial 45000 is 1462 days later in the 1904 system.
            assertEquals("2027-03-16", text(sheet, "C2"))
        }
    }

    // --- failures ---

    @Test
    fun randomBytesAreNotAZip() {
        val file = File(dir, "random.xlsx").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        try {
            open(file).close()
            fail("opened random bytes")
        } catch (expected: ZipException) {
            // the job maps this to Damaged
        }
    }

    @Test
    fun zipWithoutAWorkbookIsMalformed() {
        val file = zip("empty.xlsx", mapOf("hello.txt" to "hi"))
        assertMalformed { open(file).close() }
    }

    @Test
    fun brokenXmlIsMalformed() {
        val file = workbook(sheet1 = "<worksheet $NS><sheetData><row>")
        assertMalformed { open(file).use { it.readSheet(it.sheets[0]) } }
    }

    @Test
    fun doctypeIsRefused() {
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE lol [<!ENTITY a \"aaaa\">]><worksheet $NS/>"
        assertMalformed { open(workbook(sheet1 = bomb)).use { it.readSheet(it.sheets[0]) } }
    }

    @Test
    fun oneHugeTextRunIsRefused() {
        val run = " ".repeat(2 * 1024 * 1024)
        val file = workbook(sheet1 = "<worksheet $NS>$run</worksheet>")
        assertMalformed { open(file).use { it.readSheet(it.sheets[0]) } }
    }

    @Test
    fun partThatUnpacksTooLargeIsRefused() {
        // Repetitive markup deflates ~1000:1, the shape of a zip bomb; streamed so the test stays small.
        val file = File(dir, "bomb.xlsx")
        ZipOutputStream(file.outputStream()).use { out ->
            ZipFile(workbook()).use { source ->
                source.entries().asSequence().filter { it.name != "xl/worksheets/sheet1.xml" }.forEach {
                    out.putNextEntry(ZipEntry(it.name))
                    source.getInputStream(it).copyTo(out)
                    out.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            out.write("<worksheet $NS>".toByteArray())
            val filler = ("<x a=\"" + "a".repeat(1000) + "\"/>").repeat(1000).toByteArray()
            repeat((XlsxReader.MAX_PART_BYTES / filler.size + 1).toInt()) { out.write(filler) }
            out.write("</worksheet>".toByteArray())
            out.closeEntry()
        }
        try {
            open(file).use { it.readSheet(it.sheets[0]) }
            fail("read an oversized part")
        } catch (expected: XlsxTooLargeException) {
            // the job maps this to Failed
        }
    }

    // --- number formats ---

    @Test
    fun generalFormat() {
        val f = NumberFormatter(date1904 = false, locale = Locale.US)
        assertEquals("0.3", f.format(0.1 + 0.2, "General"))
        assertEquals("1234567", f.format(1_234_567.0, "General"))
        assertEquals("-12.5", f.format(-12.5, "General"))
        assertEquals("1.23457E+11", f.format(123_456_789_012.0, "General"))
        assertEquals("1E+12", f.format(1e12, "General"))
    }

    @Test
    fun numberFormats() {
        val f = NumberFormatter(date1904 = false, locale = Locale.US)
        assertEquals("(1,234.50)", f.format(-1234.5, "#,##0.00;(#,##0.00)"))
        assertEquals("-5", f.format(-5.0, "0"))
        assertEquals("50%", f.format(0.5, "0%"))
        assertEquals("1.50E+00", f.format(1.5, "0.00E+00"))
        assertEquals("$5", f.format(5.0, "\"$\"#,##0"))
        assertEquals("€7.00", f.format(7.0, "[\$€-407]0.00"))
        assertEquals("1,235", f.format(1_234_567.0, "#,##0,"))
        assertEquals("123-45-6789", f.format(123_456_789.0, "000-00-0000"))
        assertEquals("2.68", f.format(2.675, "0.00"))
        assertEquals("007", f.format(7.0, "000"))
        assertEquals("zero", f.format(0.0, "0;-0;\"zero\""))
    }

    @Test
    fun dateFormatsHandleTheLeapYearBug() {
        val f = NumberFormatter(date1904 = false, locale = Locale.US)
        assertEquals("1900-01-01", f.format(1.0, "yyyy-mm-dd"))
        assertEquals("1900-02-28", f.format(59.0, "yyyy-mm-dd"))
        assertEquals("1900-02-29", f.format(60.0, "yyyy-mm-dd"))
        assertEquals("1900-03-01", f.format(61.0, "yyyy-mm-dd"))
        assertEquals("15-Mar-23", f.format(45_000.0, "d-mmm-yy"))
        assertEquals("Wednesday, March 15, 2023", f.format(45_000.0, "dddd, mmmm d, yyyy"))
        assertEquals("6:00 PM", f.format(0.75, "h:mm AM/PM"))
        assertEquals("36:00", f.format(1.5, "[h]:mm"))
        assertEquals("12:30:15", f.format(0.521_006_944_4, "hh:mm:ss"))
        assertEquals("1904-01-01", NumberFormatter(date1904 = true, locale = Locale.US).format(0.0, "yyyy-mm-dd"))
    }

    @Test
    fun cellReferences() {
        assertEquals(0 to 0, XlsxSheetReader.parseRef("A1"))
        assertEquals(11 to 27, XlsxSheetReader.parseRef("AB12"))
        assertEquals(0 to 16_383, XlsxSheetReader.parseRef("XFD1"))
        assertNull(XlsxSheetReader.parseRef("XFE1"))
        assertNull(XlsxSheetReader.parseRef("12"))
        assertEquals("xl/media/a.png", XlsxReader.resolve("xl/worksheets", "../media/a.png"))
        assertEquals("xl/styles.xml", XlsxReader.resolve("xl", "/xl/styles.xml"))
    }

    // --- helpers ---

    private fun open(file: File): XlsxReader = XlsxReader.open(file, cursorFactory = ::StaxCursor)

    private fun cell(sheet: XlsxSheet, ref: String): XlsxCell? {
        val (row, col) = XlsxSheetReader.parseRef(ref)!!
        return sheet.cells.firstOrNull { it.row == row && it.col == col }
    }

    private fun text(sheet: XlsxSheet, ref: String): String? = cell(sheet, ref)?.text

    private fun assertMalformed(block: () -> Unit) {
        try {
            block()
            fail("expected a malformed workbook")
        } catch (expected: XlsxFormatException) {
            assertFalse(expected.message.isNullOrEmpty())
        }
    }

    private fun zip(name: String, parts: Map<String, String>): File = File(dir, name).apply {
        ZipOutputStream(outputStream()).use { out ->
            parts.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun workbook(sheet1: String = SHEET1, workbookPr: String = ""): File = zip(
        "book.xlsx",
        mapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "_rels/.rels" to rels("rId1" to (OFFICE_DOC to "xl/workbook.xml")),
            "xl/workbook.xml" to """<workbook $NS $R_NS>$workbookPr<sheets>
                <sheet name="Data" sheetId="1" r:id="rId1"/>
                <sheet name="Secret" sheetId="2" state="hidden" r:id="rId2"/>
                <sheet name="Wide" sheetId="3" r:id="rId3"/>
                </sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to rels(
                "rId1" to (WORKSHEET to "worksheets/sheet1.xml"),
                "rId2" to (WORKSHEET to "worksheets/sheet2.xml"),
                "rId3" to (WORKSHEET to "worksheets/sheet3.xml"),
                "rId4" to (STYLES to "styles.xml"),
                "rId5" to (SHARED to "sharedStrings.xml")
            ),
            "xl/styles.xml" to STYLES_XML,
            "xl/sharedStrings.xml" to """<sst $NS><si><t>Name</t></si>
                <si><r><rPr><b/></rPr><t>Total </t></r><r><t>due</t></r><rPh><t>ignored</t></rPh></si>
                <si><t>tab_x0009_here</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to sheet1,
            "xl/worksheets/_rels/sheet1.xml.rels" to rels("rId1" to (DRAWING to "../drawings/drawing1.xml")),
            "xl/drawings/drawing1.xml" to DRAWING_XML,
            "xl/worksheets/sheet2.xml" to
                "<worksheet $NS><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet3.xml" to wideSheet()
        )
    )

    private fun wideSheet(): String = buildString {
        append("<worksheet $NS><sheetData><row r=\"1\">")
        for (c in 1..WIDE_COLUMNS) append("<c t=\"inlineStr\"><is><t>Col $c</t></is></c>")
        append("</row></sheetData></worksheet>")
    }

    private fun rels(vararg items: Pair<String, Pair<String, String>>): String = buildString {
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        items.forEach { (id, rel) ->
            append("<Relationship Id=\"$id\" Type=\"${rel.first}\" Target=\"${rel.second}\"/>")
        }
        append("</Relationships>")
    }

    /** StAX behind the app's cursor contract: local names, element depth at start and end. */
    private class StaxCursor(stream: InputStream) : XmlCursor {
        private val reader: XMLStreamReader = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }.createXMLStreamReader(stream)
        private var level = 0
        override var depth = 0
            private set

        override fun next(): Int = try {
            if (!reader.hasNext()) {
                XmlCursor.END_DOCUMENT
            } else {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> { level++; depth = level; XmlCursor.START }
                    XMLStreamConstants.END_ELEMENT -> { depth = level; level--; XmlCursor.END }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        XmlCursor.TEXT
                    XMLStreamConstants.END_DOCUMENT -> XmlCursor.END_DOCUMENT
                    else -> XmlCursor.OTHER
                }
            }
        } catch (e: XMLStreamException) {
            throw XlsxFormatException("malformed XML", e)
        }

        override val name: String get() = reader.localName
        override val text: String get() = reader.text
        override fun attr(localName: String): String? =
            (0 until reader.attributeCount).firstOrNull { reader.getAttributeLocalName(it) == localName }
                ?.let { reader.getAttributeValue(it) }
    }

    private companion object {
        const val WIDE_COLUMNS = 40
        const val NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        const val R_NS = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val OFFICE_DOC = "$REL/officeDocument"
        const val WORKSHEET = "$REL/worksheet"
        const val STYLES = "$REL/styles"
        const val SHARED = "$REL/sharedStrings"
        const val DRAWING = "$REL/drawing"

        // xf 0 default · 1 bold · 2 built-in 14 · 3 percent 0.00% · 4 custom date · 5 #,##0.00 · 6 fill+border
        val STYLES_XML = """<styleSheet $NS>
            <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/></numFmts>
            <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>
              <font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font></fonts>
            <fills count="3"><fill><patternFill patternType="none"/></fill>
              <fill><patternFill patternType="gray125"/></fill>
              <fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill></fills>
            <borders count="2"><border><left/><right/><top/><bottom/></border>
              <border><left style="thin"/><right style="thin"/><top style="thin"/>
                <bottom style="thin"><color indexed="64"/></bottom></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="1" fillId="2" borderId="1"/></cellStyleXfs>
            <cellXfs count="7">
              <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="0" fontId="1" fillId="0" borderId="0"/>
              <xf numFmtId="14" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="10" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="164" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="4" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="0" fontId="0" fillId="2" borderId="1"><alignment horizontal="center" wrapText="1"/></xf>
            </cellXfs>
            <dxfs count="1"><dxf><font><b/></font></dxf></dxfs>
            </styleSheet>"""

        val SHEET1 = """<worksheet $NS $R_NS>
            <sheetViews><sheetView workbookViewId="0"/></sheetViews>
            <sheetFormatPr defaultRowHeight="15"/>
            <cols><col min="1" max="1" width="30" customWidth="1"/><col min="2" max="2" width="12" hidden="1"/></cols>
            <sheetData>
              <row r="1" ht="40" customHeight="1"><c r="A1" s="1" t="s"><v>0</v></c>
                <c r="B1" s="1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" s="5"><v>1234.5</v></c><c r="B2" s="3"><v>0.256</v></c>
                <c r="C2" s="4"><v>45000</v></c><c r="D2" s="2"><v>45000</v></c></row>
              <row r="3"><c r="A3" t="b"><v>1</v></c><c r="B3" t="e"><v>#DIV/0!</v></c>
                <c r="C3"><f>6*7</f><v>42</v></c><c r="D3"><f>A2/0</f></c></row>
              <row r="4"><c r="A4" t="inlineStr"><is><t>Merged title</t></is></c><c r="B4"/><c r="C4"/></row>
              <row r="5"><c r="A5" t="s"><v>2</v></c></row>
              <row r="6"><c r="E6" s="6"/></row>
            </sheetData>
            <mergeCells count="1"><mergeCell ref="A4:C4"/></mergeCells>
            <conditionalFormatting sqref="A2:A3"><cfRule type="cellIs" dxfId="0" priority="1" operator="greaterThan">
              <formula>0</formula></cfRule></conditionalFormatting>
            <pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
            <drawing r:id="rId1"/>
            </worksheet>"""

        val DRAWING_XML = """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
              xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
              xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" $R_NS>
            <xdr:twoCellAnchor><xdr:graphicFrame><a:graphic><a:graphicData>
              <c:chart r:id="rId1"/></a:graphicData></a:graphic></xdr:graphicFrame></xdr:twoCellAnchor>
            <xdr:oneCellAnchor><xdr:pic><xdr:blipFill><a:blip r:embed="rId2"/></xdr:blipFill></xdr:pic>
              </xdr:oneCellAnchor>
            </xdr:wsDr>"""
    }
}
