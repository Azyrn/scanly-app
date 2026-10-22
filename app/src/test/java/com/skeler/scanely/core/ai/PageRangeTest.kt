package com.skeler.scanely.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeTest {

    @Test
    fun `ranges and singles combine, sorted and deduplicated`() {
        assertEquals(listOf(1, 2, 3), PageRange.parse("1-3", 10))
        assertEquals(listOf(2, 4, 7), PageRange.parse("7, 2,4", 10))
        assertEquals(listOf(1, 2, 3, 9), PageRange.parse("1-3,9,2", 10))
        assertEquals(listOf(5), PageRange.parse("5-5", 10))
    }

    @Test
    fun `whitespace around the dash is accepted`() {
        assertEquals(listOf(3, 4, 5), PageRange.parse(" 3 - 5 ", 10))
    }

    @Test
    fun `nonsense and out-of-bounds ranges are rejected`() {
        assertNull(PageRange.parse("", 10))
        assertNull(PageRange.parse("   ", 10))
        assertNull(PageRange.parse("abc", 10))
        assertNull(PageRange.parse("0-3", 10))
        assertNull(PageRange.parse("5-2", 10))
        assertNull(PageRange.parse("1-11", 10))
        assertNull(PageRange.parse("11", 10))
        assertNull(PageRange.parse("1--3", 10))
    }

    @Test
    fun `a range covering every page is the whole document`() {
        val all = PageRange.parse("1-4", 4)!!
        assertTrue(PageRange.isWholeDocument(all, 4))
        assertFalse(PageRange.isWholeDocument(PageRange.parse("1-3", 4)!!, 4))
    }
}
