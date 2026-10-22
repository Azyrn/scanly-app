package com.skeler.scanely.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings must not tell a paying user they are on Free because the plan has not loaded. The
 * states are what PaywallViewModel builds when `/v1/me` has not answered, or failed.
 */
class UnknownPlanRowTest {

    @Test
    fun `a plan that failed to load is not called Free`() {
        val row = planRow(
            PaywallState(isQuotaKnown = false, error = "Couldn't reach Scanly. Check your connection and try again.")
        )

        assertFalse(row.title, row.title.contains("Free"))
        assertTrue(row.subtitle, row.subtitle.contains("try again", ignoreCase = true))
    }

    @Test
    fun `a plan still loading is not called Free either`() {
        val row = planRow(PaywallState(isQuotaKnown = false, isLoading = true))

        assertFalse(row.title, row.title.contains("Free"))
    }

    @Test
    fun `a loaded plan is named with its credits`() {
        val row = planRow(PaywallState(currentTier = "pro", pagesRemaining = 650, pagesPerMonth = 700, isQuotaKnown = true))

        assertEquals("Pro plan", row.title)
        assertEquals("650 of 700 AI credits left this month", row.subtitle)
    }
}
