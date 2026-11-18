package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdfeditor.PdfFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every PDF tool is free (no paywall); the six Office conversions are marked Beta. */
class OfficeToolsGatingTest {
    private val office = setOf(
        PdfTool.PDF_TO_WORD, PdfTool.PDF_TO_EXCEL, PdfTool.PDF_TO_POWERPOINT,
        PdfTool.WORD_TO_PDF, PdfTool.EXCEL_TO_PDF, PdfTool.POWERPOINT_TO_PDF
    )

    @Test fun `the six Office conversions are Beta, and nothing else is`() {
        office.forEach { assertTrue("$it is Beta", it.isBeta) }
        assertEquals(office, PdfTool.entries.filter { it.isBeta }.toSet())
    }

    @Test fun `no PDF tool or editor feature needs Pro`() {
        assertEquals(emptyList<PdfTool>(), PdfTool.entries.filter { it.requiresPro })
        assertEquals(emptyList<PdfFeature>(), PdfFeature.entries.filter { it.requiresPro })
    }
}
