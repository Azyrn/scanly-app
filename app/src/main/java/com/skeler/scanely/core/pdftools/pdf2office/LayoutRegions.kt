package com.skeler.scanely.core.pdftools.pdf2office

/**
 * A page's words into regions in reading order, by recursive cuts along empty bands (XY-cut).
 *
 * Lines are clustered across the whole page width, so without this two columns of prose become
 * one line each — "left line, right line" — and every paragraph alternates between two articles.
 * A region is cut down a vertical gutter only when both sides read as prose: a table's columns
 * also leave gutters, and splitting them would lose the table. Columns of Arabic or Hebrew are
 * read right to left. Where no gutter qualifies, the
 * region is cut across its widest horizontal gap (which lifts a full-width headline off the
 * columns below it) and the halves are tried again.
 */
internal object LayoutRegions {

    fun regions(words: List<LayoutWord>): List<List<LayoutWord>> {
        val out = mutableListOf<List<LayoutWord>>()
        cut(words, 0, out)
        return out
    }

    private fun cut(words: List<LayoutWord>, depth: Int, out: MutableList<List<LayoutWord>>) {
        if (depth >= MAX_DEPTH || words.size < MIN_WORDS_TO_CUT) {
            out += words
            return
        }
        val em = medianSize(words)
        val vertical = widestGap(words, em * GUTTER_EM, { it.box.left }, { it.box.right })
        if (vertical != null) {
            val (left, right) = words.partition { it.box.centerX < vertical }
            if (isProse(left) && isProse(right)) {
                val (first, second) = if (TextDirection.isRtl(words)) right to left else left to right
                cut(first, depth + 1, out)
                cut(second, depth + 1, out)
                return
            }
        }
        val horizontal = widestGap(words, em * SECTION_GAP_EM, { it.box.top }, { it.box.bottom })
        if (horizontal != null) {
            val (above, below) = words.partition { it.box.centerY < horizontal }
            cut(above, depth + 1, out)
            cut(below, depth + 1, out)
            return
        }
        out += words
    }

    /**
     * The middle of the widest band, at least [minGap] wide, that no word crosses along one axis;
     * null when there is none. A band at the edge of the region doesn't count.
     */
    private fun widestGap(
        words: List<LayoutWord>,
        minGap: Float,
        start: (LayoutWord) -> Float,
        end: (LayoutWord) -> Float
    ): Float? {
        val sorted = words.sortedBy(start)
        var reach = end(sorted.first())
        var best: Float? = null
        var bestWidth = minGap
        for (word in sorted) {
            val gap = start(word) - reach
            if (gap >= bestWidth) {
                bestWidth = gap
                best = reach + gap / 2f
            }
            if (end(word) > reach) reach = end(word)
        }
        return best
    }

    /** Lines of several words each: text that wraps, not a column of short values. */
    private fun isProse(words: List<LayoutWord>): Boolean {
        if (words.size < MIN_PROSE_WORDS) return false
        val lines = LayoutLines.lines(words)
        return lines.size >= MIN_PROSE_LINES && words.size.toFloat() / lines.size >= MIN_WORDS_PER_LINE
    }

    private fun medianSize(words: List<LayoutWord>): Float =
        words.map { it.fontSize }.sorted()[words.size / 2]

    /** Bounds the recursion; a page this deeply nested is read as it stands. */
    private const val MAX_DEPTH = 24
    private const val MIN_WORDS_TO_CUT = 8

    /** Wider than the cell split ([LayoutLines]), so ragged prose never opens a false gutter. */
    private const val GUTTER_EM = 1.2f

    /** About a blank line: smaller gaps are line or paragraph spacing inside one block. */
    private const val SECTION_GAP_EM = 1.0f

    private const val MIN_PROSE_WORDS = 6
    private const val MIN_PROSE_LINES = 2
    private const val MIN_WORDS_PER_LINE = 3f
}
