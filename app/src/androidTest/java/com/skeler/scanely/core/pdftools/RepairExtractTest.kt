package com.skeler.scanely.core.pdftools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.repair.RepairJob
import com.skeler.scanely.core.pdftools.text.ExtractTextJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Repair and Extract text against the real qpdf, pdfium and PDFBox builds. Fixtures are written
 * here byte by byte, so the damage is known exactly: a repair test that passed on an intact file
 * would prove nothing.
 */
@RunWith(AndroidJUnit4::class)
class RepairExtractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "repair-extract-test").apply { mkdirs() }

    private val pageReader = PdfPageReader(context)
    private val qpdf = QpdfEngine()
    private val workspace = ToolWorkspace(context)
    private val engine = PdfToolEngine(qpdf, pageReader, workspace)
    private val repair = RepairJob(context, pageReader, workspace)
    private val extract = ExtractTextJob(context, pageReader, workspace)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- repair ---

    @Test
    fun missingXrefIsRebuiltWithEveryPage() = runBlocking {
        val broken = fixture("broken.pdf", texts = listOf("one", "two", "three"), damage = Damage.NO_XREF)
        val run = workspace.newRun()

        val result = repair.repair(rawInput(broken, run))

        val output = result.outputs.single()
        assertEquals("broken_repaired.pdf", output.fileName)
        assertEquals(MIME_PDF, output.mimeType)
        assertEquals(3, pageReader.pageCount(output.file))
        assertEquals(ToolNote(R.plurals.pdf_tool_repair_recovered, 3, plural = true), result.notes.single())
        // The repaired copy must pass the same gate every other tool puts files through.
        assertTrue(engine.probe(ImportedFile(output.file, output.fileName, output.sizeBytes)) is ProbeResult.Opened)
    }

    @Test
    fun wrongXrefOffsetsAreRepairedAcrossManyPages() = runBlocking {
        val texts = (1..MANY_PAGES).map { "page$it" }
        val broken = fixture("shifted.pdf", texts = texts, damage = Damage.WRONG_OFFSETS)
        val run = workspace.newRun()

        val output = repair.repair(rawInput(broken, run)).outputs.single()

        assertEquals(MANY_PAGES, pageReader.pageCount(output.file))
        assertEquals("page$MANY_PAGES", pageReader.plainText(output.file, MANY_PAGES - 1)?.trim())
    }

    @Test
    fun anOpenedDocumentIsAcceptedToo() = runBlocking {
        val intact = fixture("intact.pdf", texts = listOf("a", "b"))
        val document = (engine.probe(ImportedFile(intact, intact.name, intact.length())) as ProbeResult.Opened).document
        val run = workspace.newRun()

        val output = repair.repair(ToolJobInput(listOf(document), emptyList(), run) {}).outputs.single()

        assertEquals(2, pageReader.pageCount(output.file))
    }

    @Test
    fun randomBytesAreDamagedAndLeaveNothing() = runBlocking {
        val file = File(dir, "random.pdf").apply { writeBytes(Random(11).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { repair.repair(rawInput(file, run)) })
        assertRunEmpty(run)
    }

    @Test
    fun encryptedFileWithoutPasswordIsWrongPassword() = runBlocking {
        val locked = File(dir, "locked.pdf")
        val encrypted = qpdf.encrypt(fixture("source.pdf", texts = listOf("x")), locked, SECRET, SECRET)
        assertEquals("fixture encryption failed", QpdfResult.Ok, encrypted)
        val run = workspace.newRun()

        assertEquals(ToolFailure.WrongPassword, failureOf { repair.repair(rawInput(locked, run)) })
        assertRunEmpty(run)
    }

    @Test
    fun repairWithNoFreeSpaceIsLowStorage() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val run = full.newRun()
        val broken = fixture("broken.pdf", texts = listOf("one"), damage = Damage.NO_XREF)

        val failure = failureOf { RepairJob(context, pageReader, full).repair(rawInput(broken, run)) }

        assertEquals(ToolFailure.LowStorage, failure)
        assertRunEmpty(run)
    }

    // --- extract text ---

    @Test
    fun textComesOutInPageOrderWithMarkers() = runBlocking {
        val document = open(fixture("text.pdf", texts = listOf("alpha", "bravo", "charlie")))
        val run = workspace.newRun()

        val result = extract.extract(document, pageSeparators = true, run = run) {}

        val output = result.outputs.single()
        assertEquals("text.txt", output.fileName)
        assertEquals(MIME_TEXT, output.mimeType)
        assertEquals(output.file.length(), output.sizeBytes)
        val text = output.file.readText(Charsets.UTF_8)
        val markers = (1..3).map { context.getString(R.string.pdf_tool_extract_text_page_separator, it) }
        val order = listOf(markers[0], "alpha", markers[1], "bravo", markers[2], "charlie").map { text.indexOf(it) }
        assertFalse("missing piece in output: $order", order.contains(-1))
        assertEquals(order.sorted(), order)
        assertFalse("CR left in output", text.contains('\r'))
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun withoutMarkersOnlyTheTextRemains() = runBlocking {
        val document = open(fixture("text.pdf", texts = listOf("alpha", "bravo")))

        val output = extract.extract(document, pageSeparators = false, run = workspace.newRun()) {}.outputs.single()

        assertEquals("alpha\n\nbravo\n\n", output.file.readText(Charsets.UTF_8))
    }

    @Test
    fun pagesWithoutTextAreCounted() = runBlocking {
        val document = open(fixture("mixed.pdf", texts = listOf("alpha", null, "charlie", null)))

        val result = extract.extract(document, pageSeparators = true, run = workspace.newRun()) {}

        assertEquals(ToolNote(R.plurals.pdf_tool_extract_text_empty_pages, 2, plural = true), result.notes.single())
        val text = result.outputs.single().file.readText(Charsets.UTF_8)
        // Every page keeps its marker, so the numbering never skips past an empty page.
        assertTrue(text.contains(context.getString(R.string.pdf_tool_extract_text_page_separator, 4)))
    }

    @Test
    fun manyPagesReportProgressToTheEnd() = runBlocking {
        val texts = (1..MANY_PAGES).map { "word$it" }
        val document = open(fixture("many.pdf", texts = texts))
        val progress = mutableListOf<Float?>()

        val output = extract.extract(document, pageSeparators = false, run = workspace.newRun()) {
            progress += it
        }.outputs.single()

        val lines = output.file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(texts, lines)
        assertEquals(MANY_PAGES, progress.size)
        assertEquals(1f, progress.last())
    }

    @Test
    fun extractWithNoFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val document = open(fixture("text.pdf", texts = listOf("alpha")))
        val run = full.newRun()

        val failure = failureOf { ExtractTextJob(context, pageReader, full).extract(document, true, run) {} }

        assertEquals(ToolFailure.LowStorage, failure)
        assertRunEmpty(run)
    }

    // --- helpers ---

    private fun rawInput(file: File, run: File) =
        ToolJobInput(emptyList(), listOf(ImportedFile(file, file.name, file.length())), run) {}

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

    private enum class Damage {
        NONE,

        /** The file ends after the last object: no xref table, no trailer, no startxref. */
        NO_XREF,

        /** A complete xref whose every offset is off by a few bytes, as after a botched edit. */
        WRONG_OFFSETS
    }

    /**
     * One page per entry of [texts]; a null entry is a page with only a drawn line, which is what
     * a scan looks like to a text extractor. Helvetica is a standard font, so no font file is
     * embedded and pdfium still maps every character back to Unicode.
     */
    private fun fixture(name: String, texts: List<String?>, damage: Damage = Damage.NONE): File =
        File(dir, name).apply {
            val bytes = StringBuilder()
            val offsets = mutableListOf<Int>()
            fun startObject(number: Int) {
                check(offsets.size == number - 1) { "objects must be written in order" }
                offsets += bytes.length
                bytes.append("$number 0 obj\n")
            }

            bytes.append("%PDF-1.4\n")
            startObject(1)
            bytes.append("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            val kids = texts.indices.joinToString(" ") { "${4 + it * 2} 0 R" }
            startObject(2)
            bytes.append("<< /Type /Pages /Kids [$kids] /Count ${texts.size} >>\nendobj\n")
            startObject(3)
            bytes.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

            texts.forEachIndexed { index, text ->
                val pageNumber = 4 + index * 2
                startObject(pageNumber)
                bytes.append(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                        "/Resources << /Font << /F1 3 0 R >> >> /Contents ${pageNumber + 1} 0 R >>\nendobj\n"
                )
                val drawing = if (text == null) {
                    "0 0 0 RG 50 700 m 545 700 l S\n"
                } else {
                    "BT /F1 24 Tf 72 700 Td ($text) Tj ET\n"
                }
                startObject(pageNumber + 1)
                bytes.append("<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")
            }

            if (damage != Damage.NO_XREF) {
                val shift = if (damage == Damage.WRONG_OFFSETS) OFFSET_SHIFT else 0
                val xrefOffset = bytes.length
                val size = offsets.size + 1
                bytes.append("xref\n0 $size\n0000000000 65535 f \n")
                offsets.forEach { bytes.append("%010d 00000 n \n".format(it + shift)) }
                bytes.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
            }
            writeText(bytes.toString(), Charsets.ISO_8859_1)
        }

    private companion object {
        const val SECRET = "secret"
        const val MANY_PAGES = 60
        const val OFFSET_SHIFT = 7
    }
}
