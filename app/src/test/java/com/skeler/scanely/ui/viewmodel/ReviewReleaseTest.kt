package com.skeler.scanely.ui.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review's pages are recycled when it leaves the back stack (P3), but never while something can still use
 * them: a screen still in composition would draw a recycled bitmap ("Canvas: trying to use a recycled
 * bitmap"), an export still writing them would fail half way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewReleaseTest {

    private class Page(val name: String)

    @Test
    fun `nothing in use - every page is recycled at once, a shared one only once`() = runTest {
        val a = Page("a")
        val b = Page("b")
        val recycled = mutableListOf<String>()

        // b is both a page and its own filter preview
        recycleWhenUnused({ listOf(a, b, b) }, emptyList(), MutableStateFlow(0)) { recycled += it.name }

        assertEquals(listOf("a", "b"), recycled)
    }

    @Test
    fun `waits for the screen that may still draw the pages to leave composition`() = runTest {
        val showing = MutableStateFlow(1)
        val recycled = mutableListOf<String>()
        launch { recycleWhenUnused({ listOf(Page("a")) }, emptyList(), showing) { recycled += it.name } }
        runCurrent()
        assertTrue("recycled while still composed", recycled.isEmpty())

        showing.value = 0
        runCurrent()

        assertEquals(listOf("a"), recycled)
    }

    @Test
    fun `waits for a running export even after the screen is gone`() = runTest {
        val exportDone = CompletableDeferred<Unit>()
        val export = launch { exportDone.await() }
        val recycled = mutableListOf<String>()
        launch { recycleWhenUnused({ listOf(Page("a")) }, listOf(export), MutableStateFlow(0)) { recycled += it.name } }
        runCurrent()
        assertTrue("recycled while the export still writes it", recycled.isEmpty())

        exportDone.complete(Unit)
        runCurrent()

        assertEquals(listOf("a"), recycled)
    }

    @Test
    fun `screens are checked after the export, not before`() = runTest {
        val showing = MutableStateFlow(1)
        val exportDone = CompletableDeferred<Unit>()
        val export = launch { exportDone.await() }
        val recycled = mutableListOf<String>()
        launch { recycleWhenUnused({ listOf(Page("a")) }, listOf(export), showing) { recycled += it.name } }

        showing.value = 0
        runCurrent()
        showing.value = 1
        runCurrent()
        exportDone.complete(Unit)
        runCurrent()
        assertTrue("recycled while a screen shows it", recycled.isEmpty())

        showing.value = 0
        runCurrent()
        assertEquals(listOf("a"), recycled)
    }
}
