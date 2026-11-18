package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.image.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review no longer keeps every page's original and filtered bitmaps; it keeps the edits and replays them. The
 * filters are not rotation-symmetric (their block grid starts at the top-left corner), so the replay must turn
 * and filter in the order the old bitmaps went through: turns made before the last filter change are applied
 * before the filter, turns made after it are applied to the filtered page.
 */
class PageTurnsTest {

    /** A picture as the list of steps that made it. */
    private class Pic(val steps: List<String>) {
        var recycled = false
    }

    private val made = mutableListOf<Pic>()

    private fun rotate(p: Pic) = Pic(p.steps + "turn").also { made += it }
    private fun filter(p: Pic) = Pic(p.steps + "filter").also { made += it }
    private fun render(source: Pic, turns: PageTurns) =
        renderPage(source, turns, ::rotate, ::filter) { it.recycled = true }

    @Test
    fun `a turn after the filter turns the filtered page, the filter is not run again`() {
        val turns = PageTurns().rotated()

        assertEquals(listOf("decoded", "filter", "turn"), render(Pic(listOf("decoded")), turns).steps)
    }

    @Test
    fun `a filter change after turns filters the turned original`() {
        val turns = PageTurns().rotated().rotated().refiltered()

        assertEquals(PageTurns(beforeFilter = 2, afterFilter = 0), turns)
        assertEquals(listOf("decoded", "turn", "turn", "filter"), render(Pic(listOf("decoded")), turns).steps)
    }

    @Test
    fun `turn, filter, turn replays in that order`() {
        val turns = PageTurns().rotated().refiltered().rotated()

        assertEquals(listOf("decoded", "turn", "filter", "turn"), render(Pic(listOf("decoded")), turns).steps)
    }

    @Test
    fun `four turns are a whole turn`() {
        val turns = (1..4).fold(PageTurns()) { t, _ -> t.rotated() }

        assertEquals(PageTurns(), turns)
        assertEquals(PageTurns(beforeFilter = 2), PageTurns(3, 3).refiltered())
    }

    @Test
    fun `the source is never recycled, every step but the result is`() {
        val source = Pic(listOf("decoded"))

        val result = render(source, PageTurns(beforeFilter = 1, afterFilter = 2))

        assertFalse("caller's source", source.recycled)
        assertFalse("result", result.recycled)
        assertEquals(made.size - 1, made.count { it.recycled })
    }

    @Test
    fun `turning a picture once more is the cheap path only for the same filter and earlier turns`() {
        val filter = ScanFilter.AUTO
        val before = RenderKey(filter, PageTurns(1, 0))

        assertTrue(before.turnsOnceInto(RenderKey(filter, PageTurns(1, 1))))
        assertFalse(before.turnsOnceInto(RenderKey(filter, PageTurns(1, 2))))
        assertFalse(before.turnsOnceInto(RenderKey(ScanFilter.GRAYSCALE, PageTurns(1, 1))))
        assertFalse(before.turnsOnceInto(RenderKey(filter, PageTurns(2, 0))))
    }
}
