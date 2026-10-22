package com.skeler.scanely.core.scan

import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.ocr.OcrResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo can hold a code and text at once — an invoice with a payment QR, a ticket, a flyer.
 * Reading the code must not cost the user the document's text.
 */
class CombineScanTest {
    private val qr = ScanAction.OpenUrl("https://pay.example.com/inv/42")
    private val invoice = OcrResult.Success("Invoice 42\nTotal due: 18.00 EUR\nbilling@example.com")

    @Test
    fun `a photo with a code and text returns both`() = runBlocking {
        val result = combineScan(listOf(qr)) { invoice }

        assertTrue("the code is kept", result.hasBarcodes)
        assertTrue("the text is read too", result.hasText)
        assertEquals(invoice.text, (result.textResult as OcrResult.Success).text)
        assertTrue(
            "actions found in the text survive",
            result.textActions.any { it is ScanAction.SendEmail }
        )
    }

    @Test
    fun `a code-only photo is not reported as empty when the text engine finds nothing`() =
        runBlocking {
            val result = combineScan(listOf(qr)) { OcrResult.Empty }

            assertTrue(result.hasBarcodes)
            assertFalse(result.hasText)
            assertFalse(result.isEmpty)
        }

    @Test
    fun `a text failure next to a code keeps the code and the failure`() = runBlocking {
        val result = combineScan(listOf(qr)) { OcrResult.Error("Recognition failed") }

        assertTrue(result.hasBarcodes)
        assertTrue(result.textResult is OcrResult.Error)
    }
}
