package com.skeler.scanely.core.pdfeditor

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The APK carries one LiberationSans, PDFBox's, and both the editor and PDFBox read it.
 *
 * Both halves fail loudly when the file is missing from the APK rather than from a test fixture:
 * `Typeface.createFromAsset` throws "Font asset not found", and PDFBox's FontMapperImpl throws while
 * loading its last-resort font, which it does the first time anything touches a Standard 14 font —
 * including the AcroForm defaults fixup that runs when a form-bearing PDF is signed.
 */
@RunWith(AndroidJUnit4::class)
class FontAssetsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyEditorFontLoadsFromTheApk() {
        for (font in OfficialFont.entries) {
            // The call OverlayComposer and FontMatcher make; they wrap it in runCatching, which is
            // why a missing file would otherwise only show up as the wrong glyphs on the page.
            val typeface = Typeface.createFromAsset(context.assets, font.assetPath)
            val bytes = context.assets.open(font.assetPath).use { it.readBytes() }
            assertTrue("${font.name}: ${font.assetPath} is empty", bytes.size > 10_000)
            assertTrue("${font.name}: no typeface", typeface != null)
        }
    }

    @Test
    fun pdfboxResolvesItsLastResortFont() {
        PDFBoxResourceLoader.init(context)
        // Forces FontMappers.instance(), which parses LiberationSans or throws.
        val width = PDType1Font.HELVETICA.getStringWidth("Scanly")
        assertTrue("Helvetica width was $width", width > 0f)
    }
}
