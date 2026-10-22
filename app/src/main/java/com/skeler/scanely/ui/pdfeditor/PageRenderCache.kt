package com.skeler.scanely.ui.pdfeditor

/** Main-thread-owned byte LRU. Eviction drops references; Compose may still draw a bitmap. */
internal class PageRenderCache<T>(val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private val entries = LinkedHashMap<Int, T>(16, 0.75f, true)
    private var bytes = 0L

    fun get(page: Int): T? = entries[page]

    fun put(page: Int, value: T) {
        entries.remove(page)?.let { bytes -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxBytes) return
        entries[page] = value
        bytes += size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }

    fun snapshot(): Map<Int, T> = entries.toMap()
    fun clear() {
        entries.clear()
        bytes = 0
    }
}
