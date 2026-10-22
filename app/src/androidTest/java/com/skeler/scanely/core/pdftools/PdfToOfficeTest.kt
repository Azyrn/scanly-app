package com.skeler.scanely.core.pdftools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdftools.pdf2office.OfficePageSource
import com.skeler.scanely.core.pdftools.pdf2office.PdfToExcelJob
import com.skeler.scanely.core.pdftools.pdf2office.PdfToPowerPointJob
import com.skeler.scanely.core.pdftools.pdf2office.PdfToWordJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * PDF → Word, PowerPoint and Excel against the real pdfium build, on fixtures written here: a
 * text page, a grid of numbers drawn at aligned x positions, and a page that is only a picture.
 * The checks open the output as a zip and look for the parts and the words Office needs.
 */
@RunWith(AndroidJUnit4::class)
class PdfToOfficeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "pdf-to-office-test").apply { mkdirs() }

    private val reader = PdfPageReader(context)
    private val workspace = ToolWorkspace(context)
    private val engine = PdfToolEngine(QpdfEngine(), reader, workspace)
    private val source = OfficePageSource(reader)
    private val word = PdfToWordJob(source, workspace)
    private val powerPoint = PdfToPowerPointJob(source, workspace)
    private val excel = PdfToExcelJob(context, source, workspace)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- Word ---

    @Test
    fun textPdfBecomesHeadingsAndParagraphs() = runBlocking {
        val document = open(pdf("report.pdf", listOf(textPage())))
        val progress = mutableListOf<Float?>()

        val result = word.convert(input(document) { progress += it })

        val output = result.outputs.single()
        assertEquals("report.docx", output.fileName)
        assertEquals(MIME_DOCX, output.mimeType)
        val parts = parts(output.file)
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val xml = parts.getValue("word/document.xml")
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(xml.contains("Quarterly Report"))
        assertTrue(xml.contains("quick brown fox"))
        assertTrue(xml.contains("second paragraph"))
        assertTrue(ToolNote(R.string.pdf2office_simplified_word) in result.notes)
        assertEquals(1f, progress.last())
    }

    @Test
    fun numberGridBecomesAWordTable() = runBlocking {
        val document = open(pdf("grid.pdf", listOf(gridPage())))

        val xml = parts(word.convert(input(document)).outputs.single().file).getValue("word/document.xml")

        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains(">Region</w:t>"))
        assertTrue(xml.contains(">4521</w:t>"))
    }

    @Test
    fun scannedPageIsAddedAsAnImage() = runBlocking {
        val document = open(pdf("scan.pdf", listOf(textPage(), IMAGE_PAGE)))

        val result = word.convert(input(document))

        val parts = parts(result.outputs.single().file)
        assertTrue(parts.containsKey("word/media/image1.jpg"))
        assertTrue(parts.getValue("word/_rels/document.xml.rels").contains("media/image1.jpg"))
        assertTrue(ToolNote(R.plurals.pdf2office_scanned_as_images, 1, plural = true) in result.notes)
    }

    // --- PowerPoint ---

    @Test
    fun everyPageIsASlideWithEditableText() = runBlocking {
        val pages = List(PAGES) { if (it == 3) IMAGE_PAGE else textPage() }
        val document = open(pdf("deck.pdf", pages))

        val result = powerPoint.convert(input(document))

        val output = result.outputs.single()
        assertEquals("deck.pptx", output.fileName)
        assertEquals(MIME_PPTX, output.mimeType)
        val parts = parts(output.file)
        assertTrue(parts.keys.containsAll(listOf("ppt/presentation.xml", "ppt/slideMasters/slideMaster1.xml")))
        assertEquals(PAGES, Regex("<p:sldId ").findAll(parts.getValue("ppt/presentation.xml")).count())
        val first = parts.getValue("ppt/slides/slide1.xml")
        assertTrue(first.contains("txBox=\"1\""))
        assertTrue(first.contains("Quarterly Report"))
        assertFalse("text slide got a page picture", first.contains("<p:pic>"))
        assertTrue(parts.getValue("ppt/slides/slide4.xml").contains("<p:pic>"))
        assertTrue(ToolNote(R.string.pdf2office_simplified_powerpoint) in result.notes)
    }

    // --- Excel ---

    @Test
    fun gridBecomesNumericCellsOnAPageSheet() = runBlocking {
        val document = open(pdf("grid.pdf", listOf(textPage(), gridPage())))

        val result = excel.convert(input(document))

        val output = result.outputs.single()
        assertEquals("grid.xlsx", output.fileName)
        assertEquals(MIME_XLSX, output.mimeType)
        val parts = parts(output.file)
        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"Page 2\""))
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<v>4521</v>"))
        assertTrue(sheet.contains(">Region</t>"))
        assertTrue(result.notes.none { it.res == R.string.pdf2office_excel_no_tables })
    }

    @Test
    fun noTablesPutsTextOneLinePerRow() = runBlocking {
        val document = open(pdf("prose.pdf", listOf(textPage(), IMAGE_PAGE)))

        val result = excel.convert(input(document))

        val sheet = parts(result.outputs.single().file).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("Quarterly Report"))
        assertTrue(ToolNote(R.string.pdf2office_excel_no_tables) in result.notes)
        assertTrue(ToolNote(R.plurals.pdf2office_excel_scanned_skipped, 1, plural = true) in result.notes)
    }

    // --- failures ---

    @Test
    fun noFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val document = open(pdf("report.pdf", listOf(textPage())))
        val run = full.newRun()

        val failure = failureOf {
            PdfToWordJob(source, full).convert(ToolJobInput(listOf(document), emptyList(), run) {})
        }

        assertEquals(ToolFailure.LowStorage, failure)
        assertRunEmpty(run)
    }

    @Test
    fun fileDamagedAfterOpeningLeavesNothingBehind(): Unit = runBlocking {
        val document = open(pdf("report.pdf", listOf(textPage(), textPage())))
        document.file.writeBytes("not a pdf any more".toByteArray())
        val run = workspace.newRun()

        // Every page is unreadable: the job must still finish or fail cleanly, never hang.
        val outcome = runCatching { word.convert(ToolJobInput(listOf(document), emptyList(), run) {}) }

        outcome.onSuccess { result ->
            assertTrue(ToolNote(R.plurals.pdf2office_unreadable_pages, 2, plural = true) in result.notes)
        }
        outcome.onFailure { error ->
            assertTrue("unexpected $error", error is ToolException)
            assertRunEmpty(run)
        }
    }

    @Test
    fun cancelledConversionLeavesNothing() = runBlocking {
        val document = open(pdf("long.pdf", List(MANY_PAGES) { textPage() }))
        val run = workspace.newRun()
        val started = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            powerPoint.convert(ToolJobInput(listOf(document), emptyList(), run) { started.set(true) })
        }
        // Cancel once the page loop is running, which is where the partial .pptx exists.
        while (!started.get()) delay(1)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertRunEmpty(run)
    }

    // --- helpers ---

    private fun input(document: ToolDocument, onProgress: (Float?) -> Unit = {}) =
        ToolJobInput(listOf(document), emptyList(), workspace.newRun(), onProgress)

    private suspend fun open(file: File): ToolDocument {
        val result = engine.probe(ImportedFile(file, file.name, file.length()))
        assertTrue("expected Opened, got $result", result is ProbeResult.Opened)
        return (result as ProbeResult.Opened).document
    }

    private suspend fun failureOf(block: suspend () -> Unit): ToolFailure? = try {
        block()
        null
    } catch (e: ToolException) {
        e.failure
    }

    private fun assertRunEmpty(run: File) {
        val left = run.listFiles().orEmpty().map { it.name }
        assertTrue("run dir not empty: $left", left.isEmpty())
    }

    private fun parts(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }.decodeToString()
        }
    }

    /** A title and two paragraphs of 11 pt body text, every line its own text object. */
    private fun textPage(): String = buildString {
        append(text("Quarterly Report", 72, 760, 24))
        val first = listOf(
            "The quick brown fox jumps over the lazy dog while the",
            "committee reviews the figures for the third quarter and",
            "agrees on the plan for next year."
        )
        val second = listOf(
            "This second paragraph starts after a clear gap so the",
            "layout analysis has to split it from the first one."
        )
        first.forEachIndexed { index, line -> append(text(line, 72, 720 - index * 14, 11)) }
        second.forEachIndexed { index, line -> append(text(line, 72, 660 - index * 14, 11)) }
    }

    /** Four aligned columns of short values, the shape of a printed table. */
    private fun gridPage(): String = buildString {
        val rows = listOf(
            listOf("Region", "Q1", "Q2", "Q3"),
            listOf("North", "4521", "3980", "4102"),
            listOf("South", "2210", "2475", "2630"),
            listOf("East", "1875", "1902", "2044"),
            listOf("West", "3320", "3101", "3457")
        )
        rows.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, cell -> append(text(cell, 72 + column * 120, 700 - row * 18, 11)) }
        }
    }

    private fun text(value: String, x: Int, y: Int, size: Int): String {
        val escaped = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return "BT /F1 $size Tf $x $y Td ($escaped) Tj ET\n"
    }

    /**
     * A minimal valid PDF with one page per entry of [pages] (each a content stream), sharing a
     * standard Helvetica font and a small grey image, with a real cross-reference table.
     */
    private fun pdf(name: String, pages: List<String>): File = File(dir, name).apply {
        val out = StringBuilder()
        val offsets = mutableListOf<Int>()
        fun obj(body: String) {
            offsets += out.length
            out.append("${offsets.size} 0 obj\n").append(body).append("\nendobj\n")
        }
        out.append("%PDF-1.4\n")
        val kids = pages.indices.joinToString(" ") { "${5 + it * 2} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val pixels = "8".repeat(IMAGE_SIDE * IMAGE_SIDE)
        obj(
            "<< /Type /XObject /Subtype /Image /Width $IMAGE_SIDE /Height $IMAGE_SIDE /ColorSpace /DeviceGray " +
                "/BitsPerComponent 8 /Length ${pixels.length} >>\nstream\n$pixels\nendstream"
        )
        pages.forEachIndexed { index, content ->
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> " +
                    "/XObject << /Im0 4 0 R >> >> /Contents ${6 + index * 2} 0 R >>"
            )
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = out.length
        out.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        writeBytes(out.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private companion object {
        const val PAGES = 5
        const val MANY_PAGES = 200
        const val IMAGE_SIDE = 64

        /** Only a picture: no text layer, which is what a scanned page looks like. */
        const val IMAGE_PAGE = "q 451 0 0 698 72 72 cm /Im0 Do Q\n"
    }
}
