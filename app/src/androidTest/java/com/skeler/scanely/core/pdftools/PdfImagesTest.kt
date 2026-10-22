package com.skeler.scanely.core.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdftools.image.ImagePageMargin
import com.skeler.scanely.core.pdftools.image.ImagePageOrientation
import com.skeler.scanely.core.pdftools.image.ImagePageSize
import com.skeler.scanely.core.pdftools.image.ImageToPdfJob
import com.skeler.scanely.core.pdftools.image.ImageToPdfSettings
import com.skeler.scanely.core.pdftools.image.JpgQuality
import com.skeler.scanely.core.pdftools.image.PdfToJpgJob
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * PDF → JPG (page renders and embedded-image extraction) and Image → PDF against the real qpdf,
 * pdfium and PDFBox builds. Fixtures are written here byte by byte so the tests know exactly
 * which images a PDF embeds and how.
 */
@RunWith(AndroidJUnit4::class)
class PdfImagesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "pdf-images-test").apply { mkdirs() }

    private val reader = PdfPageReader(context)
    private val workspace = ToolWorkspace(context)
    private val engine = PdfToolEngine(QpdfEngine(), reader, workspace)
    private val toJpg = PdfToJpgJob(reader, workspace)
    private val toPdf = ImageToPdfJob(context, workspace)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- pages to JPG ---

    @Test
    fun everyPageBecomesAJpegAtTheChosenResolution() = runBlocking {
        val document = open(pdf("three.pdf", pages = 3))

        val normal = toJpg.pagesToJpg(document, JpgQuality.NORMAL, workspace.newRun()) {}
        val high = toJpg.pagesToJpg(document, JpgQuality.HIGH, workspace.newRun()) {}

        assertEquals(listOf("three_page-001.jpg", "three_page-002.jpg", "three_page-003.jpg"),
            normal.outputs.map { it.fileName })
        normal.outputs.forEach { assertEquals(MIME_JPEG, it.mimeType) }
        assertEquals(A4_WIDTH_PT * 150 / 72, bounds(normal.outputs[0].file).outWidth.toFloat(), 2f)
        assertEquals(A4_WIDTH_PT * 300 / 72, bounds(high.outputs[0].file).outWidth.toFloat(), 2f)
    }

    @Test
    fun aGiantPageIsCappedInEdgeAndArea() {
        val (width, height) = PdfToJpgJob.renderSize(14_400f, 14_400f, 300)
        assertTrue("width $width", width <= 4000)
        assertTrue("area ${width.toLong() * height}", width.toLong() * height <= 16_000_000L)

        val (stripWidth, stripHeight) = PdfToJpgJob.renderSize(200f, 14_400f, 300)
        assertTrue("strip area", stripWidth.toLong() * stripHeight <= 16_000_000L)
    }

    @Test
    fun pagesToJpgOnFullStorageIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val document = open(pdf("one.pdf", pages = 1))
        val run = full.newRun()

        val failure = failureOf { PdfToJpgJob(reader, full).pagesToJpg(document, JpgQuality.NORMAL, run) {} }

        assertEquals(ToolFailure.LowStorage, failure)
        assertRunEmpty(run)
    }

    // --- extract images ---

    @Test
    fun jpegsComeOutByteIdenticalAndSharedImagesOnce() = runBlocking {
        val jpeg = jpegBytes(solid(64, 48, Color.RED))
        // 25 pages crosses the 20-page chunk boundary; the logo is on every page.
        val document = open(
            pdf("shared.pdf", pages = 25, images = listOf(Image.Jpeg(jpeg, 64, 48), Image.Rgb(3, 2, RGB_SAMPLES)))
        )
        val run = workspace.newRun()

        val result = toJpg.extractImages(document, run) {}

        assertEquals(listOf("shared_image-001.jpg", "shared_image-002.png"), result.outputs.map { it.fileName })
        assertArrayEquals(jpeg, result.outputs[0].file.readBytes())
        val png = BitmapFactory.decodeFile(result.outputs[1].file.path)
        assertEquals(3, png.width)
        assertEquals(2, png.height)
        assertEquals(Color.rgb(255, 0, 0), png.getPixel(0, 0))
        assertEquals(Color.rgb(0, 0, 255), png.getPixel(2, 1))
        assertTrue(result.notes.isEmpty())
        // Nothing but the outputs is left behind (the native scratch directory is gone).
        assertEquals(2, run.listFiles().orEmpty().size)
    }

    @Test
    fun imagesInUnsupportedFormatsAreCountedNotExported() = runBlocking {
        val document = open(
            pdf("cmyk.pdf", pages = 1, images = listOf(Image.Rgb(3, 2, RGB_SAMPLES), Image.Cmyk(2, 2)))
        )

        val result = toJpg.extractImages(document, workspace.newRun()) {}

        assertEquals(1, result.outputs.size)
        assertEquals(ToolNote(com.skeler.scanely.R.plurals.pdf_tool_jpg_images_skipped, 1, plural = true),
            result.notes.single())
    }

    @Test
    fun aPdfWithoutImagesFailsAndLeavesNothing() = runBlocking {
        val document = open(pdf("text.pdf", pages = 2))
        val run = workspace.newRun()

        assertEquals(ToolFailure.NothingFound, failureOf { toJpg.extractImages(document, run) {} })
        assertRunEmpty(run)
    }

    @Test
    fun extractOnFullStorageIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val document = open(pdf("img.pdf", pages = 1, images = listOf(Image.Rgb(3, 2, RGB_SAMPLES))))
        val run = full.newRun()

        assertEquals(ToolFailure.LowStorage, failureOf { PdfToJpgJob(reader, full).extractImages(document, run) {} })
        assertRunEmpty(run)
    }

    // --- image to PDF ---

    @Test
    fun imagesBecomePagesInOrderWithJpegBytesKept() = runBlocking {
        val jpegFile = File(dir, "photo.jpg").apply { writeBytes(jpegBytes(solid(300, 200, Color.GREEN))) }
        val pngFile = File(dir, "shot.png").apply { writeBytes(pngBytes(solid(100, 400, Color.BLUE))) }

        val result = toPdf.run(
            listOf(imported(jpegFile), imported(pngFile)),
            ImageToPdfSettings(ImagePageSize.A4),
            workspace.newRun()
        ) {}

        val output = result.outputs.single()
        assertEquals("photo_images.pdf", output.fileName)
        assertEquals(MIME_PDF, output.mimeType)
        PDFBoxResourceLoader.init(context)
        PDDocument.load(output.file).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            // Auto orientation: the wide photo gets a landscape page, the tall shot a portrait one.
            val first = pdf.getPage(0)
            assertTrue(first.mediaBox.width > first.mediaBox.height)
            val second = pdf.getPage(1)
            assertTrue(second.mediaBox.width < second.mediaBox.height)

            val image = first.resources.xObjectNames
                .map { first.resources.getXObject(it) }
                .filterIsInstance<PDImageXObject>()
                .single()
            assertEquals(COSName.DCT_DECODE, image.cosObject.filters)
            assertEquals(jpegFile.length(), image.cosObject.length)
        }
    }

    @Test
    fun exifRotationIsAppliedWithoutReencoding() = runBlocking {
        // Stored 200×100: left half red, right half blue. Orientation 6 turns it a quarter
        // clockwise, so upright it is 100×200 with red on top.
        val stored = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 200) for (y in 0 until 100) setPixel(x, y, if (x < 100) Color.RED else Color.BLUE)
        }
        val file = File(dir, "turned.jpg").apply { writeBytes(jpegBytes(stored)) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val output = toPdf.run(
            listOf(imported(file)),
            ImageToPdfSettings(ImagePageSize.FIT, ImagePageOrientation.AUTO, ImagePageMargin.NONE),
            workspace.newRun()
        ) {}.outputs.single()

        val page = reader.renderPage(output.file, 0, 100)!!
        assertEquals(200, page.height)
        assertColor(Color.RED, page.getPixel(50, 40))
        assertColor(Color.BLUE, page.getPixel(50, 160))
    }

    @Test
    fun fitPageTakesTheImageShapePlusMargin() {
        val layout = ImageToPdfJob.layoutPage(
            400,
            200,
            ExifInterface.ORIENTATION_NORMAL,
            ImageToPdfSettings(ImagePageSize.FIT, margin = ImagePageMargin.SMALL)
        )
        assertEquals(300f + 36f, layout.pageWidth, 0.01f)
        assertEquals(150f + 36f, layout.pageHeight, 0.01f)
    }

    @Test
    fun unreadableFilesAreLeftOutWithANote() = runBlocking {
        val good = File(dir, "good.png").apply { writeBytes(pngBytes(solid(50, 50, Color.BLACK))) }
        val bad = File(dir, "bad.jpg").apply { writeBytes(Random(3).nextBytes(5_000)) }

        val result = toPdf.run(listOf(imported(good), imported(bad)), ImageToPdfSettings(), workspace.newRun()) {}

        assertEquals(1, result.notes.single().count)
        PDFBoxResourceLoader.init(context)
        PDDocument.load(result.outputs.single().file).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun onlyUnreadableFilesIsDamagedAndLeavesNothing() = runBlocking {
        val bad = File(dir, "bad.png").apply { writeBytes(Random(5).nextBytes(5_000)) }
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { toPdf.run(listOf(imported(bad)), ImageToPdfSettings(), run) {} })
        assertRunEmpty(run)
    }

    @Test
    fun imageToPdfOnFullStorageIsLowStorageAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val image = File(dir, "a.png").apply { writeBytes(pngBytes(solid(10, 10, Color.WHITE))) }
        val run = full.newRun()

        val job = ImageToPdfJob(context, full)
        val failure = failureOf { job.run(listOf(imported(image)), ImageToPdfSettings(), run) {} }

        assertEquals(ToolFailure.LowStorage, failure)
        assertRunEmpty(run)
    }

    // --- helpers ---

    private suspend fun open(file: File): ToolDocument {
        val result = engine.probe(imported(file))
        assertTrue("expected Opened, got $result", result is ProbeResult.Opened)
        return (result as ProbeResult.Opened).document
    }

    private fun imported(file: File) = ImportedFile(file, file.name, file.length())

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

    private fun assertColor(expected: Int, actual: Int) {
        val close = abs(Color.red(expected) - Color.red(actual)) < COLOR_TOLERANCE &&
            abs(Color.green(expected) - Color.green(actual)) < COLOR_TOLERANCE &&
            abs(Color.blue(expected) - Color.blue(actual)) < COLOR_TOLERANCE
        assertTrue("expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", close)
    }

    private fun bounds(file: File) =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }

    private fun solid(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun jpegBytes(bitmap: Bitmap) =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

    private fun pngBytes(bitmap: Bitmap) =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private sealed interface Image {
        class Jpeg(val bytes: ByteArray, val width: Int, val height: Int) : Image
        class Rgb(val width: Int, val height: Int, val samples: ByteArray) : Image
        class Cmyk(val width: Int, val height: Int) : Image
    }

    /**
     * A valid PDF of [pages] A4 pages, each drawing a line and every one of [images] — the same
     * image objects on every page, which is what makes dedupe observable.
     */
    private fun pdf(name: String, pages: Int, images: List<Image> = emptyList()): File {
        val objects = mutableListOf<ByteArray>()
        fun add(text: String, stream: ByteArray? = null): Int {
            val body = ByteArrayOutputStream()
            body.write(text.toByteArray(Charsets.ISO_8859_1))
            if (stream != null) {
                body.write("\nstream\n".toByteArray(Charsets.ISO_8859_1))
                body.write(stream)
                body.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
            }
            objects += body.toByteArray()
            return objects.size
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("") // page tree, filled in below once the kids are known
        val imageIds = images.map { image ->
            when (image) {
                is Image.Jpeg -> add(
                    "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                        "/Length ${image.bytes.size} >>",
                    image.bytes
                )
                is Image.Rgb -> add(
                    "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Length ${image.samples.size} >>",
                    image.samples
                )
                is Image.Cmyk -> {
                    val samples = ByteArray(image.width * image.height * 4)
                    add(
                        "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                            "/ColorSpace /DeviceCMYK /BitsPerComponent 8 /Length ${samples.size} >>",
                        samples
                    )
                }
            }
        }
        val xObjects = imageIds.withIndex().joinToString(" ") { (i, id) -> "/Im$i $id 0 R" }
        val drawing = buildString {
            append("0 0 0 RG 50 700 m 545 700 l S\n")
            imageIds.indices.forEach { append("q 100 0 0 100 ${50 + it * 120} 400 cm /Im$it Do Q\n") }
        }.toByteArray(Charsets.ISO_8859_1)
        val kids = (0 until pages).map {
            val content = add("<< /Length ${drawing.size} >>", drawing)
            add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Resources << /XObject << $xObjects >> >> /Contents $content 0 R >>"
            )
        }
        objects[1] = "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count $pages >>"
            .toByteArray(Charsets.ISO_8859_1)

        return File(dir, name).apply {
            outputStream().buffered().use { out ->
                var position = 0L
                fun write(bytes: ByteArray) {
                    out.write(bytes)
                    position += bytes.size
                }
                fun write(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
                write("%PDF-1.4\n")
                val offsets = objects.mapIndexed { index, body ->
                    val offset = position
                    write("${index + 1} 0 obj\n")
                    write(body)
                    write("\nendobj\n")
                    offset
                }
                val xref = position
                write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
                offsets.forEach { write("%010d 00000 n \n".format(it)) }
                write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            }
        }
    }

    private companion object {
        const val A4_WIDTH_PT = 595f
        const val COLOR_TOLERANCE = 60

        /** 3×2 RGB: top row red, green, white; bottom row black, grey, blue. */
        val RGB_SAMPLES = byteArrayOf(
            -1, 0, 0, 0, -1, 0, -1, -1, -1,
            0, 0, 0, 127, 127, 127, 0, 0, -1
        )
    }
}
