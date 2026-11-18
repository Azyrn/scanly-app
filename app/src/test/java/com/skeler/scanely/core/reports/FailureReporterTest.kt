package com.skeler.scanely.core.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/** A report names what went wrong and where, never a message, path or file name. */
class FailureReporterTest {
    @Test fun `the type is the deepest cause's class, and its message is never used`() {
        val error = IllegalStateException("/sdcard/Download/salary.pdf", IOException("contract.docx: no space"))
        assertEquals("IOException", FailureReporter.rootType(error))
    }

    @Test fun `the frame is the first one in Scanly's own code`() {
        val error = RuntimeException("x")
        error.stackTrace = arrayOf(
            StackTraceElement("java.util.ArrayList", "get", null, 1),
            StackTraceElement("com.skeler.scanely.core.pdftools.office2pdf.docx.DocxToPdfJob", "convert", null, 2)
        )
        assertEquals("DocxToPdfJob.convert", FailureReporter.appFrame(error))
    }

    @Test fun `anything outside a field's characters is replaced, and length is capped`() {
        val cleaned = FailureReporter.safe("Could not open salary.pdf", Regex("[^A-Za-z0-9_.$]"), 80)
        assertFalse(cleaned.contains(' '))
        assertEquals(10, FailureReporter.safe("a".repeat(50), Regex("[^a]"), 10).length)
    }
}
