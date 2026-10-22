package com.skeler.scanely.ui.pdfeditor

import org.junit.Assert.*
import org.junit.Test

class PageRenderCacheTest {
    @Test fun evictsByBytesAndAccessOrder() {
        val cache = PageRenderCache<Long>(10) { it }
        cache.put(1, 4); cache.put(2, 4)
        cache.get(1)
        cache.put(3, 5)
        assertEquals(setOf(1, 3), cache.snapshot().keys)
    }
    @Test fun replacementDoesNotDoubleCount() {
        val cache = PageRenderCache<Long>(10) { it }
        cache.put(1, 8); cache.put(1, 2); cache.put(2, 8)
        assertEquals(2, cache.snapshot().size)
    }
    @Test fun oversizedPageIsNotRetained() {
        val cache = PageRenderCache<Long>(10) { it }
        cache.put(1, 11)
        assertTrue(cache.snapshot().isEmpty())
    }
    @Test fun clearReleasesAllEntriesAndAccounting() {
        val cache = PageRenderCache<Long>(10) { it }
        cache.put(1, 10); cache.clear(); cache.put(2, 10)
        assertEquals(setOf(2), cache.snapshot().keys)
    }
}
