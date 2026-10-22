package com.skeler.scanely.core.pdftools

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxToPdfJob
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Word to PDF against the real platform parser, PdfDocument and pdfium, on .docx files written
 * here so every byte of input is known. Checks what a user would: the pages exist, the words are
 * on them, pictures made it in, and what was left out is said — and that failures and cancels
 * leave nothing the result screen could offer.
 */
@RunWith(AndroidJUnit4::class)
class WordToPdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "word-to-pdf-test").apply { mkdirs() }

    private val workspace = ToolWorkspace(context)
    private val job = DocxToPdfJob(workspace)
    private val reader = PdfPageReader(context)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun reportConvertsWithTextTableListPictureAndPageBreak() = runBlocking {
        val run = workspace.newRun()
        val result = job.convert(input(docx("report.docx", REPORT_BODY, withImage = true), run))

        val output = result.outputs.single()
        assertEquals("report.pdf", output.fileName)
        assertEquals(MIME_PDF, output.mimeType)
        assertEquals(2, reader.pageCount(output.file))
        val first = reader.plainText(output.file, 0).orEmpty()
        for (expected in listOf("Quarterly report", "Bold words", "First bullet", "Second bullet", "B2", "C3")) {
            assertTrue("page 1 is missing \"$expected\": $first", first.contains(expected))
        }
        assertTrue(reader.plainText(output.file, 1).orEmpty().contains("After the break"))
        assertTrue(
            "the chart note is missing: ${result.notes}",
            ToolNote(R.plurals.pdf_tool_word_to_pdf_note_charts, 1, plural = true) in result.notes
        )

        PDFBoxResourceLoader.init(context)
        PDDocument.load(output.file).use { pdf ->
            assertTrue("no picture on page 1", imagesIn(pdf.getPage(0).resources) > 0)
        }
    }

    @Test
    fun hundredsOfPagesComeOutInOrder() = runBlocking {
        val pages = 300
        val body = (1..pages).joinToString("") { page ->
            paragraph("Page marker $page") + paragraph("Filler text for page $page.") +
                if (page < pages) PAGE_BREAK else ""
        }
        val run = workspace.newRun()
        val result = job.convert(input(docx("long.docx", body), run))

        val file = result.outputs.single().file
        assertEquals(pages, reader.pageCount(file))
        assertTrue(reader.plainText(file, pages - 1).orEmpty().contains("Page marker $pages"))
    }

    @Test
    fun oneHugeParagraphFlowsAcrossPages() = runBlocking {
        val words = (1..20_000).joinToString(" ") { "word$it" }
        val run = workspace.newRun()
        val result = job.convert(input(docx("wall.docx", paragraph(words)), run))

        val file = result.outputs.single().file
        val count = reader.pageCount(file) ?: 0
        assertTrue("expected many pages, got $count", count > 10)
        assertTrue(reader.plainText(file, count - 1).orEmpty().contains("word20000"))
    }

    @Test
    fun aRowTallerThanThePageIsCutAndSaid() = runBlocking {
        val cell = (1..200).joinToString("") { paragraph("Line $it") }
        val body = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>$cell</w:tc></w:tr></w:tbl>"
        val run = workspace.newRun()
        val result = job.convert(input(docx("tall.docx", body), run))

        assertTrue(ToolNote(R.plurals.pdf_tool_word_to_pdf_note_rows, 1, plural = true) in result.notes)
    }

    @Test
    fun randomBytesAreDamagedAndLeaveNothing() = runBlocking {
        val file = File(dir, "random.docx").apply { writeBytes(Random(7).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { job.convert(input(file, run)) })
        assertRunEmpty(run)
    }

    @Test
    fun malformedDocumentXmlIsDamagedAndLeavesNothing() = runBlocking {
        val run = workspace.newRun()
        val file = docx("broken.docx", "<w:p><w:r><w:t>never closed")

        assertEquals(ToolFailure.Damaged, failureOf { job.convert(input(file, run)) })
        assertRunEmpty(run)
    }

    @Test
    fun noFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val run = full.newRun()

        assertEquals(
            ToolFailure.LowStorage,
            failureOf { DocxToPdfJob(full).convert(input(docx("report.docx", REPORT_BODY), run)) }
        )
        assertRunEmpty(run)
    }

    @Test
    fun cancelledConversionLeavesNoOutput() = runBlocking {
        val body = (1..10_000).joinToString("") { paragraph("Paragraph $it with enough words to wrap a line or two.") }
        val file = docx("cancel.docx", body)
        val run = workspace.newRun()

        val conversion = launch(Dispatchers.Default) { job.convert(input(file, run)) }
        delay(CANCEL_AFTER_MS)
        conversion.cancel()
        conversion.join()

        assertTrue("conversion finished before it could be cancelled", conversion.isCancelled)
        assertRunEmpty(run)
    }

    // --- helpers ---

    private fun input(file: File, run: File) = ToolJobInput(
        documents = emptyList(),
        files = listOf(ImportedFile(file, file.name, file.length())),
        run = run,
        onProgress = {}
    )

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

    /** Images on a page, including ones Skia nests inside form XObjects. */
    private fun imagesIn(resources: PDResources?, depth: Int = 0): Int {
        if (resources == null || depth > 4) return 0
        return resources.xObjectNames.sumOf { name ->
            when (val xObject = resources.getXObject(name)) {
                is PDImageXObject -> 1
                is PDFormXObject -> imagesIn(xObject.resources, depth + 1)
                else -> 0
            }
        }
    }

    private fun paragraph(text: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    private fun docx(name: String, body: String, withImage: Boolean = false): File {
        val parts = linkedMapOf(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to ROOT_RELS,
            "word/document.xml" to "<w:document $NAMESPACES><w:body>$body</w:body></w:document>",
            "word/styles.xml" to STYLES,
            "word/numbering.xml" to NUMBERING,
            "word/_rels/document.xml.rels" to DOCUMENT_RELS
        ).mapValues { it.value.toByteArray() }.toMutableMap()
        if (withImage) parts["word/media/image1.png"] = png()
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            parts.forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 30)) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val CANCEL_AFTER_MS = 150L

        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"

        const val NAMESPACES = """xmlns:w="$W" xmlns:r="$OFFICE_RELS"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart""""

        const val PAGE_BREAK = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""

        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="xml" ContentType="application/xml"/>
            <Default Extension="png" ContentType="image/png"/>
            </Types>"""

        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="$PKG_RELS">
            <Relationship Id="rId1" Type="$OFFICE_RELS/officeDocument" Target="word/document.xml"/>
            </Relationships>"""

        const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="$PKG_RELS">
            <Relationship Id="rIdStyles" Type="$OFFICE_RELS/styles" Target="styles.xml"/>
            <Relationship Id="rIdNumbering" Type="$OFFICE_RELS/numbering" Target="numbering.xml"/>
            <Relationship Id="rIdImg" Type="$OFFICE_RELS/image" Target="media/image1.png"/>
            <Relationship Id="rIdChart" Type="$OFFICE_RELS/chart" Target="charts/chart1.xml"/>
            </Relationships>"""

        const val STYLES = """<w:styles xmlns:w="$W">
            <w:docDefaults>
              <w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/>
              </w:rPr></w:rPrDefault>
              <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>
            </w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"/>
            <w:style w:type="paragraph" w:styleId="Heading1">
              <w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr>
            </w:style>
            <w:style w:type="table" w:styleId="TableGrid">
              <w:tblPr><w:tblBorders><w:top w:val="single"/><w:bottom w:val="single"/></w:tblBorders></w:tblPr>
            </w:style>
            </w:styles>"""

        const val NUMBERING = """<w:numbering xmlns:w="$W">
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/>
                <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            </w:numbering>"""

        const val PICTURE = """<w:drawing><wp:inline><wp:extent cx="1828800" cy="914400"/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic><pic:blipFill><a:blip r:embed="rIdImg"/></pic:blipFill></pic:pic>
            </a:graphicData></a:graphic></wp:inline></w:drawing>"""

        const val CHART = """<w:drawing><wp:inline><wp:extent cx="1828800" cy="914400"/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">
            <c:chart r:id="rIdChart"/>
            </a:graphicData></a:graphic></wp:inline></w:drawing>"""

        const val REPORT_BODY = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Quarterly report</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold words</w:t></w:r>
              <w:r><w:t xml:space="preserve"> then plain.</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
              <w:r><w:t>First bullet</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
              <w:r><w:t>Second bullet</w:t></w:r></w:p>
            <w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
              <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>
              <w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>C1</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>C2</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>A3</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B3</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>C3</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r>$PICTURE</w:r></w:p>
            <w:p><w:r>$CHART</w:r></w:p>
            <w:p><w:r><w:br w:type="page"/><w:t>After the break</w:t></w:r></w:p>
        """
    }
}
