package com.skeler.scanely.core.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ML Kit text recognition through the app's own service, on the device's real recognizer.
 *
 * The recognizer is the Play-services variant, whose model Google Play services installs rather
 * than the APK carrying it. On a device that has not fetched it yet the first calls fail with
 * "waiting for the module to be downloaded", which is what a user would see too, so the test
 * waits that out instead of treating it as a pass.
 */
@RunWith(AndroidJUnit4::class)
class MlKitOcrSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun page(vararg lines: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 200 + lines.size * 90, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 60f, 130f + i * 90f, paint) }
        return bitmap
    }

    @Test
    fun recognizesPrintedLatinText() = runBlocking {
        val service = MlKitOcrService(context, PdfRendererHelper(context))
        val bitmap = page("Invoice number 4471", "Total due 92.50 EUR")

        var result: OcrResult = OcrResult.Empty
        for (attempt in 1..MODULE_WAIT_ATTEMPTS) {
            result = service.recognizeFromBitmap(bitmap)
            val waiting = (result as? OcrResult.Error)?.message.orEmpty().contains("download", true)
            if (!waiting) break
            delay(MODULE_WAIT_MS)
        }

        val text = when (val r = result) {
            is OcrResult.Success -> r.text
            is OcrResult.Error -> throw AssertionError("ML Kit failed: ${r.message}")
            OcrResult.Empty -> throw AssertionError("ML Kit read no text at all")
        }
        assertTrue("read: \"$text\"", text.contains("Invoice", ignoreCase = true))
        assertTrue("read: \"$text\"", text.contains("4471"))
        assertTrue("read: \"$text\"", text.contains("92.50") || text.contains("92,50"))
    }

    private companion object {
        const val MODULE_WAIT_ATTEMPTS = 30
        const val MODULE_WAIT_MS = 3_000L
    }
}
