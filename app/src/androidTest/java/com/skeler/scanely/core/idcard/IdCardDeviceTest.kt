package com.skeler.scanely.core.idcard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.image.DocumentFilters
import com.skeler.scanely.core.image.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * On the phone: the ID copy PDF prints the card at true size, and the whiteboard filter is fast on a
 * real CPU. Writes proof images to <app external files>/idcard_test/ for a visual check.
 */
@RunWith(AndroidJUnit4::class)
class IdCardDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(ctx.getExternalFilesDir(null), "idcard_test").apply { mkdirs() }

    /** A fake card: solid tint with a dark frame, a "photo" block and text bars, so its bounds are measurable. */
    private fun card(tint: Int, w: Int = 1600, h: Int = 1010): Bitmap {
        val b = createBitmap(w, h)
        val c = Canvas(b)
        c.drawColor(tint)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 50, 70) }
        c.drawRect(60f, 200f, 460f, 720f, p)
        for (i in 0 until 6) c.drawRect(560f, 220f + i * 90f, 1450f - i * 60f, 260f + i * 90f, p)
        p.textSize = 70f
        c.drawText("ROYAUME DU MAROC  المملكة المغربية", 60f, 140f, p)
        return b
    }

    @Test
    fun pdfPrintsTheCardAtTrueSize() {
        val copy = IdCopy(
            IdDocumentKind.ID_CARD,
            listOf(card(Color.rgb(170, 205, 230)), card(Color.rgb(230, 210, 170))),
            CopyLook.COLOUR,
            "Dossier banque – nov 2026"
        )
        val pdf = File(out, "id_copy.pdf")
        pdf.outputStream().use { IdCardRenderer.writePdf(copy, it) }

        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    // render back at 300 dpi and measure the first card's tinted area
                    val w = IdCardLayout.pageWidthPx()
                    val h = IdCardLayout.pageHeightPx()
                    val bmp = createBitmap(w, h)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val box = tintBox(bmp, 0, h / 2)
                    val mmW = box.width() * 25.4f / IdCardLayout.DEFAULT_DPI
                    val mmH = box.height() * 25.4f / IdCardLayout.DEFAULT_DPI
                    println("ID_CARD_MM=$mmW x $mmH")
                    assertTrue("card width $mmW mm", abs(mmW - 85.6f) < 0.6f)
                    assertTrue("card height $mmH mm", abs(mmH - 53.98f) < 0.6f)
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, File(out, "id_copy_page.png").outputStream())
                }
            }
        }
    }

    /** Bounding box of clearly non-white, non-guide pixels in rows [y0, y1). */
    private fun tintBox(b: Bitmap, y0: Int, y1: Int): android.graphics.Rect {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        val row = IntArray(b.width)
        for (y in y0 until y1) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in row.indices) {
                val p = row[x]
                val sat = maxOf(Color.red(p), Color.green(p), Color.blue(p)) - minOf(Color.red(p), Color.green(p), Color.blue(p))
                if (sat > 30) { // the card tint, not the grey guide or white paper
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bt) bt = y
                }
            }
        }
        return android.graphics.Rect(l, t, r + 1, bt + 1)
    }

    @Test
    fun allLooksAndAPassportRender() {
        for (look in CopyLook.entries) {
            val page = IdCardRenderer.renderPage(
                IdCopy(IdDocumentKind.ID_CARD, listOf(card(Color.rgb(170, 205, 230))), look, "Copie CNSS"),
                dpi = 110
            )
            page.compress(Bitmap.CompressFormat.PNG, 100, File(out, "look_${look.name.lowercase()}.png").outputStream())
        }
        val passport = IdCardRenderer.renderPage(
            IdCopy(IdDocumentKind.PASSPORT, listOf(card(Color.rgb(150, 60, 70), 1800, 1270)), CopyLook.COLOUR, ""),
            dpi = 110
        )
        passport.compress(Bitmap.CompressFormat.PNG, 100, File(out, "passport.png").outputStream())
    }

    @Test
    fun fullResolutionRenderIsQuickEnough() {
        val copy = IdCopy(IdDocumentKind.ID_CARD, listOf(card(Color.LTGRAY), card(Color.LTGRAY)), CopyLook.PHOTOCOPY, "Test")
        IdCardRenderer.renderPage(copy, 110).recycle()
        val previewMs = measureTimeMillis { IdCardRenderer.renderPage(copy, 110).recycle() }
        val fullMs = measureTimeMillis { IdCardRenderer.renderPage(copy).recycle() }
        println("ID_PREVIEW_MS=$previewMs ID_FULL_MS=$fullMs")
        assertTrue("preview $previewMs ms", previewMs < 250)
        assertTrue("300 dpi page $fullMs ms", fullMs < 2500)
    }

    @Test
    fun whiteboardFilterIsFastOnAFullPage() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val names = testCtx.assets.list("whiteboard").orEmpty()
        val sources = names.map { n -> testCtx.assets.open("whiteboard/$n").use { android.graphics.BitmapFactory.decodeStream(it) } to n }
            .ifEmpty { listOf(createBitmap(2200, 1650).apply { eraseColor(Color.rgb(200, 196, 188)) } to "flat.png") }
        for ((src, name) in sources) {
            DocumentFilters.apply(src, ScanFilter.WHITEBOARD).recycle() // warm-up
            var result: Bitmap? = null
            val ms = measureTimeMillis { result = DocumentFilters.apply(src, ScanFilter.WHITEBOARD) }
            println("WHITEBOARD_MS[$name ${src.width}x${src.height}]=$ms")
            result!!.compress(Bitmap.CompressFormat.JPEG, 92, File(out, "wb_after_$name.jpg").outputStream())
            assertTrue("whiteboard on ${src.width}x${src.height} took $ms ms", ms < 1000)
        }
    }
}
