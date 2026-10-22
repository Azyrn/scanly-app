package com.skeler.scanely.core.pdftools

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.stamp.NumberFormat
import com.skeler.scanely.core.pdftools.stamp.NumberPosition
import com.skeler.scanely.core.pdftools.stamp.NumberSize
import com.skeler.scanely.core.pdftools.stamp.PageNumbersJob
import com.skeler.scanely.core.pdftools.stamp.PageNumbersOptions
import com.skeler.scanely.core.pdftools.stamp.StampLayer
import com.skeler.scanely.core.pdftools.stamp.StampRunner
import com.skeler.scanely.core.pdftools.stamp.WatermarkJob
import com.skeler.scanely.core.pdftools.stamp.WatermarkMark
import com.skeler.scanely.core.pdftools.stamp.WatermarkOptions
import com.skeler.scanely.core.pdftools.stamp.WatermarkPlacement
import com.skeler.scanely.core.pdftools.stamp.WatermarkRotation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream

/**
 * Page numbers and Watermark against the real pdfium, qpdf and PdfDocument. Outputs are checked
 * the way a reader sees them: the stamped text through pdfium's text layer, and placement by
 * rendering the page and looking for ink where the stamp should be — which is the only honest
 * check for rotated pages, where the stamp is drawn in one space and shown in another.
 */
