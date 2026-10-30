package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The report from a Word file: a 0.81-inch table column set in 12 pt Times New Roman, where Word
 * fits "Product 1" on one line. Drawn in the phone's own serif (Noto Serif, about 15% wider) every
 * cell wrapped, and "Qty" broke into "Q / ty".
 */
@RunWith(AndroidJUnit4::class)
class DocxFontsTest {

    private val fonts = DocxFonts(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    private fun width(font: String, text: String): Float =
        TextPaint().apply {
            textSize = 12f
            typeface = fonts.typeface(font, bold = false, italic = false)
        }.measureText(text)

    @Test
    fun timesNewRomanFitsTheCellWordFitsItIn() {
        // 1167 twips of column minus the 28-twip cell margins on each side, in points.
        val cellWidthPt = (1167 - 2 * 28) / 20f
        val width = width("Times New Roman", "Product 1")
        assertTrue("\"Product 1\" measured $width pt in a $cellWidthPt pt cell", width <= cellWidthPt)
    }

    @Test
    fun officeFamiliesWithABundledTwinAreNotReportedAsReplaced() {
        listOf("Times New Roman", "Liberation Serif", "Courier New", "Liberation Mono").forEach {
            assertFalse(it, fonts.isReplaced(it))
        }
        assertTrue(fonts.isReplaced("Comic Sans MS"))
    }
}
