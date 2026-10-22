package com.skeler.scanely.core.pdftools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdfeditor.engine.EncryptionState
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

/**
 * The tools engine against the real qpdf and pdfium builds, on fixtures generated here so every
 * byte of input is known. The point is the failure mapping and the cleanup guarantees — that a
 * failed, refused or cancelled job never leaves a file the result screen could offer.
 */
@RunWith(AndroidJUnit4::class)
class PdfToolEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "pdf-tool-engine-test").apply { mkdirs() }

    private val qpdf = QpdfEngine()
    private val workspace = ToolWorkspace(context)
    private val engine = PdfToolEngine(qpdf, PdfPageReader(context), workspace)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- probe ---

    @Test
    fun plainPdfOpensWithItsPageCount() = runBlocking {
        val document = open(fixture("plain.pdf", pages = 3))

        assertEquals(3, document.pageCount)
        assertFalse(document.isEncrypted)
        assertEquals("", document.password)
    }

    @Test
    fun randomBytesAreDamaged() = runBlocking {
        val file = File(dir, "random.pdf").apply { writeBytes(Random(7).nextBytes(20_000)) }

        assertEquals(ToolFailure.Damaged, failureOf { engine.probe(imported(file)) })
    }

    @Test
    fun encryptedPdfNeedsItsPassword() = runBlocking {
        val locked = encryptedFixture()

        assertEquals(ProbeResult.NeedsPassword, engine.probe(imported(locked)))
        assertEquals(ProbeResult.NeedsPassword, engine.probe(imported(locked), "wrong"))

        val opened = engine.probe(imported(locked), SECRET)
        assertTrue("expected Opened, got $opened", opened is ProbeResult.Opened)
        val document = (opened as ProbeResult.Opened).document
        assertTrue(document.isEncrypted)
        assertEquals(3, document.pageCount)
        assertEquals(SECRET, document.password)
    }

    // --- protect ---

    @Test
    fun protectedOutputAsksForThePasswordAndOpensWithIt() = runBlocking {
        val document = open(fixture("plain.pdf", pages = 2))

        val output = engine.protect(document, "hunter2", workspace.newRun()).outputs.single()

        assertEquals("plain_protected.pdf", output.fileName)
        assertEquals("application/pdf", output.mimeType)
        assertEquals(output.file.length(), output.sizeBytes)
        assertEquals(ProbeResult.NeedsPassword, engine.probe(imported(output.file)))
        val reopened = engine.probe(imported(output.file), "hunter2") as ProbeResult.Opened
        assertEquals(2, reopened.document.pageCount)
    }

    // --- unlock ---

    @Test
    fun unlockedOutputIsPlain() = runBlocking {
        val document = open(encryptedFixture(), SECRET)

        val output = engine.unlock(document, workspace.newRun()).outputs.single()

        assertEquals(EncryptionState.PLAIN, qpdf.encryptionState(output.file))
        assertEquals("locked_unlocked.pdf", output.fileName)
    }

    @Test
    fun unlockOnPlainIsRefused() = runBlocking {
        val document = open(fixture("plain.pdf", pages = 1))
        val run = workspace.newRun()

        assertEquals(ToolFailure.NotEncrypted, failureOf { engine.unlock(document, run) })
        assertRunEmpty(run)
    }

    // --- rotate ---

    @Test
    fun rotateAllTurnsEveryPage() = runBlocking {
        val document = open(fixture("plain.pdf", pages = 3))

        val output = engine.rotateAll(document, 90, workspace.newRun()).outputs.single()

        PDFBoxResourceLoader.init(context)
        PDDocument.load(output.file).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            (0 until pdf.numberOfPages).forEach { assertEquals(90, pdf.getPage(it).rotation) }
        }
        assertEquals("plain_rotated.pdf", output.fileName)
    }

    // --- compress ---

    @Test
    fun uncompressedContentShrinks() = runBlocking {
        val document = open(fixture("big.pdf", pages = 2, paddingBytes = 2 * MB))

        val result = engine.compress(document, CompressionLevel.LOSSLESS, workspace.newRun())

        val output = result.outputs.single()
        assertEquals(document.sizeBytes, result.inputBytes)
        assertTrue("${output.sizeBytes} not < ${document.sizeBytes}", output.sizeBytes < document.sizeBytes)
        assertEquals("big_compressed.pdf", output.fileName)
    }

    @Test
    fun compressingCompressedOutputIsNoGainAndLeavesNothing() = runBlocking {
        val first = engine.compress(
            open(fixture("big.pdf", pages = 2, paddingBytes = 2 * MB)),
            CompressionLevel.LOSSLESS,
            workspace.newRun()
        ).outputs.single()
        val again = open(first.file)
        val run = workspace.newRun()

        // The second pass re-deflates at the same level into the same object streams, so it can
        // only come out the same size.
        assertEquals(ToolFailure.NoGain, failureOf { engine.compress(again, CompressionLevel.LOSSLESS, run) })
        assertRunEmpty(run)
    }

    // --- storage and cancellation ---

    @Test
    fun noFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val fullEngine = PdfToolEngine(qpdf, PdfPageReader(context), full)
        val document = (fullEngine.probe(imported(fixture("plain.pdf", pages = 1))) as ProbeResult.Opened).document
        val run = full.newRun()

        assertEquals(
            ToolFailure.LowStorage,
            failureOf { fullEngine.compress(document, CompressionLevel.LOSSLESS, run) }
        )
        assertRunEmpty(run)
    }

    @Test
    fun cancelledCompressLeavesNoOutput() = runBlocking {
        // Re-encoding a large noise image to JPEG takes seconds even on a fast phone (deflating
        // repetitive filler doesn't — it finished before any cancel could land), so the cancel
        // below reliably arrives while qpdf is still inside native code.
        val document = open(imageFixture("noise.pdf", side = NOISE_SIDE))
        val run = workspace.newRun()

        val job = launch(Dispatchers.Default) {
            engine.compress(document, CompressionLevel.SMALLEST, run)
        }
        // Native code can't be interrupted, so this cancellation only takes effect once qpdf
        // returns — the path where the engine must still throw away the finished output.
        delay(CANCEL_AFTER_MS)
        job.cancel()
        job.join()

        assertTrue("compress finished before it could be cancelled", job.isCancelled)
        assertRunEmpty(run)
    }

    // --- helpers ---

    private suspend fun open(file: File, password: String = ""): ToolDocument {
        val result = engine.probe(imported(file), password)
        assertTrue("expected Opened, got $result", result is ProbeResult.Opened)
        return (result as ProbeResult.Opened).document
    }

    private fun imported(file: File) = ImportedFile(file, file.name, file.length())

    private suspend fun encryptedFixture(): File {
        val locked = File(dir, "locked.pdf")
        val encrypted = qpdf.encrypt(fixture("source.pdf", pages = 3), locked, SECRET, SECRET)
        assertEquals("fixture encryption failed", QpdfResult.Ok, encrypted)
        return locked
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

    /** One page carrying a [side]×[side] RGB image of random pixels, stored uncompressed. */
    private fun imageFixture(name: String, side: Int): File = File(dir, name).apply {
        val random = Random(side)
        outputStream().buffered().use { out ->
            val offsets = mutableListOf<Long>()
            var position = 0L
            fun write(bytes: ByteArray) {
                out.write(bytes)
                position += bytes.size
            }
            fun write(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
            fun startObject() {
                offsets += position
                write("${offsets.size} 0 obj\n")
            }
            val drawing = "q 500 0 0 500 47 171 cm /Im0 Do Q\n"
            val imageBytes = side.toLong() * side * 3

            write("%PDF-1.4\n")
            startObject()
            write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            startObject()
            write("<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            startObject()
            write(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>\nendobj\n"
            )
            startObject()
            write("<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")
            startObject()
            write(
                "<< /Type /XObject /Subtype /Image /Width $side /Height $side " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Length $imageBytes >>\nstream\n"
            )
            val row = ByteArray(side * 3)
            repeat(side) {
                random.nextBytes(row)
                write(row)
            }
            write("\nendstream\nendobj\n")

            val xrefOffset = position
            write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { write("%010d 00000 n \n".format(it)) }
            write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        }
    }

    private fun fixture(name: String, pages: Int, paddingBytes: Int = 0): File =
        File(dir, name).apply { outputStream().buffered().use { writePdf(it, pages, paddingBytes) } }

    /**
     * A minimal but valid PDF: catalog, page tree, one page and one content stream per page, and
     * a cross-reference table with real offsets so neither engine has to fall back to repair —
     * repair would hide exactly the damage handling these tests are about.
     *
     * [paddingBytes] of no-op graphics operators go into the first page's content, uncompressed,
     * which is what gives compression something to win.
     */
    private fun writePdf(out: OutputStream, pages: Int, paddingBytes: Int) {
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

        write("%PDF-1.4\n%âãÏÓ\n")
        startObject(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until pages).joinToString(" ") { "${3 + it * 2} 0 R" }
        startObject(2)
        write("<< /Type /Pages /Kids [$kids] /Count $pages >>\nendobj\n")

        repeat(pages) { index ->
            val pageNumber = 3 + index * 2
            startObject(pageNumber)
            write(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Contents ${pageNumber + 1} 0 R >>\nendobj\n"
            )

            val drawing = "0 0 0 RG 50 ${700 - index * 10} m 545 ${700 - index * 10} l S\n"
                .toByteArray(Charsets.ISO_8859_1)
            val padding = if (index == 0) paddingBytes else 0
            val filler = "q Q\n".toByteArray(Charsets.ISO_8859_1)
            val fillerCount = padding / filler.size
            val length = drawing.size + fillerCount * filler.size

            startObject(pageNumber + 1)
            write("<< /Length $length >>\nstream\n")
            write(drawing)
            val chunk = ByteArrayOutputStream().apply { repeat(FILLER_CHUNK) { write(filler) } }.toByteArray()
            var remaining = fillerCount
            while (remaining > 0) {
                val n = minOf(remaining, FILLER_CHUNK)
                write(if (n == FILLER_CHUNK) chunk else chunk.copyOf(n * filler.size))
                remaining -= n
            }
            write("\nendstream\nendobj\n")
        }

        val xrefOffset = position
        val size = offsets.size + 1
        write("xref\n0 $size\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    }

    private companion object {
        const val SECRET = "secret"
        const val MB = 1024 * 1024
        const val FILLER_CHUNK = 16 * 1024
        const val CANCEL_AFTER_MS = 300L
        const val NOISE_SIDE = 4000
    }
}