@RunWith(AndroidJUnit4::class)
class StampTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "stamp-test").apply { mkdirs() }

    private val qpdf = QpdfEngine()
    private val reader = PdfPageReader(context)
    private val workspace = ToolWorkspace(context)
    private val engine = PdfToolEngine(qpdf, reader, workspace)
    private val runner = StampRunner(reader, workspace)
    private val numbers = PageNumbersJob(context, runner)
    private val watermark = WatermarkJob(context, runner)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- page numbers ---

    @Test
    fun everyPageGetsItsNumberAndTheTotal() = runBlocking {
        val document = open(fixture("report.pdf", List(3) { A4 }))

        val output = numbers.number(input(document), PageNumbersOptions(format = NumberFormat.PAGE_OF_TOTAL))
            .outputs.single()

        assertEquals("report_numbered.pdf", output.fileName)
        assertEquals(3, reader.pageCount(output.file))
        (0 until 3).forEach { index ->
            val text = reader.plainText(output.file, index).orEmpty()
            assertTrue("page ${index + 1}: '$text'", text.contains("Page ${index + 1} of 3"))
        }
    }

    @Test
    fun coverIsSkippedAndNumberingStartsWhereAsked() = runBlocking {
        val document = open(fixture("book.pdf", List(4) { A4 }))

        val output = numbers.number(input(document), PageNumbersOptions(startNumber = 5, firstPage = 2))
            .outputs.single()

        assertTrue(reader.plainText(output.file, 0).orEmpty().isBlank())
        assertEquals("5", reader.plainText(output.file, 1).orEmpty().trim())
        assertEquals("7", reader.plainText(output.file, 3).orEmpty().trim())
    }

    @Test
    fun numberLandsAtTheVisibleBottomOfARotatedPage() = runBlocking {
        val document = open(fixture("turned.pdf", listOf(A4.copy(rotate = 90), A4.copy(rotate = 180))))

        val output = numbers.number(input(document), PageNumbersOptions(size = NumberSize.LARGE))
            .outputs.single()

        (0 until 2).forEach { index ->
            val page = reader.renderPage(output.file, index, RENDER_WIDTH)!!
            assertTrue("page ${index + 1}: no ink at bottom centre", hasInk(page, 0.4f, 0.6f, 0.85f, 1f))
            assertFalse("page ${index + 1}: ink at the top", hasInk(page, 0f, 1f, 0f, 0.5f))
            page.recycle()
        }
    }

    @Test
    fun mixedPageSizesAreEachNumberedAtTheirOwnCorner() = runBlocking {
        val landscape = PageSpec(842, 595)
        val document = open(fixture("mixed.pdf", listOf(A4, landscape, PageSpec(300, 400))))

        val output = numbers.number(
            input(document),
            PageNumbersOptions(position = NumberPosition.TOP_RIGHT, size = NumberSize.LARGE)
        ).outputs.single()

        (0 until 3).forEach { index ->
            val page = reader.renderPage(output.file, index, RENDER_WIDTH)!!
            assertTrue("page ${index + 1}: no ink top right", hasInk(page, 0.75f, 1f, 0f, 0.2f))
            assertFalse("page ${index + 1}: ink bottom left", hasInk(page, 0f, 0.5f, 0.5f, 1f))
            page.recycle()
        }
    }

    @Test
    fun twoThousandPagesAreNumbered() = runBlocking {
        val document = open(fixture("long.pdf", List(MANY_PAGES) { A4 }))
        var lastProgress = 0f

        val tracked = input(document, onProgress = { p -> p?.let { lastProgress = it } })

        val output = numbers.number(tracked, PageNumbersOptions()).outputs.single()

        assertEquals(MANY_PAGES, reader.pageCount(output.file))
        assertEquals("$MANY_PAGES", reader.plainText(output.file, MANY_PAGES - 1).orEmpty().trim())
        assertTrue("progress never advanced", lastProgress > 0.5f)
    }

    @Test
    fun encryptedDocumentStaysEncryptedAndIsNumbered() = runBlocking {
        val locked = File(dir, "locked.pdf")
        assertEquals(QpdfResult.Ok, qpdf.encrypt(fixture("plain.pdf", List(2) { A4 }), locked, SECRET, SECRET))
        val document = open(locked, SECRET)

        val output = numbers.number(input(document), PageNumbersOptions()).outputs.single()

        assertEquals(ProbeResult.NeedsPassword, engine.probe(ImportedFile(output.file, "x.pdf", output.sizeBytes)))
        assertEquals("2", reader.plainText(output.file, 1, SECRET).orEmpty().trim())
    }

    // --- watermark ---

    @Test
    fun textWatermarkIsOnEveryPageOfEverySize() = runBlocking {
        val document = open(fixture("deck.pdf", listOf(A4, A4, PageSpec(842, 595), A4)))

        val output = watermark.watermark(input(document), WatermarkOptions(WatermarkMark.Text("DRAFT")))
            .outputs.single()

        assertEquals("deck_watermarked.pdf", output.fileName)
        (0 until 4).forEach { index ->
            val text = reader.plainText(output.file, index).orEmpty()
            assertTrue("page ${index + 1}: '$text'", text.contains("DRAFT"))
            val page = reader.renderPage(output.file, index, RENDER_WIDTH)!!
            assertTrue("page ${index + 1}: no ink at centre", hasInk(page, 0.3f, 0.7f, 0.3f, 0.7f))
            page.recycle()
        }
    }

    @Test
    fun tiledUnderlayOnRotatedPageCoversThePage() = runBlocking {
        val document = open(fixture("tiles.pdf", listOf(A4.copy(rotate = 90))))

        val output = watermark.watermark(
            input(document),
            WatermarkOptions(
                WatermarkMark.Text("COPY"),
                placement = WatermarkPlacement.TILED,
                rotation = WatermarkRotation.NONE,
                layer = StampLayer.UNDER
            )
        ).outputs.single()

        val page = reader.renderPage(output.file, 0, RENDER_WIDTH)!!
        assertTrue(hasInk(page, 0f, 0.5f, 0f, 0.5f))
        assertTrue(hasInk(page, 0.5f, 1f, 0.5f, 1f))
        page.recycle()
    }

    @Test
    fun imageWatermarkIsDrawnAtTheTop() = runBlocking {
        val image = File(dir, "logo.png")
        Bitmap.createBitmap(IMAGE_SIDE, IMAGE_SIDE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val document = open(fixture("photo.pdf", List(2) { A4 }))

        val output = watermark.watermark(
            input(document),
            WatermarkOptions(
                WatermarkMark.Image(Uri.fromFile(image).toString()),
                placement = WatermarkPlacement.TOP,
                rotation = WatermarkRotation.NONE
            )
        ).outputs.single()

        val page = reader.renderPage(output.file, 1, RENDER_WIDTH)!!
        assertTrue("no image at the top", hasInk(page, 0.4f, 0.6f, 0f, 0.4f))
        assertFalse("image reached the bottom", hasInk(page, 0f, 1f, 0.7f, 1f))
        page.recycle()
    }

    // --- failures ---

    @Test
    fun unreadableImageFailsAndLeavesNothing() = runBlocking {
        val notAnImage = File(dir, "notes.png").apply { writeText("not an image") }
        val document = open(fixture("plain.pdf", List(1) { A4 }))
        val run = workspace.newRun()

        val failure = failureOf {
            watermark.watermark(
                input(document, run),
                WatermarkOptions(WatermarkMark.Image(Uri.fromFile(notAnImage).toString()))
            )
        }

        assertEquals(ToolFailure.Unreadable, failure)
        assertRunEmpty(run)
    }

    @Test
    fun noFreeSpaceIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val fullNumbers = PageNumbersJob(context, StampRunner(reader, full))
        val document = open(fixture("plain.pdf", List(2) { A4 }))
        val run = full.newRun()

        assertEquals(
            ToolFailure.LowStorage,
            failureOf { fullNumbers.number(input(document, run), PageNumbersOptions()) }
        )
        assertRunEmpty(run)
    }

    @Test
    fun damagedDocumentFailsAndLeavesNothing() = runBlocking {
        val good = open(fixture("plain.pdf", List(2) { A4 }))
        // Opened while intact, damaged before the job reads it: the job must still fail cleanly.
        good.file.writeText("%PDF-1.4\nnothing here")
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { numbers.number(input(good, run), PageNumbersOptions()) })
        assertRunEmpty(run)
    }

    @Test
    fun pageRangesFoldAscendingRuns() {
        assertEquals("1-3,5", StampRunner.pageRangeSpec(listOf(1, 2, 3, 5)))
        // qpdf reads "1,1-2,1" as the page list 1,1,2,1; a run only folds while it ascends.
        assertEquals("1,1-2,1", StampRunner.pageRangeSpec(listOf(1, 1, 2, 1)))
        assertEquals("7", StampRunner.pageRangeSpec(listOf(7)))
    }

    // --- helpers ---

    private fun input(
        document: ToolDocument,
        run: File = workspace.newRun(),
        onProgress: (Float?) -> Unit = {}
    ) = ToolJobInput(listOf(document), emptyList(), run, onProgress)

    private suspend fun open(file: File, password: String = ""): ToolDocument {
        val result = engine.probe(ImportedFile(file, file.name, file.length()), password)
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

    /** True if any pixel in the region (fractions of width and height) is clearly not white. */
    private fun hasInk(page: Bitmap, left: Float, right: Float, top: Float, bottom: Float): Boolean {
        val x0 = (page.width * left).toInt()
        val x1 = (page.width * right).toInt().coerceAtMost(page.width)
        val y0 = (page.height * top).toInt()
        val y1 = (page.height * bottom).toInt().coerceAtMost(page.height)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val pixel = page.getPixel(x, y)
                if (Color.red(pixel) < INK || Color.green(pixel) < INK || Color.blue(pixel) < INK) return true
            }
        }
        return false
    }

    private data class PageSpec(val width: Int, val height: Int, val rotate: Int = 0)

    private fun fixture(name: String, pages: List<PageSpec>): File =
        File(dir, name).apply { outputStream().buffered().use { writePdf(it, pages) } }

    /**
     * A minimal valid PDF with empty pages of the given sizes and /Rotate, so any ink on a
     * rendered output page is the stamp's.
     */
    private fun writePdf(out: OutputStream, pages: List<PageSpec>) {
        val offsets = mutableListOf<Long>()
        var position = 0L
        fun write(text: String) {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            out.write(bytes)
            position += bytes.size
        }
        fun startObject() {
            offsets += position
            write("${offsets.size} 0 obj\n")
        }

        write("%PDF-1.4\n")
        startObject()
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = pages.indices.joinToString(" ") { "${3 + it * 2} 0 R" }
        startObject()
        write("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            startObject()
            write(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${page.width} ${page.height}] " +
                    "/Rotate ${page.rotate} /Contents ${4 + index * 2} 0 R >>\nendobj\n"
            )
            startObject()
            write("<< /Length 0 >>\nstream\n\nendstream\nendobj\n")
        }

        val xrefOffset = position
        val size = offsets.size + 1
        write("xref\n0 $size\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    }

    private companion object {
        val A4 = PageSpec(595, 842)
        const val SECRET = "secret"
        const val MANY_PAGES = 2000
        const val RENDER_WIDTH = 400
        const val IMAGE_SIDE = 64
        const val INK = 200
    }
}
