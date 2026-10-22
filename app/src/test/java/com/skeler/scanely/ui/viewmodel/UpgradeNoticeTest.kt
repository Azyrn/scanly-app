package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.network.PlanDto
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app half of the double-billing guard. A Pro subscriber who buys Max on the website starts
 * a second subscription billed alongside the first, so the paywall has to say so before they go.
 * Plain text only: a link, button or portal hand-off to the website is what Play forbids a reader
 * app, so the words are the whole guard.
 */
class UpgradeNoticeTest {

    private fun plan(id: String, pages: Int) = PlanDto(
        id = id,
        priceUsd = 0.0,
        pagesPerMonth = pages,
        maxPagesPerDoc = 1,
        allowsOffice = false,
        allowsTranslation = false
    )

    private fun plans(current: String) = listOf(
        plan("free", 10),
        plan("pro", 700),
        plan("max", 1500)
    ).map { PlanOption(it, isCurrent = it.id == current) }

    @Test
    fun `a Pro subscriber is told a Max purchase would be a second subscription`() {
        val notice = upgradeNotice("pro", plans("pro"))

        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("second subscription"))
        assertTrue(notice, notice.contains(SCANLY_SITE))
    }

    @Test
    fun `a Pro subscriber is sent to the in-place upgrade, which the portal cannot do`() {
        val notice = upgradeNotice("pro", plans("pro"))!!

        assertTrue(notice, notice.contains("$SCANLY_SITE/upgrade"))
        assertTrue(notice, notice.contains("same account"))
    }

    @Test
    fun `the notice names the site in plain text and never as a link`() {
        val notice = upgradeNotice("pro", plans("pro"))!!

        assertTrue(notice, !notice.contains("http"))
        assertTrue(notice, !notice.contains("/v1/license/portal"))
    }

    @Test
    fun `a free user has nothing to double-buy`() {
        assertNull(upgradeNotice("free", plans("free")))
    }

    @Test
    fun `a Max subscriber has no bigger plan to be warned about`() {
        assertNull(upgradeNotice("max", plans("max")))
    }

    /** Checked by size like the paywall's own top-plan check, so a future tier needs no change. */
    @Test
    fun `an unloaded plan list shows no notice rather than a guess`() {
        assertNull(upgradeNotice("pro", emptyList()))
    }
}
