package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.organize.PageRange
import com.skeler.scanely.core.pdftools.organize.PageRanges
import com.skeler.scanely.core.pdftools.organize.RangeError
import com.skeler.scanely.core.pdftools.organize.RangeParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {

    @Test
    fun parsesSinglesAndSpansInTypedOrder() {
        assertEquals(
            listOf(PageRange(8, 10), PageRange(1, 3), PageRange(5, 5)),
            ok("8-10, 1-3, 5", pages = 12)
        )
    }

    @Test
    fun acceptsEverySeparatorAndDashKind() {
        val expected = listOf(PageRange(1, 3), PageRange(5, 5), PageRange(7, 9), PageRange(10, 11))
        assertEquals(expected, ok(" 1 - 3;5  7–9,,10—11 ", pages = 12))
    }

    @Test
    fun openEndedSpanRunsToTheLastPage() {
        assertEquals(listOf(PageRange(4, 6)), ok("4-", pages = 6))
    }

    @Test
    fun keepsRepeatsAndOverlaps() {
        assertEquals(listOf(PageRange(1, 2), PageRange(2, 3), PageRange(1, 2)), ok("1-2,2-3,1-2", pages = 3))
    }

    @Test
    fun blankIsEmpty() {
        assertEquals(RangeParse.Invalid(RangeError.EMPTY, null), PageRanges.parse("  , ", 5))
    }

    @Test
    fun pageZeroAndPastTheEndAreOutOfBounds() {
        assertEquals(RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, "0"), PageRanges.parse("0", 5))
        assertEquals(RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, "4-6"), PageRanges.parse("1, 4-6", 5))
        assertEquals(RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, "6-"), PageRanges.parse("6-", 5))
        assertEquals(
            RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, "99999999999"),
            PageRanges.parse("99999999999", 5)
        )
    }

    @Test
    fun reversedSpanIsRefusedNotFlipped() {
        assertEquals(RangeParse.Invalid(RangeError.REVERSED, "5-2"), PageRanges.parse("5-2", 5))
    }

    @Test
    fun garbageIsSyntax() {
        listOf("a", "1-2-3", "-3", "1.5", "3--5", "z").forEach { text ->
            val parsed = PageRanges.parse(text, 10)
            assertTrue("$text gave $parsed", parsed is RangeParse.Invalid && parsed.error == RangeError.SYNTAX)
        }
    }

    @Test
    fun firstBadTokenIsReported() {
        assertEquals(RangeParse.Invalid(RangeError.SYNTAX, "x"), PageRanges.parse("1, x, 99", 5))
    }

    @Test
    fun pagesOfIsTheSortedUnion() {
        assertEquals(setOf(1, 2, 3, 7), PageRanges.pagesOf(listOf(PageRange(7, 7), PageRange(2, 3), PageRange(1, 2))))
        assertEquals(listOf(1, 2, 3, 7), PageRanges.pagesOf(listOf(PageRange(7, 7), PageRange(1, 3))).toList())
    }

    @Test
    fun compactFoldsRuns() {
        assertEquals(
            listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 9)),
            PageRanges.compact(listOf(9, 1, 2, 3, 5, 8, 2))
        )
        assertEquals(emptyList<PageRange>(), PageRanges.compact(emptyList()))
    }

    @Test
    fun specIsQpdfSyntax() {
        assertEquals("1-3,5,8-10", PageRanges.spec(listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 10))))
    }

    private fun ok(text: String, pages: Int): List<PageRange> {
        val parsed = PageRanges.parse(text, pages)
        assertTrue("$text gave $parsed", parsed is RangeParse.Ok)
        return (parsed as RangeParse.Ok).ranges
    }
}
