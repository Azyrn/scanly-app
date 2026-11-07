package com.skeler.scanely.core.pdftools

import org.junit.Assert.assertFalse
import org.junit.Test

/** Office conversion is free in both directions. */
class PdfToolGatingTest {

    @Test
    fun `PDF to Word, PowerPoint and Excel are free`() {
        listOf(PdfTool.PDF_TO_WORD, PdfTool.PDF_TO_POWERPOINT, PdfTool.PDF_TO_EXCEL).forEach {
            assertFalse("$it is free", it.requiresPro)
        }
    }

    @Test
    fun `Word, PowerPoint and Excel to PDF are free`() {
        listOf(PdfTool.WORD_TO_PDF, PdfTool.POWERPOINT_TO_PDF, PdfTool.EXCEL_TO_PDF).forEach {
            assertFalse("$it is free", it.requiresPro)
        }
    }
}
