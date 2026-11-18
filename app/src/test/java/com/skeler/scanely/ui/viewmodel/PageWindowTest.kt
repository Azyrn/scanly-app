package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import com.skeler.scanely.core.image.ScanFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * P3: review's memory follows what is on screen, not the page count. Only the current page and its neighbours
 * are decoded; a picture the screen may have drawn is recycled only after the screen reports a composition
 * without it.
 */
class PageWindowTest {

    private class Pic(val page: Int, val steps: List<String>) {
        var recycled = false
    }

    private inner class FakeOps : PageOps<Pic> {
        val decoded = mutableListOf<Int>()
        val all = mutableListOf<Pic>()
        val unreadable = mutableSetOf<Int>()

        private fun make(page: Int, steps: List<String>) = Pic(page, steps).also { all += it }

        override fun decode(uri: Uri): Pic? {
            val page = uris.indexOf(uri)
            if (page in unreadable) return null
            decoded += page
            return make(page, listOf("decoded"))
        }

        override fun rotateQuarter(picture: Pic) = make(picture.page, picture.steps + "turn")
        override fun filter(picture: Pic, filter: ScanFilter) = make(picture.page, picture.steps + filter.name)
        override fun thumbnail(picture: Pic) = make(picture.page, picture.steps + "thumb")
        override fun recycle(picture: Pic) {
            assertFalse("recycled twice", picture.recycled)
            picture.recycled = true
        }

        override suspend fun <T> background(block: () -> T): T = block()
    }

    private val uris = List(10) { mock(Uri::class.java) }
    private var pages = uris.mapIndexed { i, uri -> ReviewPage(i.toLong(), uri) }
    private var current = 0
    private var filter = ScanFilter.AUTO
    private val ops = FakeOps()
    private val window = PageWindow(ops)
    private var view: WindowView<Pic>? = null

    private suspend fun sync() = window.sync({ WindowTarget(pages, current, filter) }) { view = it }

    private fun shownPages() = view!!.shown.keys.map { it.toInt() }.toSet()

    @Test
    fun `ten pages - only the current page and its neighbour are decoded`() = runTest {
        sync()

        assertEquals(listOf(0, 1), ops.decoded)
        assertEquals(setOf(0, 1), shownPages())
        assertEquals(ScanFilter.entries.size, view!!.previews.size)
    }

    @Test
    fun `moving on decodes the new neighbour and lets go of the page behind`() = runTest {
        sync()
        current = 2
        sync()

        assertEquals(setOf(1, 2, 3), shownPages())
        assertEquals(listOf(0, 1, 2, 3), ops.decoded)
        val page0 = ops.all.filter { it.page == 0 }
        val source0 = page0.single { it.steps == listOf("decoded") }
        val shown0 = page0.single { it.steps == listOf("decoded", "AUTO") }
        assertTrue("its decoded original was never on screen", source0.recycled)
        assertFalse("its picture may still be drawn", shown0.recycled)

        window.drawn(view!!.version)

        assertTrue("recycled once the screen composed without it", shown0.recycled)
    }

    @Test
    fun `a turn turns the shown picture and a filter change re-renders from the original`() = runTest {
        sync()
        pages = pages.mapIndexed { i, p -> if (i == 0) p.copy(turns = p.turns.rotated()) else p }
        sync()
        assertEquals(listOf("decoded", "AUTO", "turn"), view!!.shown.getValue(0).steps)

        filter = ScanFilter.GRAYSCALE
        pages = pages.map { it.copy(turns = it.turns.refiltered()) }
        sync()

        assertEquals(listOf("decoded", "turn", "GRAYSCALE"), view!!.shown.getValue(0).steps)
        assertEquals(listOf("decoded", "GRAYSCALE"), view!!.shown.getValue(1).steps)
        assertEquals("decoded once each", listOf(0, 1), ops.decoded)
    }

    @Test
    fun `a page that can't be read is marked, not dropped`() = runTest {
        ops.unreadable += 1
        sync()

        assertEquals(setOf(1L), view!!.unreadable)
        assertEquals(setOf(0), shownPages())
        assertEquals(10, pages.size)
    }

    @Test
    fun `release takes every live picture, nothing recycled twice`() = runTest {
        sync()
        current = 5
        sync()

        val held = window.takeAll()
        held.forEach { ops.recycle(it) }

        assertTrue("every picture made is recycled", ops.all.all { it.recycled })
    }
}
