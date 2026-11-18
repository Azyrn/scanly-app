package com.skeler.scanely.core.ads

import org.junit.Assert.assertEquals
import org.junit.Test

/** A failed rewarded-ad load used to be final for the life of the process. */
class AdLoadRetryTest {

    @Test fun `failed loads are retried with a doubling wait`() {
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L),
            (1..5).map { adLoadRetryDelayMs(it) }
        )
    }

    // Stopping after five left ads off until a restart: the ad button hides while no ad is loaded.
    @Test fun `retries never stop, they settle at eight minutes`() {
        assertEquals(listOf(480_000L, 480_000L, 480_000L), listOf(6, 7, 100).map { adLoadRetryDelayMs(it) })
    }
}
