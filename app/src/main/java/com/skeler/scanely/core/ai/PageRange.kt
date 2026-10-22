package com.skeler.scanely.core.ai

/** Parses page ranges for display; the server remains the billing authority. */
object PageRange {

    private val PART = Regex("^(\\d{1,6})(?:\\s*-\\s*(\\d{1,6}))?$")

    fun parse(spec: String, totalPages: Int): List<Int>? {
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val pages = sortedSetOf<Int>()
        for (part in parts) {
            val match = PART.matchEntire(part) ?: return null
            val from = match.groupValues[1].toIntOrNull() ?: return null
            val to = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            if (from < 1 || to < from || to > totalPages) return null
            pages.addAll(from..to)
        }
        return pages.toList()
    }

    fun isWholeDocument(pages: List<Int>, totalPages: Int): Boolean = pages.size == totalPages
}
