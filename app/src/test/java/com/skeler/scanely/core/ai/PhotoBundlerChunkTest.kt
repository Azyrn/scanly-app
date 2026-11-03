package com.skeler.scanely.core.ai

import com.skeler.scanely.core.ai.PhotoBundler.Companion.chunkRanges
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoBundlerChunkTest {

    @Test
    fun `photos that fit go in one PDF`() {
        assertEquals(listOf(0..9), chunkRanges(List(10) { 300L }, maxPages = 300, maxBytes = 30_000))
    }

    @Test
    fun `the plan page limit starts a new PDF`() {
        assertEquals(listOf(0..2, 3..5, 6..6), chunkRanges(List(7) { 1L }, maxPages = 3, maxBytes = 1_000))
    }

    @Test
    fun `the upload size starts a new PDF before it is exceeded`() {
        assertEquals(listOf(0..1, 2..3, 4..4), chunkRanges(List(5) { 400L }, maxPages = 300, maxBytes = 1_000))
    }

    @Test
    fun `a photo exactly filling the budget stays with its run`() {
        assertEquals(listOf(0..1, 2..2), chunkRanges(listOf(500L, 500L, 1L), maxPages = 300, maxBytes = 1_000))
    }

    @Test
    fun `an oversized photo still gets a PDF of its own`() {
        assertEquals(listOf(0..0, 1..1, 2..2), chunkRanges(listOf(10L, 5_000L, 10L), maxPages = 300, maxBytes = 1_000))
    }

    @Test
    fun `order is kept and nothing is dropped`() {
        val sizes = List(1_000) { (it % 7 + 1) * 100L }
        val ranges = chunkRanges(sizes, maxPages = 300, maxBytes = 30_000)
        assertEquals((0 until 1_000).toList(), ranges.flatMap { it.toList() })
        ranges.forEach { range ->
            assert(range.count() <= 300)
            assert(range.sumOf { sizes[it] } <= 30_000)
        }
    }

    @Test
    fun `no photos give no PDFs`() {
        assertEquals(emptyList<IntRange>(), chunkRanges(emptyList(), maxPages = 3, maxBytes = 1_000))
    }
}
