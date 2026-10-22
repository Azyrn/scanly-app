package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.ocr.OcrBatchBuilder
import com.skeler.scanely.core.ocr.OcrResult
import com.skeler.scanely.core.ocr.scanFailureMessage
import com.skeler.scanely.core.scan.UnifiedScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scan that failed must not be shown as a scan that found nothing. "No text or barcodes
 * detected" tells the user their document is blank; a failure is the app's problem, not theirs.
 */
class ScanFailureStateTest {

    /** What UnifiedScanService.scanImages returns when neither picked file could be opened. */
    private fun bothFilesUnreadable(): UnifiedScanResult {
        val batch = OcrBatchBuilder()
        batch.addError("Failed to load image")
        batch.addError("Failed to load image")
        return UnifiedScanResult(batch.build(), barcodeActions = emptyList(), textActions = emptyList())
    }

    @Test
    fun `files that could not be read are a failure, not an empty result`() {
        val state = bothFilesUnreadable().toUiState(uri = null)

        assertNotNull("the screen is told the scan failed", state.failure)
        assertFalse("and does not fall through to 'nothing detected'", state.isEmpty)
    }

    @Test
    fun `a page with genuinely no text is still reported as empty`() {
        val state = UnifiedScanResult(OcrResult.Empty, emptyList(), emptyList()).toUiState(null)

        assertNull(state.failure)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `a text failure next to a readable code shows the code, not a failure screen`() {
        val code = ScanAction.CopyText("4006381333931", "Copy")
        val state = UnifiedScanResult(OcrResult.Error("Recognition failed"), listOf(code), emptyList())
            .toUiState(null)

        assertEquals(listOf(code), state.barcodeActions)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `the text engine still downloading says so, instead of blaming the image`() {
        val message = scanFailureMessage(
            OcrResult.Error("Waiting for the text optional module to be downloaded. Please wait.")
        )

        assertNotNull(message)
        assertTrue(message!!, message.contains("try again", ignoreCase = true))
        assertFalse(message, message.contains("different", ignoreCase = true))
    }
}
