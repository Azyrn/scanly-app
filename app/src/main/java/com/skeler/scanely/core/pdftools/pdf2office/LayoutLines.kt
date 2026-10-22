package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.abs
import kotlin.math.max

/** Words into lines, and each line into cells split at column-sized gaps. */
internal object LayoutLines {

    /**
     * Baseline clustering: words sorted by vertical centre, joined while the centre stays within
     * [LINE_TOLERANCE] of the taller height. Two columns at the same height become one line here —
     * they split again into cells, which is also how a table row is recognised.
     */
    fun lines(words: List<LayoutWord>): List<LayoutLine> {
        val sorted = words.sortedWith(compareBy<LayoutWord> { it.box.centerY }.thenBy { it.box.left })
        val groups = mutableListOf<MutableList<LayoutWord>>()
        var centre = 0f
        var height = 0f
        for (word in sorted) {
            val current = groups.lastOrNull()
            // The larger height, so a thin word (a dash, a dot leader) still joins its line.
            if (current != null && abs(word.box.centerY - centre) <= max(height, word.box.height) * LINE_TOLERANCE) {
                current += word
                centre = (centre * (current.size - 1) + word.box.centerY) / current.size
                height = max(height, word.box.height)
            } else {
                groups += mutableListOf(word)
                centre = word.box.centerY
                height = word.box.height
            }
        }
        return groups.map { group -> buildLine(group.sortedBy { it.box.left }) }
            .sortedWith(compareBy<LayoutLine> { it.box.top }.thenBy { it.box.left })
    }

    private fun buildLine(words: List<LayoutWord>): LayoutLine {
        val box = words.map { it.box }.reduce { a, b -> a.union(b) }
        val size = words.map { it.fontSize }.sorted()[words.size / 2]
        val cells = mutableListOf<LayoutCell>()
        var start = 0
        for (index in 1..words.size) {
            val split = index == words.size ||
                words[index].box.left - words[index - 1].box.right > size * CELL_GAP_EM
            if (split) {
                val run = words.subList(start, index)
                cells += LayoutCell(
                    run.joinToString(" ") { it.text },
                    run.map { it.box }.reduce { a, b -> a.union(b) },
                    run.size
                )
                start = index
            }
        }
        return LayoutLine(words, box, size, cells)
    }

    /** The size most of the page's characters are set in; headings are measured against it. */
    fun bodySize(lines: List<LayoutLine>): Float =
        lines.groupBy { (it.fontSize * 2).toInt() }
            .maxByOrNull { (_, group) -> group.sumOf { line -> line.words.sumOf { it.text.length } } }
            ?.value?.first()?.fontSize
            ?: DEFAULT_SIZE_PT

    private const val LINE_TOLERANCE = 0.4f
    private const val DEFAULT_SIZE_PT = 11f
    private const val CELL_GAP_EM = 1.2f
}
