package com.skeler.scanely.ui.components

import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.ai.backendFailure
import com.skeler.scanely.core.network.BackendError
import com.skeler.scanely.docchat.presentation.screen.designPaywallReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The paywall must say why it opened. A free user tapping "Sign" has not hit a limit; a Pro user
 * out of credits has not been locked out of a Pro feature.
 */
class PaywallReasonTest {

    @Test
    fun `a Pro-only tool is not described as a limit the user hit`() {
        val headline = paywallHeadline(
            reason = "Signing and editing text are part of Scanly Pro.",
            isFeatureLock = true,
            isPaid = false,
            currentTier = "free"
        )

        assertFalse(headline, headline.contains("limit"))
        assertEquals("Part of Scanly Pro", headline)
    }

    @Test
    fun `running out is still described as a limit`() {
        assertEquals(
            "You've hit your plan's limit",
            paywallHeadline("You've used all 700 credits.", isFeatureLock = false, isPaid = true, currentTier = "pro")
        )
    }

    @Test
    fun `the design paywall shows the server's reason, not a fixed Pro pitch`() {
        // A Pro subscriber out of credits, as the Worker words it, through the real mapper.
        val refusal = (
            backendFailure(
                402,
                BackendError("quota_exceeded", "You've used all 700 credits in your plan this month.")
            ) as FatalAiException
            ).result

        assertEquals(refusal.message, designPaywallReason(refusal.message, "fallback"))
    }
}
