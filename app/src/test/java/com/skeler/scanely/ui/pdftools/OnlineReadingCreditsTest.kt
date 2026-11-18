package com.skeler.scanely.ui.pdftools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online-reading question compares the pages to read with the credits left. When the balance
 * is unknown (/v1/me not loaded or failed) it used to count as 0: the dialog said "You have 0
 * credits" in red and hid Use credits, so a user with credits could not use them. Unknown is not
 * zero; the server decides at the read and refuses it without charging when credits are short.
 */
class OnlineReadingCreditsTest {
    @Test fun `an unknown balance is not shown as too few credits`() {
        assertTrue(hasEnoughCredits(null, 3))
    }

    @Test fun `a known balance is compared with the pages`() {
        assertTrue(hasEnoughCredits(3, 3))
        assertFalse(hasEnoughCredits(2, 3))
        assertFalse(hasEnoughCredits(0, 1))
    }
}
