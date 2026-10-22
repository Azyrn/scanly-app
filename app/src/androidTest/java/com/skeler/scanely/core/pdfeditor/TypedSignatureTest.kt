package com.skeler.scanely.core.pdfeditor

import android.graphics.Color
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.signature.TypedSignature
import com.skeler.scanely.settings.presentation.BundledFontLicenses
import com.skeler.scanely.settings.presentation.readLicense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The typed-signature font and the licence the OFL obliges us to ship with it.
 *
 * TypedSignature.loadTypeface swallows a missing asset (the dialog then says the font failed), so
 * only a test on the real APK notices a dropped file.
 */
@RunWith(AndroidJUnit4::class)
class TypedSignatureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun signatureFontLoadsFromTheApk() {
        val bytes = context.assets.open(TypedSignature.FONT_ASSET).use { it.readBytes() }
        assertTrue("${TypedSignature.FONT_ASSET} is only ${bytes.size} bytes", bytes.size > 10_000)
        assertNotNull(Typeface.createFromAsset(context.assets, TypedSignature.FONT_ASSET))
        assertNotNull(TypedSignature.loadTypeface(context))
    }

    @Test
    fun oflTextShipsWithTheFont() {
        val text = context.assets.open(TypedSignature.LICENSE_ASSET).bufferedReader().use { it.readText() }
        assertTrue(text.contains("SIL Open Font License"))
        assertTrue(text.contains("Great Vibes"))
    }

    @Test
    fun everyLicenceShownInSettingsIsReadable() {
        for ((name, path) in BundledFontLicenses) {
            val text = readLicense(context, path)
            assertTrue("$name: $path missing or empty", !text.isNullOrBlank())
        }
    }

    @Test
    fun renderTrimsToInkOnTransparency() {
        val typeface = requireNotNull(TypedSignature.loadTypeface(context))
        val bitmap = requireNotNull(TypedSignature.render("Jane Doe", typeface, Color.BLACK))

        assertTrue("too small: ${bitmap.width}", bitmap.width in 600..2400)
        assertTrue(bitmap.height in 1 until bitmap.width)
        // Tight bounds: transparent corners, ink somewhere in the middle row.
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)))
        val middle = IntArray(bitmap.width)
        bitmap.getPixels(middle, 0, bitmap.width, 0, bitmap.height / 2, bitmap.width, 1)
        assertTrue(middle.any { Color.alpha(it) > 0 })
    }

    @Test
    fun blankNameRendersNothing() {
        val typeface = requireNotNull(TypedSignature.loadTypeface(context))
        assertNull(TypedSignature.render("   ", typeface, Color.BLACK))
    }
}
