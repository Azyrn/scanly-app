package com.skeler.scanely.core.pdftools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.EncryptionState
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.organize.MergeJob
import com.skeler.scanely.core.pdftools.organize.PageRange
import com.skeler.scanely.core.pdftools.organize.SplitJob
import com.skeler.scanely.core.pdftools.organize.SplitMode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

/**
 * Merge and Split against the real qpdf build. Every fixture page has its own MediaBox width
 * (200 + its page number), so an output's widths say exactly which source pages it holds and in
 * what order — the property these tools exist for.
 */
@RunWith(AndroidJUnit4::class)
class MergeSplitTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "merge-split-test").apply { mkdirs() }

    private val qpdf = QpdfEngine()
    private val workspace = ToolWorkspace(context)
    private val merge = MergeJob(workspace)
    private val split = SplitJob(qpdf, workspace)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- merge ---

    @Test
    fun mergeKeepsTheArrangedOrderAndReadsEachPassword() = runBlocking {
        val plain = document(fixture("plain.pdf", pages = 3, widthBase = 300))
        val locked = encrypted(fixture("source.pdf", pages = 2, widthBase = 400), "locked.pdf")

        val output = merge.merge(listOf(locked, plain), workspace.newRun()) {}.outputs.single()

        assertEquals("locked_merged.pdf", output.fileName)
        assertEquals("application/pdf", output.mimeType)
        assertEquals(listOf(401, 402, 301, 302, 303), widths(output.file))
        // The merged copy starts from an empty document, so it carries no input's password.
        assertEquals(EncryptionState.PLAIN, qpdf.encryptionState(output.file))
    }

    @Test
    fun mergeOfManyFiles() = runBlocking {
        val documents = (0 until MANY_FILES).map { index ->
            document(fixture("part$index.pdf", pages = PAGES_PER_FILE))
        }

        val output = merge.merge(documents, workspace.newRun()) {}.outputs.single()

        assertEquals(MANY_FILES * PAGES_PER_FILE, widths(output.file).size)
    }

    @Test
    fun mergeWithAWrongPasswordFailsAndLeavesNothing() = runBlocking {
        val good = document(fixture("plain.pdf", pages = 1))
        val locked = encrypted(fixture("source.pdf", pages = 1), "locked.pdf").copy(password = "wrong")
        val run = workspace.newRun()

        assertEquals(ToolFailure.WrongPassword, failureOf { merge.merge(listOf(good, locked), run) {} })
        assertRunEmpty(run)
    }

    @Test
    fun mergeWithADamagedFileFailsAndLeavesNothing() = runBlocking {
        val good = document(fixture("plain.pdf", pages = 1))
        val junk = File(dir, "junk.pdf").apply { writeBytes(Random(3).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertNotNull(failureOf { merge.merge(listOf(good, document(junk, pages = 1)), run) {} })
        assertRunEmpty(run)
    }

    @Test
    fun mergeWithNoSpaceIsLowStorage() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val documents = listOf(document(fixture("a.pdf", pages = 1)), document(fixture("b.pdf", pages = 1)))
        val run = full.newRun()

        assertEquals(ToolFailure.LowStorage, failureOf { MergeJob(full).merge(documents, run) {} })
        assertRunEmpty(run)
    }

    // --- split: ranges, extract, delete ---

    @Test
    fun byRangesMakesOneFilePerRange() = runBlocking {
        val source = document(fixture("report.pdf", pages = 10))
        val ranges = listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 10))

        val outputs = split.split(source, SplitMode.ByRanges(ranges), workspace.newRun()) {}.outputs

        assertEquals(
            listOf("report_pages_1-3.pdf", "report_page_5.pdf", "report_pages_8-10.pdf"),
            outputs.map { it.fileName }
        )
        assertEquals(listOf(201, 202, 203), widths(outputs[0].file))
        assertEquals(listOf(205), widths(outputs[1].file))
        assertEquals(listOf(208, 209, 210), widths(outputs[2].file))
    }

    @Test
    fun theSameRangeTwiceGetsTwoFiles() = runBlocking {
        val source = document(fixture("report.pdf", pages = 4))
        val ranges = listOf(PageRange(1, 2), PageRange(1, 2))

        val outputs = split.split(source, SplitMode.ByRanges(ranges), workspace.newRun()) {}.outputs

        assertEquals(listOf("report_pages_1-2.pdf", "report_pages_1-2_2.pdf"), outputs.map { it.fileName })
        outputs.forEach { assertTrue(it.file.isFile) }
    }

    @Test
    fun extractKeepsTheTypedOrderInOneFile() = runBlocking {
        val source = document(fixture("report.pdf", pages = 10))
        val ranges = listOf(PageRange(8, 10), PageRange(1, 1))

        val output = split.split(source, SplitMode.Extract(ranges), workspace.newRun()) {}.outputs.single()

        assertEquals("report_extracted.pdf", output.fileName)
        assertEquals(listOf(208, 209, 210, 201), widths(output.file))
    }

    @Test
    fun deleteDropsTheNamedPages() = runBlocking {
        val source = document(fixture("report.pdf", pages = 6))
        val ranges = listOf(PageRange(2, 4), PageRange(3, 3))

        val output = split.split(source, SplitMode.Delete(ranges), workspace.newRun()) {}.outputs.single()

        assertEquals(listOf(201, 205, 206), widths(output.file))
    }

    @Test
    fun deletingEveryPageIsRefusedAndLeavesNothing() = runBlocking {
        val source = document(fixture("report.pdf", pages = 3))
        val run = workspace.newRun()

        val failure = failureOf { split.split(source, SplitMode.Delete(listOf(PageRange(1, 3))), run) {} }

        assertTrue("got $failure", failure is ToolFailure.Failed)
        assertRunEmpty(run)
    }

    @Test
    fun aLockedDocumentSplitsIntoLockedFiles() = runBlocking {
        val locked = encrypted(fixture("source.pdf", pages = 4), "locked.pdf")

        val mode = SplitMode.ByRanges(listOf(PageRange(1, 2), PageRange(4, 4)))

        val outputs = split.split(locked, mode, workspace.newRun()) {}.outputs

        outputs.forEach { output ->
            assertEquals(EncryptionState.NEEDS_PASSWORD, qpdf.encryptionState(output.file))
            assertEquals(EncryptionState.OPENED, qpdf.encryptionState(output.file, SECRET))
        }
        assertEquals(listOf(204), widths(outputs[1].file, SECRET))
    }

    // --- split: every N ---

    @Test
    fun everyNPagesLeavesTheRemainderInTheLastPart() = runBlocking {
        val source = document(fixture("long.pdf", pages = 25))
        val progress = mutableListOf<Float?>()

        val outputs = split.split(source, SplitMode.EveryN(10), workspace.newRun()) { progress += it }.outputs

        assertEquals(listOf("long_part-01.pdf", "long_part-02.pdf", "long_part-03.pdf"), outputs.map { it.fileName })
        assertEquals((201..210).toList(), widths(outputs[0].file))
        assertEquals((211..220).toList(), widths(outputs[1].file))
        assertEquals((221..225).toList(), widths(outputs[2].file))
        assertEquals(1f, progress.last())
        // Only the outputs are left behind: qpdf's staging folder is gone.
        assertEquals(outputs.map { it.fileName }.toSet(), outputs.first().file.parentFile!!.list()!!.toSet())
    }

    @Test
    fun everyPageOfALongDocumentGetsItsOwnPartInNameOrder() = runBlocking {
        val source = document(fixture("long.pdf", pages = LONG_PAGES))

        val outputs = split.split(source, SplitMode.EveryN(1), workspace.newRun()) {}.outputs

        assertEquals(LONG_PAGES, outputs.size)
        assertEquals("long_part-001.pdf", outputs.first().fileName)
        assertEquals("long_part-$LONG_PAGES.pdf", outputs.last().fileName)
        assertEquals(listOf(200 + LONG_PAGES), widths(outputs.last().file))
        assertEquals(listOf(237), widths(outputs[36].file))
    }

    @Test
    fun everyNOnADamagedFileLeavesNothing() = runBlocking {
        val junk = File(dir, "junk.pdf").apply { writeBytes(Random(5).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertNotNull(failureOf { split.split(document(junk, pages = 4), SplitMode.EveryN(2), run) {} })
        assertRunEmpty(run)
    }

    // --- split: by size ---

    @Test
    fun bySizeKeepsEveryPartUnderTheLimitAndEveryPageInOrder() = runBlocking {
        val source = document(fixture("photos.pdf", pages = 8, imageSide = IMAGE_SIDE))
        val limit = 3L * IMAGE_BYTES + IMAGE_BYTES / 2

        val result = split.split(source, SplitMode.MaxSize(limit), workspace.newRun()) {}

        assertTrue("expected several parts, got ${result.outputs.size}", result.outputs.size > 1)
        result.outputs.forEach { assertTrue("${it.fileName} is ${it.sizeBytes}", it.sizeBytes <= limit) }
        assertEquals((201..208).toList(), result.outputs.flatMap { widths(it.file) })
        assertEquals("photos_part-01.pdf", result.outputs.first().fileName)
        assertTrue(result.notes.isEmpty())
        // Rejected candidates are deleted as the search goes, not just at the end.
        assertEquals(result.outputs.size, result.outputs.first().file.parentFile!!.list()!!.size)
    }

    @Test
    fun aPageOverTheLimitGetsItsOwnPartAndANote() = runBlocking {
        val source = document(fixture("photos.pdf", pages = 3, imageSide = IMAGE_SIDE))

        val result = split.split(source, SplitMode.MaxSize(IMAGE_BYTES / 4), workspace.newRun()) {}

        assertEquals(3, result.outputs.size)
        assertEquals(listOf(listOf(201), listOf(202), listOf(203)), result.outputs.map { widths(it.file) })
        assertEquals(listOf(ToolNote(R.plurals.pdf_tool_split_oversized_pages, 3, plural = true)), result.notes)
    }

    @Test
    fun aLimitAboveTheWholeFileMakesOnePart() = runBlocking {
        val source = document(fixture("small.pdf", pages = 5))

        val outputs = split.split(source, SplitMode.MaxSize(source.sizeBytes * 10), workspace.newRun()) {}.outputs

        assertEquals((201..205).toList(), widths(outputs.single().file))
    }

    @Test
    fun splitWithNoSpaceIsLowStorage() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val source = document(fixture("report.pdf", pages = 4))
        val run = full.newRun()

        assertEquals(
            ToolFailure.LowStorage,
            failureOf { SplitJob(qpdf, full).split(source, SplitMode.EveryN(1), run) {} }
        )
        assertRunEmpty(run)
    }

    // --- helpers ---

    private fun document(file: File, pages: Int? = null, password: String = ""): ToolDocument = ToolDocument(
        file = file,
        displayName = file.name,
        sizeBytes = file.length(),
        pageCount = pages ?: PDDocument.load(file).use { it.numberOfPages },
        password = password,
        isEncrypted = password.isNotEmpty()
    )

    private suspend fun encrypted(source: File, name: String): ToolDocument {
        val locked = File(dir, name)
        assertEquals("fixture encryption failed", QpdfResult.Ok, qpdf.encrypt(source, locked, SECRET, SECRET))
        return document(locked, pages = PDDocument.load(source).use { it.numberOfPages }, password = SECRET)
    }

    /** Each page's MediaBox width, which the fixtures set to 200 + the page's original number. */
    private fun widths(file: File, password: String = ""): List<Int> =
        PDDocument.load(file, password).use { pdf -> pdf.pages.map { it.mediaBox.width.toInt() } }

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

    private fun fixture(name: String, pages: Int, widthBase: Int = 200, imageSide: Int = 0): File =
        File(dir, name).apply { outputStream().buffered().use { writePdf(it, pages, widthBase, imageSide) } }

    /**
     * A minimal valid PDF with a real cross-reference table. Page n is (widthBase + n) points
     * wide. With [imageSide] > 0 each page also draws its own [imageSide]² RGB image of random
     * pixels, stored raw: random bytes don't deflate, so qpdf can't shrink a page below its image
     * and file sizes stay predictable for the by-size tests.
     */
    private fun writePdf(out: OutputStream, pages: Int, widthBase: Int, imageSide: Int) {
        val offsets = mutableListOf<Long>()
        var position = 0L
        fun write(bytes: ByteArray) {
            out.write(bytes)
            position += bytes.size
        }
        fun write(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
        fun startObject(number: Int) {
            check(offsets.size == number - 1) { "objects must be written in order" }
            offsets += position
            write("$number 0 obj\n")
        }

        val stride = if (imageSide > 0) 3 else 2
        val random = Random(pages * 31 + imageSide)
        write("%PDF-1.4\n%âãÏÓ\n")
        startObject(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until pages).joinToString(" ") { "${3 + it * stride} 0 R" }
        startObject(2)
        write("<< /Type /Pages /Kids [$kids] /Count $pages >>\nendobj\n")

        repeat(pages) { index ->
            val pageObject = 3 + index * stride
            val width = widthBase + index + 1
            val resources = if (imageSide > 0) "/Resources << /XObject << /Im0 ${pageObject + 2} 0 R >> >> " else ""
            startObject(pageObject)
            write(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width 842] $resources" +
                    "/Contents ${pageObject + 1} 0 R >>\nendobj\n"
            )
            val drawing = if (imageSide > 0) {
                "q 100 0 0 100 10 10 cm /Im0 Do Q\n"
            } else {
                "0 0 0 RG 10 ${700 - index} m 150 ${700 - index} l S\n"
            }
            startObject(pageObject + 1)
            write("<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")
            if (imageSide > 0) {
                startObject(pageObject + 2)
                write(
                    "<< /Type /XObject /Subtype /Image /Width $imageSide /Height $imageSide " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Length ${imageSide * imageSide * 3} >>\nstream\n"
                )
                write(random.nextBytes(imageSide * imageSide * 3))
                write("\nendstream\nendobj\n")
            }
        }

        val xrefOffset = position
        val size = offsets.size + 1
        write("xref\n0 $size\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    }

    private companion object {
        const val SECRET = "secret"
        const val MANY_FILES = 12
        const val PAGES_PER_FILE = 10
        const val LONG_PAGES = 120
        const val IMAGE_SIDE = 300
        const val IMAGE_BYTES = IMAGE_SIDE * IMAGE_SIDE * 3L
    }
}
