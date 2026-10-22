package com.skeler.scanely.core.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.imagetools.CompressImageJob
import com.skeler.scanely.core.pdftools.imagetools.ConvertFromJpgJob
import com.skeler.scanely.core.pdftools.imagetools.ConvertToJpgJob
import com.skeler.scanely.core.pdftools.imagetools.ImageBatch
import com.skeler.scanely.core.pdftools.imagetools.ImageCodec
import com.skeler.scanely.core.pdftools.imagetools.ImageTarget
import com.skeler.scanely.core.pdftools.imagetools.ResizeImageJob
import com.skeler.scanely.core.pdftools.imagetools.ResizeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The four image tools against the real Android codecs, on fixtures drawn here. What matters is
 * what a user would notice: photos stay upright, transparency turns white not black, no location
 * leaks into an output, a run with some bad files still delivers the good ones, and a failed run
 * leaves nothing to save.
 */
@RunWith(AndroidJUnit4::class)
class ImageToolsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "image-tools-test").apply { mkdirs() }
    private val inputs = File(dir, "in").apply { mkdirs() }
    private val run = File(dir, "run").apply { mkdirs() }

    private val workspace = ToolWorkspace(context)
    private val batch = ImageBatch(ImageCodec(), workspace)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- Convert to JPG ---

    @Test
    fun transparentPngBecomesJpegOnWhite() = runBlocking {
        val png = halfTransparentPng("logo.png")

        val result = ConvertToJpgJob(batch).convert(jobInput(png))

        val output = result.outputs.single()
        assertEquals(MIME_JPEG, output.mimeType)
        assertEquals("logo.jpg", output.fileName)
        val bitmap = decode(output.file)
        assertNear(Color.WHITE, bitmap.getPixel(5, 20))
        assertNear(Color.RED, bitmap.getPixel(35, 20))
    }

    @Test
    fun exifRotationIsAppliedAndLocationDropped() = runBlocking {
        // Landscape pixels, tagged "rotate 90° clockwise" with a GPS position, as a phone camera writes.
        val jpeg = imageFile("photo.jpg", Bitmap.CompressFormat.JPEG, 40, 20) { canvas, paint ->
            paint.color = Color.BLACK
            canvas.drawRect(0f, 0f, 20f, 20f, paint)
        }
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(LATITUDE, LONGITUDE)
            saveAttributes()
        }
        assertNotNull(ExifInterface(jpeg).latLong)

        val output = ConvertToJpgJob(batch).convert(jobInput(jpeg)).outputs.single()

        val bitmap = decode(output.file)
        assertEquals(20, bitmap.width)
        assertEquals(40, bitmap.height)
        // The black left half ends up on top after a clockwise quarter turn.
        assertNear(Color.BLACK, bitmap.getPixel(10, 5))
        assertNear(Color.WHITE, bitmap.getPixel(10, 35))
        val exif = ExifInterface(output.file)
        assertNull(exif.latLong)
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        )
    }

    @Test
    fun manyImagesAllConvertWithProgress() = runBlocking {
        val files = (1..12).map { photo("p$it.png", Bitmap.CompressFormat.PNG) }
        val progress = mutableListOf<Float?>()

        val result = ConvertToJpgJob(batch).convert(jobInput(*files.toTypedArray()) { progress += it })

        assertEquals(12, result.outputs.size)
        assertEquals(12, result.outputs.map { it.fileName }.toSet().size)
        result.outputs.forEach { assertEquals(MIME_JPEG, it.mimeType) }
        val values = progress.filterNotNull()
        assertEquals(1f, values.last())
        assertEquals(values.sorted(), values)
    }

    @Test
    fun sameNamesDoNotOverwriteEachOther() = runBlocking {
        val a = photo("scan.png", Bitmap.CompressFormat.PNG, subdir = "a")
        val b = photo("scan.png", Bitmap.CompressFormat.PNG, subdir = "b")

        val result = ConvertToJpgJob(batch).convert(jobInput(a, b))

        assertEquals(listOf("scan.jpg", "scan (2).jpg"), result.outputs.map { it.fileName })
    }

    // --- Unreadable inputs ---

    @Test
    fun unreadableFilesAreSkippedWhenOthersWork() = runBlocking {
        val good = photo("good.jpg", Bitmap.CompressFormat.JPEG)
        val bad = File(inputs, "bad.jpg").apply { writeBytes(Random(3).nextBytes(5_000)) }
        val empty = File(inputs, "empty.png").apply { writeBytes(ByteArray(0)) }

        val result = ConvertFromJpgJob(batch).convert(jobInput(bad, good, empty), ImageTarget.PNG)

        assertEquals(1, result.outputs.size)
        assertEquals(listOf(ToolNote(R.plurals.pdf_tool_img_skipped, 2, plural = true)), result.notes)
    }

    @Test
    fun onlyUnreadableFilesFailAndLeaveNothing() = runBlocking {
        val bad = File(inputs, "bad.png").apply { writeBytes(Random(4).nextBytes(5_000)) }

        val failure = failureOf { ConvertToJpgJob(batch).convert(jobInput(bad)) }

        assertEquals(ToolFailure.Unreadable, failure)
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    @Test
    fun lowStorageFailsAndLeavesNothing() = runBlocking {
        val full = ImageBatch(ImageCodec(), ToolWorkspace(context) { 0L })
        val files = arrayOf(photo("a.jpg", Bitmap.CompressFormat.JPEG), photo("b.jpg", Bitmap.CompressFormat.JPEG))

        val failure = failureOf { ResizeImageJob(full).resize(jobInput(*files), ResizeMode.Percent(50)) }

        assertEquals(ToolFailure.LowStorage, failure)
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    // --- Compress ---

    @Test
    fun jpegCompressesSmallerAndKeepsFormat() = runBlocking {
        val jpeg = photo("big.jpg", Bitmap.CompressFormat.JPEG, quality = 100, width = 800, height = 600)

        val result = CompressImageJob(batch).compress(jobInput(jpeg), CompressImageJob.QUALITY_STRONG, false)

        val output = result.outputs.single()
        assertEquals("big_compressed.jpg", output.fileName)
        assertEquals(MIME_JPEG, output.mimeType)
        assertTrue(output.sizeBytes < jpeg.length())
        assertTrue(result.showSizeChange)
        assertTrue(result.notes.isEmpty())
        assertEquals(800, decode(output.file).width)
    }

    @Test
    fun alreadySmallImageComesBackAsTheOriginal() = runBlocking {
        val tiny = photo("tiny.jpg", Bitmap.CompressFormat.JPEG, quality = 5, width = 64, height = 64)

        val result = CompressImageJob(batch).compress(jobInput(tiny), CompressImageJob.QUALITY_LIGHT, false)

        val output = result.outputs.single()
        assertEquals("tiny_compressed.jpg", output.fileName)
        assertArrayEquals(tiny.readBytes(), output.file.readBytes())
        assertEquals(listOf(ToolNote(R.plurals.pdf_tool_img_already_small, 1, plural = true)), result.notes)
    }

    @Test
    fun pngCanBecomeJpeg() = runBlocking {
        val png = photo("shot.png", Bitmap.CompressFormat.PNG, width = 600, height = 400)

        val kept = CompressImageJob(batch).compress(jobInput(png), CompressImageJob.QUALITY_RECOMMENDED, false)
        assertTrue(kept.outputs.single().fileName.endsWith(".png"))
        assertEquals(MIME_PNG, kept.outputs.single().mimeType)

        run.listFiles()?.forEach { it.delete() }
        val converted = CompressImageJob(batch).compress(jobInput(png), CompressImageJob.QUALITY_RECOMMENDED, true)
        val output = converted.outputs.single()
        assertEquals("shot_compressed.jpg", output.fileName)
        assertEquals(MIME_JPEG, output.mimeType)
        assertTrue(output.sizeBytes < png.length())
    }

    // --- Convert from JPG ---

    @Test
    fun jpegConvertsToEachTarget() = runBlocking {
        val jpeg = photo("cat.jpg", Bitmap.CompressFormat.JPEG, width = 120, height = 90)
        val expected = mapOf(
            ImageTarget.PNG to (MIME_PNG to "cat.png"),
            ImageTarget.WEBP to (MIME_WEBP to "cat.webp"),
            ImageTarget.GIF to (MIME_GIF to "cat.gif")
        )
        for ((target, nameAndMime) in expected) {
            val output = ConvertFromJpgJob(batch).convert(jobInput(jpeg), target).outputs.single()
            assertEquals(nameAndMime.first, output.mimeType)
            assertEquals(nameAndMime.second, output.fileName)
            val bitmap = decode(output.file)
            assertEquals(120, bitmap.width)
            assertEquals(90, bitmap.height)
            output.file.delete()
        }
    }

    @Test
    fun gifOutputKeepsColoursClose() = runBlocking {
        val png = halfTransparentPng("flag.png")

        val output = ConvertFromJpgJob(batch).convert(jobInput(png), ImageTarget.GIF).outputs.single()

        val bitmap = decode(output.file)
        assertEquals(0, Color.alpha(bitmap.getPixel(5, 20)))
        assertNear(Color.RED, bitmap.getPixel(35, 20))
    }

    // --- Resize ---

    @Test
    fun resizeByPercentAndBySize() = runBlocking {
        val jpeg = photo("wide.jpg", Bitmap.CompressFormat.JPEG, width = 200, height = 120)
        val job = ResizeImageJob(batch)

        suspend fun sizeAfter(mode: ResizeMode): Pair<Int, Int> {
            val output = job.resize(jobInput(jpeg), mode).outputs.single()
            assertEquals("wide_resized.jpg", output.fileName)
            assertEquals(MIME_JPEG, output.mimeType)
            val bitmap = decode(output.file)
            output.file.delete()
            return bitmap.width to bitmap.height
        }

        assertEquals(100 to 60, sizeAfter(ResizeMode.Percent(50)))
        assertEquals(50 to 30, sizeAfter(ResizeMode.Fit(50, null, keepAspect = true, noEnlarge = true)))
        assertEquals(100 to 60, sizeAfter(ResizeMode.Fit(150, 60, keepAspect = true, noEnlarge = true)))
        assertEquals(200 to 120, sizeAfter(ResizeMode.Fit(1000, null, keepAspect = true, noEnlarge = true)))
        assertEquals(400 to 240, sizeAfter(ResizeMode.Fit(400, null, keepAspect = true, noEnlarge = false)))
        assertEquals(50 to 50, sizeAfter(ResizeMode.Fit(50, 50, keepAspect = false, noEnlarge = true)))
        assertEquals(200 to 50, sizeAfter(ResizeMode.Fit(null, 50, keepAspect = false, noEnlarge = true)))
    }

    @Test
    fun resizeKeepsPngTransparency() = runBlocking {
        val png = halfTransparentPng("icon.png")

        val output = ResizeImageJob(batch).resize(jobInput(png), ResizeMode.Percent(50)).outputs.single()

        assertEquals(MIME_PNG, output.mimeType)
        val bitmap = decode(output.file)
        assertEquals(20, bitmap.width)
        assertEquals(0, Color.alpha(bitmap.getPixel(2, 10)))
    }

    // --- helpers ---

    private fun jobInput(vararg files: File, onProgress: (Float?) -> Unit = {}) = ToolJobInput(
        documents = emptyList(),
        files = files.map { ImportedFile(it, it.name, it.length()) },
        run = run,
        onProgress = onProgress
    )

    private fun imageFile(
        name: String,
        format: Bitmap.CompressFormat,
        width: Int,
        height: Int,
        quality: Int = 95,
        subdir: String? = null,
        draw: (Canvas, Paint) -> Unit
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        draw(Canvas(bitmap), Paint())
        val parent = subdir?.let { File(inputs, it).apply { mkdirs() } } ?: inputs
        val file = File(parent, name)
        file.outputStream().use { bitmap.compress(format, quality, it) }
        bitmap.recycle()
        return file
    }

    /** Noise plus shapes: something with real detail for an encoder to work on. */
    private fun photo(
        name: String,
        format: Bitmap.CompressFormat,
        quality: Int = 95,
        width: Int = 160,
        height: Int = 120,
        subdir: String? = null
    ): File = imageFile(name, format, width, height, quality, subdir) { canvas, paint ->
        val random = Random(name.hashCode())
        repeat(200) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextInt(width).toFloat()
            val y = random.nextInt(height).toFloat()
            canvas.drawCircle(x, y, random.nextInt(4, 30).toFloat(), paint)
        }
    }

    /** 40x40, transparent on the left half, red on the right. */
    private fun halfTransparentPng(name: String): File {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).drawRect(20f, 0f, 40f, 40f, Paint().apply { color = Color.RED })
        val file = File(inputs, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun decode(file: File): Bitmap =
        BitmapFactory.decodeFile(file.path) ?: throw AssertionError("${file.name} doesn't decode")

    private fun assertNear(expected: Int, actual: Int) {
        val close = intArrayOf(RED_SHIFT, GREEN_SHIFT, 0).all { shift ->
            abs((expected shr shift and BYTE) - (actual shr shift and BYTE)) <= COLOR_TOLERANCE
        }
        assertTrue("expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", close)
    }

    private suspend fun failureOf(block: suspend () -> Unit): ToolFailure {
        try {
            block()
        } catch (e: ToolException) {
            return e.failure
        }
        fail("expected a ToolException")
        error("unreachable")
    }

    private companion object {
        const val COLOR_TOLERANCE = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BYTE = 0xFF
        const val LATITUDE = 33.5731
        const val LONGITUDE = -7.5898
    }
}
