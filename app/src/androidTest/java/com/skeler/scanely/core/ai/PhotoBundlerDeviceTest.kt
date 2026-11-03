package com.skeler.scanely.core.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Runs the real joiner on the phone: real decoding, real PDFBox output, read back by Android's renderer. */
class PhotoBundlerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bundler = PhotoBundler(context)
    private val payloads = PayloadFactory(context)

    private fun photos(count: Int, width: Int = 3000, height: Int = 4000): List<Uri> {
        val dir = File(context.cacheDir, "bundler-test").apply { deleteRecursively(); mkdirs() }
        val paint = Paint().apply { color = Color.BLACK; textSize = 120f }
        return (1..count).map { index ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                repeat(20) { line -> drawText("Photo $index line ${line + 1}", 150f, 300f + line * 180f, paint) }
            }
            val file = File(dir, "photo-$index.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            Uri.fromFile(file)
        }
    }

    private fun pageSizes(uri: Uri): List<Pair<Int, Int>> =
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { pdf ->
                (0 until pdf.pageCount).map { i -> pdf.openPage(i).use { it.width to it.height } }
            }
        }

    @Test fun twelvePhotosOnProBecomeOnePdfOfTwelveUprightPages() = runBlocking {
        val out = bundler.bundle(photos(12), maxPages = 300, maxBytes = 30L * 1024 * 1024)
        assertEquals(1, out.size)
        assertEquals("application/pdf", context.contentResolver.getType(out.single()))
        val pages = pageSizes(out.single())
        assertEquals(12, pages.size)
        pages.forEach { (w, h) -> assertTrue("portrait page expected, got ${w}x$h", h > w) }

        // The upload path takes it as a PDF within the plan's size cap.
        val payload = payloads.create(out.single(), 30L * 1024 * 1024) as PayloadFactory.Payload.File
        assertEquals("application/pdf", payload.mimeType)
        context.getExternalFilesDir(null)!!.resolve("bundle-12.pdf").writeBytes(payload.bytes)
    }

    @Test fun freePlanLimitSplitsIntoRunsOfThree() = runBlocking {
        val out = bundler.bundle(photos(10), maxPages = 3, maxBytes = 5L * 1024 * 1024)
        assertEquals(listOf(3, 3, 3, 1), out.map { pageSizes(it).size })
    }

    @Test fun uploadCapSplitsAndEveryPdfFitsIt() = runBlocking {
        val cap = 700L * 1024
        val out = bundler.bundle(photos(8), maxPages = 300, maxBytes = cap)
        assertTrue("expected a split, got ${out.size}", out.size > 1)
        assertEquals(8, out.sumOf { pageSizes(it).size })
        out.forEach { uri ->
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size }
            assertTrue("PDF of $bytes bytes exceeds $cap", bytes <= cap)
        }
    }

    @Test fun anUnreadablePhotoFailsTheScanInsteadOfDroppingAPage() = runBlocking {
        val bad = File(context.cacheDir, "not-a-photo.jpg").apply { writeText("not an image") }
        try {
            bundler.bundle(photos(2) + Uri.fromFile(bad), maxPages = 300, maxBytes = 30L * 1024 * 1024)
            fail("expected BundleException")
        } catch (expected: PhotoBundler.BundleException) {
            // the message names photo 3
            assertTrue(expected.message!!.contains("photo 3"))
        }
    }
}
