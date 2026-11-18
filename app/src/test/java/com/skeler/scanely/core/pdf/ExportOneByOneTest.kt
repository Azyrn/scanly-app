package com.skeler.scanely.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A scan export makes one full-size page at a time (S1: holding all of them got the app killed on long scans),
 * writes the pages in order, and tells the user about a page it could not read instead of silently dropping it.
 */
class ExportOneByOneTest {

    private class Page(val number: Int) {
        var recycled = false
    }

    private val made = mutableListOf<Page>()
    private var live = 0
    private var maxLive = 0

    private fun render(index: Int, unreadable: Set<Int> = emptySet()): Page? {
        if (index + 1 in unreadable) return null
        live++
        maxLive = maxOf(maxLive, live)
        return Page(index + 1).also { made += it }
    }

    private fun recycle(page: Page) {
        assertTrue("recycled twice: page ${page.number}", !page.recycled)
        page.recycled = true
        live--
    }

    @Test
    fun `every page is written in order, one at a time, and each is recycled`() {
        val written = mutableListOf<Int>()

        val unreadable = exportOneByOne(5, { render(it) }, ::recycle) { index, page ->
            assertEquals(index + 1, page.number)
            written += page.number
        }

        assertEquals(listOf(1, 2, 3, 4, 5), written)
        assertTrue(unreadable.isEmpty())
        assertTrue(made.all { it.recycled })
        assertEquals("pages held at once", 1, maxLive)
    }

    @Test
    fun `a page that can't be read is reported by number and the rest still go out`() {
        val written = mutableListOf<Int>()

        val unreadable = exportOneByOne(4, { render(it, unreadable = setOf(2, 4)) }, ::recycle) { _, page ->
            written += page.number
        }

        assertEquals(listOf(1, 3), written)
        assertEquals(listOf(2, 4), unreadable)
        assertTrue(made.all { it.recycled })
    }

    @Test
    fun `a write that fails still recycles its page and stops the export`() {
        try {
            exportOneByOne(3, { render(it) }, ::recycle) { _, page ->
                if (page.number == 2) throw IOException("disk full")
            }
            fail("the failure must reach the caller")
        } catch (expected: IOException) {
            assertEquals("disk full", expected.message)
        }

        assertEquals("page 3 never made", 2, made.size)
        assertTrue(made.all { it.recycled })
    }
}
