package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.ai.backendFailure
import com.skeler.scanely.core.network.BackendError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An AI error is never a result. Shown as "Extracted Text" it gets Copy, Export and Edit, lands in
 * history, and an edit turns it into a "successful" document; and the upgrade sheet, which only
 * opens for an error, never appears.
 */
class AiErrorNotResultTest {

    /** What the app makes of the Worker's 402 when the month's credits are spent. */
    private fun quotaRefusal(): AiResult.Error {
        val failure = backendFailure(
            402,
            BackendError(
                code = "quota_exceeded",
                message = "You've used your 20 free credits this month. Upgrade for more.",
                requiredTier = "pro"
            )
        )
        return (failure as FatalAiException).result
    }

    @Test
    fun `a batch where every file was refused is an error the paywall can act on`() {
        val combined = combineBatchResults(listOf(quotaRefusal(), quotaRefusal(), quotaRefusal()))

        assertTrue("not saved as a success", combined is AiResult.Error)
        combined as AiResult.Error
        assertTrue("opens the upgrade sheet", combined.isUpgradePath)
        assertEquals(quotaRefusal().message, combined.message)
        assertEquals("pro", combined.requiredTier)
    }

    @Test
    fun `an error is not displayed as extracted text`() {
        assertNull(aiDisplayText(quotaRefusal()))
    }

    @Test
    fun `a batch that read something is still a success`() {
        val combined = combineBatchResults(listOf(AiResult.Success("Page one"), quotaRefusal()))

        assertTrue(combined is AiResult.Success)
        assertTrue((combined as AiResult.Success).text.startsWith("Page one"))
    }
}
