package com.skeler.scanely.core.pdftools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxToPdfJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Excel to PDF end to end: workbooks built here byte by byte, converted with the real job, and the
 * PDF read back with pdfium — page count, the text on each page, and the notes the user sees.
 */
@RunWith(AndroidJUnit4::class)
class ExcelToPdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "excel-to-pdf-test").apply { mkdirs() }
    private val workspace = ToolWorkspace(context)
    private val job = XlsxToPdfJob(workspace)
    private val reader = PdfPageReader(context)
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        Locale.setDefault(savedLocale)
    }

    @Test
    fun workbookPrintsVisibleSheetsWithFormattedValues() = runBlocking {
        val result = job.convert(imported(workbook()), workspace.newRun()) {}

        val output = result.outputs.single()
        assertEquals("book.pdf", output.fileName)
        assertEquals(MIME_PDF, output.mimeType)
        val pages = reader.pageCount(output.file)!!
        val texts = (0 until pages).map { reader.plainText(output.file, it).orEmpty() }

        val data = texts.first()
        listOf(
            "Data", "Name", "Total due", "1,234.50", "25.60%", "2023-03-15", "3/15/2023", "TRUE", "42", "Merged title"
        )
            .forEach { assertTrue("'$it' missing from page 1", data.contains(it)) }
        assertFalse("hidden sheet printed", texts.any { it.contains("Secret value") })
    }

    @Test
    fun wideSheetContinuesOnFollowingPages() = runBlocking {
        val output = job.convert(imported(workbook()), workspace.newRun()) {}.outputs.single()

        val pages = reader.pageCount(output.file)!!
        // pdfium guesses reading direction per page; a landscape page of short cells in narrow
        // columns reads as vertical flow and comes back as "C\nol 0\n1". The PDF's text is right,
        // so the page order is checked with whitespace removed.
        val texts = (0 until pages).map { reader.plainText(output.file, it).orEmpty().filterNot(Char::isWhitespace) }
        val wide = texts.drop(1)
        assertTrue("wide sheet fit one page: $pages pages", wide.size >= 2)
        assertTrue("first continuation page: ${wide.first().take(300)}", wide.first().contains("Col01"))
        assertFalse(wide.first().contains("Col$WIDE_COLUMNS"))
        assertTrue(wide.last().contains("Col$WIDE_COLUMNS"))
        // Every page of a sheet is headed with its name.
        assertTrue(wide.all { it.contains("Wide") })
    }

    @Test
    fun notesSayWhatWasLeftOut() = runBlocking {
        val notes = job.convert(imported(workbook()), workspace.newRun()) {}.notes

        assertTrue(ToolNote(R.plurals.excel2pdf_note_charts, 1, plural = true) in notes)
        assertTrue(ToolNote(R.plurals.excel2pdf_note_hidden_sheets, 1, plural = true) in notes)
        assertTrue(ToolNote(R.string.excel2pdf_note_formulas_cached) in notes)
        assertTrue(ToolNote(R.plurals.excel2pdf_note_formulas_missing, 1, plural = true) in notes)
    }

    @Test
    fun longSheetRunsDownManyPages() = runBlocking {
        val rows = 3_000
        val sheet = buildString {
            append("<worksheet $NS><sheetData>")
            for (r in 1..rows) append("<row r=\"$r\"><c r=\"A$r\" t=\"inlineStr\"><is><t>Row $r</t></is></c></row>")
            append("</sheetData></worksheet>")
        }
        var lastProgress = 0f
        val output = job.convert(imported(workbook(sheet1 = sheet)), workspace.newRun()) { p ->
            if (p != null) lastProgress = p
        }.outputs.single()

        val pages = reader.pageCount(output.file)!!
        // 3,000 rows of 15 pt on a 734 pt printable height: about 61 pages, plus the wide sheet's.
        assertTrue("only $pages pages", pages > 60)
        val longPages = (0 until pages).map { reader.plainText(output.file, it).orEmpty() }.filter { "Row " in it }
        assertTrue(longPages.last().contains("Row $rows"))
        assertEquals(1f, lastProgress, 0.001f)
    }

    @Test
    fun emptySheetStillGetsAPage() = runBlocking {
        val output = job.convert(
            imported(workbook(sheet1 = "<worksheet $NS><sheetData/></worksheet>")),
            workspace.newRun()
        ) {}.outputs.single()

        assertTrue(reader.plainText(output.file, 0).orEmpty().contains("Data"))
    }

    @Test
    fun randomBytesAreDamagedAndLeaveNothing() = runBlocking {
        val file = File(dir, "random.xlsx").apply { writeBytes(Random(7).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { job.convert(imported(file), run) {} })
        assertRunEmpty(run)
    }

    @Test
    fun brokenSheetXmlIsDamagedAndLeavesNothing() = runBlocking {
        val run = workspace.newRun()

        val failure = failureOf { job.convert(imported(workbook(sheet1 = "<worksheet $NS><sheetData><row>")), run) {} }

        assertEquals(ToolFailure.Damaged, failure)
        assertRunEmpty(run)
    }

    @Test
    fun noFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val run = full.newRun()

        assertEquals(ToolFailure.LowStorage, failureOf { XlsxToPdfJob(full).convert(imported(workbook()), run) {} })
        assertRunEmpty(run)
    }

    // --- helpers ---

    private fun imported(file: File) = ImportedFile(file, file.name, file.length())

    private suspend fun failureOf(block: suspend () -> Unit): ToolFailure? = try {
        block()
        null
    } catch (e: ToolException) {
        e.failure
    }

    private fun assertRunEmpty(run: File) {
        assertTrue("run left files: ${run.list()?.toList()}", run.list().isNullOrEmpty())
    }

    private fun workbook(sheet1: String = SHEET1): File = File(dir, "book.xlsx").apply {
        val parts = mapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "_rels/.rels" to rels("rId1" to (OFFICE_DOC to "xl/workbook.xml")),
            "xl/workbook.xml" to """<workbook $NS $R_NS><sheets>
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
                <si><r><rPr><b/></rPr><t>Total </t></r><r><t>due</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to sheet1,
            "xl/worksheets/_rels/sheet1.xml.rels" to rels("rId1" to (DRAWING to "../drawings/drawing1.xml")),
            "xl/drawings/drawing1.xml" to DRAWING_XML,
            "xl/worksheets/sheet2.xml" to """<worksheet $NS><sheetData><row r="1">
                <c r="A1" t="inlineStr"><is><t>Secret value</t></is></c></row></sheetData></worksheet>""",
            "xl/worksheets/sheet3.xml" to wideSheet()
        )
        ZipOutputStream(outputStream()).use { out ->
            parts.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    /** Forty columns 20 characters wide: about 4,000 pt, several landscape pages across. */
    private fun wideSheet(): String = buildString {
        append("<worksheet $NS><cols><col min=\"1\" max=\"$WIDE_COLUMNS\" width=\"20\" customWidth=\"1\"/></cols>")
        append("<sheetData><row r=\"1\">")
        // Zero-padded so "Col 01" can't match inside "Col 10" in the page text.
        for (c in 1..WIDE_COLUMNS) append("<c t=\"inlineStr\"><is><t>Col ${c.toString().padStart(2, '0')}</t></is></c>")
        append("</row></sheetData></worksheet>")
    }

    private fun rels(vararg items: Pair<String, Pair<String, String>>): String = buildString {
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        items.forEach { (id, rel) ->
            append("<Relationship Id=\"$id\" Type=\"${rel.first}\" Target=\"${rel.second}\"/>")
        }
        append("</Relationships>")
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
                <bottom style="thin"/></border></borders>
            <cellXfs count="7">
              <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="0" fontId="1" fillId="0" borderId="0"/>
              <xf numFmtId="14" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="10" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="164" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="4" fontId="0" fillId="0" borderId="0"/>
              <xf numFmtId="0" fontId="0" fillId="2" borderId="1"><alignment horizontal="center" wrapText="1"/></xf>
            </cellXfs></styleSheet>"""

        val SHEET1 = """<worksheet $NS $R_NS>
            <cols><col min="1" max="4" width="14" customWidth="1"/></cols>
            <sheetData>
              <row r="1"><c r="A1" s="1" t="s"><v>0</v></c><c r="B1" s="1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" s="5"><v>1234.5</v></c><c r="B2" s="3"><v>0.256</v></c>
                <c r="C2" s="4"><v>45000</v></c><c r="D2" s="2"><v>45000</v></c></row>
              <row r="3"><c r="A3" t="b"><v>1</v></c><c r="C3"><f>6*7</f><v>42</v></c><c r="D3"><f>A2/0</f></c></row>
              <row r="4"><c r="A4" s="6" t="inlineStr"><is><t>Merged title</t></is></c>
                <c r="B4" s="6"/><c r="C4" s="6"/></row>
            </sheetData>
            <mergeCells count="1"><mergeCell ref="A4:C4"/></mergeCells>
            <drawing r:id="rId1"/>
            </worksheet>"""

        val DRAWING_XML = """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
              xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
              xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" $R_NS>
            <xdr:twoCellAnchor><xdr:graphicFrame><a:graphic><a:graphicData>
              <c:chart r:id="rId1"/></a:graphicData></a:graphic></xdr:graphicFrame></xdr:twoCellAnchor>
            </xdr:wsDr>"""
    }
}
