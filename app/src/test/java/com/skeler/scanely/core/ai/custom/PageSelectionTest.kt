package com.skeler.scanely.core.ai.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page range is parsed twice in this codebase — here, and in `worker/src/pages.ts` — because a
 * scan on a user's own endpoint never reaches the Worker. The same dialog feeds both, so the two
 * grammars have to agree; these cases are the ones where a disagreement would be silent.
 */
class PageSelectionTest {

    private fun indices(spec: String?, pageCount: Int): List<Int> {
        val result = PageSelection.parse(spec, pageCount)
        assertTrue("expected pages, got $result", result is PageSelection.Result.Pages)
        return (result as PageSelection.Result.Pages).indices
    }

    @Test
    fun `no range means the whole document`() {
        assertEquals(listOf(0, 1, 2), indices(null, 3))
        assertEquals(listOf(0, 1, 2), indices("   ", 3))
    }

    @Test
    fun `a range is one-based and inclusive, and comes back zero-based`() {
        assertEquals(listOf(0, 1, 2, 3, 4), indices("1-5", 10))
        assertEquals(listOf(1, 3, 6), indices("2,4,7", 10))
        assertEquals(listOf(4), indices("5", 10))
    }

    /** The bug this whole class exists for: pages 40-45 used to return pages 1-20. */
    @Test
    fun `a late range selects late pages, not the first ones`() {
        assertEquals(listOf(39, 40, 41, 42, 43, 44), indices("40-45", 100))
    }

    @Test
    fun `overlapping parts are merged and sorted rather than repeated`() {
        assertEquals(listOf(0, 1, 2, 3), indices("3,1-2,2-4,1", 10))
    }

    @Test
    fun `whitespace around a dash is accepted, as the server accepts it`() {
        assertEquals(listOf(1, 2), indices("2 - 3", 10))
    }

    /**
     * `1-999999` is a legal spec. Expanding it before intersecting with the document would
     * allocate a million entries to keep ten, which on a phone is not a rounding error.
     */
    @Test
    fun `a range past the end is clamped to the document, not materialised`() {
        assertEquals(listOf(0, 1, 2), indices("1-999999", 3))
    }

    @Test
    fun `a range entirely past the end is out of range, not empty success`() {
        val result = PageSelection.parse("40-45", 10)
        assertEquals(PageSelection.Result.OutOfRange(10), result)
    }

    @Test
    fun `malformed specs are refused rather than guessed at`() {
        listOf("abc", "1-", "-5", "5-1", "0", "0-3", "1..3", "1;2", "1-2-3").forEach { spec ->
            assertEquals(
                "expected $spec to be malformed",
                PageSelection.Result.Malformed,
                PageSelection.parse(spec, 10)
            )
        }
    }

    @Test
    fun `too many parts is refused, matching the server's own cap`() {
        val tooMany = (1..65).joinToString(",")
        assertEquals(PageSelection.Result.Malformed, PageSelection.parse(tooMany, 100))
    }
}
