package com.skeler.scanely.core.ai

import com.skeler.scanely.core.network.BackendError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paywall is an answer only when a plan would lift the limit. The envelopes are the Worker's
 * own, read through the real error mapper; worker/test/upsell-bounds.mjs pins that the Worker names
 * `requiredTier` exactly when a bigger plan accepts what was refused.
 */
class UpgradePathTest {

    private fun errorFor(code: String, message: String, requiredTier: String? = null): AiResult.Error =
        (backendFailure(413, BackendError(code, message, requiredTier = requiredTier)) as FatalAiException).result

    @Test
    fun `a limit that is the same on every plan does not open the paywall`() {
        val extraction = errorFor(
            "doc_too_large",
            "Data extraction reads up to 8 pages. Pick a page range that size or smaller."
        )

        assertFalse(extraction.isUpgradePath)
    }

    @Test
    fun `a limit the user's plan sets, and a bigger plan lifts, does`() {
        val pages = errorFor(
            "doc_too_large",
            "This document is 5 pages. Your plan scans up to 3 pages in a single document.",
            requiredTier = "pro"
        )

        assertTrue(pages.isUpgradePath)
    }

    @Test
    fun `running out of credits still opens the paywall`() {
        assertTrue(errorFor("quota_exceeded", "You've used your 20 free credits this month.").isUpgradePath)
    }
}
