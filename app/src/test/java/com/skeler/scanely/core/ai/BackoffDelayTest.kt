package com.skeler.scanely.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug these pin: `retryAfterSeconds` was parsed off the error envelope and then dropped, so a
 * Gemini capacity failure answering "retry in 30 s" was retried after ~1 s and again after ~2 s.
 * All three attempts were spent inside four seconds, and the user was told the design had failed
 * roughly twenty-six seconds before the server expected it to work.
 */
class BackoffDelayTest {

    private val localCeiling = BACKOFF_BASE_MS * 2 + BACKOFF_JITTER_MS

    @Test
    fun `without a server hint it uses the local exponential backoff`() {
        repeat(50) {
            val first = backoffDelay(1, null)
            assertTrue(
                "first retry $first outside local backoff range",
                first >= BACKOFF_BASE_MS && first < BACKOFF_BASE_MS + BACKOFF_JITTER_MS
            )
        }
    }

    @Test
    fun `the local backoff still grows between attempts`() {
        val first = backoffDelay(1, null)
        val second = backoffDelay(2, null)
        assertTrue("attempt 2 ($second) should outlast attempt 1 ($first)", second > first)
    }

    @Test
    fun `a server hint longer than the local backoff wins`() {
        assertEquals(30_000L, backoffDelay(1, 30))
        assertTrue(
            "a 30s hint must not be collapsed into the ~1s local backoff",
            backoffDelay(1, 30) > localCeiling
        )
    }

    @Test
    fun `a hint shorter than the local backoff does not shorten the wait`() {
        val delay = backoffDelay(2, 1)
        assertTrue("a 1s hint must not undercut the local backoff", delay >= BACKOFF_BASE_MS * 2)
    }

    /** Our own per-minute limiter answers 60; a full minute of spinner is worse than retrying early. */
    @Test
    fun `a long hint is clamped so no single wait strands the user`() {
        assertEquals(MAX_BACKOFF_MS, backoffDelay(1, 60))
        assertEquals(MAX_BACKOFF_MS, backoffDelay(1, 3600))
    }

    @Test
    fun `three attempts against a capacity failure span the outage, not four seconds`() {
        val total = backoffDelay(1, 30) + backoffDelay(2, 30)
        assertTrue("total wait $total ms is too short to outlast a 30s outage", total >= 60_000L)
    }
}

/**
 * Waiting is only worth it when waiting can work. Google answers a busy model and an exhausted
 * daily quota with the same status; the server tells them apart by how long it says to wait, and
 * these pin the client honouring that distinction.
 */
class WorthRetryingTest {

    @Test
    fun `no hint stays retryable, as before`() {
        assertTrue(worthRetrying(null))
    }

    @Test
    fun `a busy provider is worth waiting for`() {
        assertTrue(worthRetrying(30))
    }

    @Test
    fun `a wait longer than we would ever sit through is not retried`() {
        assertFalse("an hour", worthRetrying(3600))
        assertFalse("until tomorrow", worthRetrying(8 * 60 * 60))
    }

    @Test
    fun `the boundary is the same ceiling the backoff clamps to`() {
        assertTrue(worthRetrying((MAX_BACKOFF_MS / 1000).toInt()))
        assertFalse(worthRetrying((MAX_BACKOFF_MS / 1000).toInt() + 1))
    }
}
