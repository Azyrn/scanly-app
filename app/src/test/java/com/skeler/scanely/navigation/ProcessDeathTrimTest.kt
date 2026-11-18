package com.skeler.scanely.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

/**
 * After process death the back stack comes back but in-memory screens come back empty. Every one
 * of them has to go, not only the one on top, or Back lands on a blank screen.
 */
class ProcessDeathTrimTest {

    /** Bottom to top, popped the way NavController.popBackStack(route, inclusive = true) does. */
    private fun trimmed(vararg stack: Destination): List<Destination> {
        val entries = stack.toMutableList()
        trimInMemoryScreens(
            isOnStack = { route -> entries.any { route.isInstance(it) } },
            popInclusive = { route: KClass<out Destination> ->
                val topmost = entries.indexOfLast { route.isInstance(it) }
                if (topmost >= 0) entries.subList(topmost, entries.size).clear()
                topmost >= 0
            }
        )
        return entries
    }

    private val home = Destination.Home
    private val settings = Destination.Settings
    private val results = Destination.Results
    private val tools = Destination.PdfTools
    private val tool = Destination.PdfTool("MERGE")

    @Test
    fun `nothing in memory leaves the stack alone`() {
        assertEquals(listOf(home, settings), trimmed(home, settings))
    }

    @Test
    fun `an in-memory screen on top is popped`() {
        assertEquals(listOf(home), trimmed(home, results))
    }

    @Test
    fun `an in-memory screen under a self-loading one is popped with everything above it`() {
        // Back from Settings must not reach an empty Results.
        assertEquals(listOf(home), trimmed(home, results, settings))
    }

    @Test
    fun `the lowest in-memory screen decides how far to trim`() {
        assertEquals(listOf(home, tools), trimmed(home, tools, tool, Destination.PdfStudio, settings))
    }

    @Test
    fun `a screen in memory twice is trimmed to below the lower copy`() {
        assertEquals(listOf(home, settings), trimmed(home, settings, results, tools, results))
    }
}
