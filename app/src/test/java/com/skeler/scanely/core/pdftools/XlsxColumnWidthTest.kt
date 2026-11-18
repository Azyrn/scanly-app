package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.xlsx.ColumnSpec
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.FontStyle
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.PageFrame
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.PageMargins
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.PageOrientation
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxLayout
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.maxDigitPx
import com.skeler.scanely.core.pdftools.pdf2office.exact.OfficeFamily
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Column widths in the Normal font's digits (ECMA-376 §18.3.1.13), not a fixed 7 px. */
class XlsxColumnWidthTest {

    /** Digit advances in em of the metric twins: Carlito 0.507, Liberation Sans 0.556. */
    private val measurer = TextMeasurer { _, family, _, _, _, size ->
        size * if (family == OfficeFamily.CALIBRI) CALIBRI_DIGIT_EM else ARIAL_DIGIT_EM
    }

    @Test
    fun specExamplesGiveSevenPixels() {
        assertEquals(7, maxDigitPx(FontStyle(sizePt = 11f, name = "Calibri"), measurer))
        assertEquals(7, maxDigitPx(FontStyle(sizePt = 10f, name = "Arial"), measurer))
    }

    @Test
    fun arialEightIsSixPixels() {
        assertEquals(6, maxDigitPx(FontStyle(sizePt = 8f, name = "Arial"), measurer))
    }

    @Test
    fun widthFollowsTheDigitWidth() {
        val columns = ColumnSpec(mapOf(0 to 8.7109375f), emptySet(), null, 8)
        // The specification's own example: 8.7109375 characters of a 7 px digit are 61 px.
        assertEquals(61 * 0.75f, XlsxLayout.columnWidthPt(columns, 0, 7), 1e-4f)
        // Truncate(((256 × 8.7109375 + Truncate(128 / 6)) / 256) × 6) = 52 px.
        assertEquals(52 * 0.75f, XlsxLayout.columnWidthPt(columns, 0, 6), 1e-4f)
    }

    @Test
    fun paperAndMarginsComeFromTheSheet() {
        val a4 = PageFrame(PageOrientation.LANDSCAPE, 9, PageMargins(56.7f, 56.7f, 70.9f, 70.9f))
        assertEquals(842f, a4.width, 0f)
        assertEquals(842f - 2 * 56.7f, a4.printableWidth, 1e-3f)
        assertEquals(56.7f, a4.marginX, 0f)
        val unnamed = PageFrame(PageOrientation.PORTRAIT)
        assertEquals(612f, unnamed.width, 0f)
        assertEquals(792f, unnamed.height, 0f)
    }

    private companion object {
        const val CALIBRI_DIGIT_EM = 0.507f
        const val ARIAL_DIGIT_EM = 0.556f
    }
}
