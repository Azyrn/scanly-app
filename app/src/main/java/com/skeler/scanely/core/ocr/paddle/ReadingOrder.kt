package com.skeler.scanely.core.ocr.paddle

import kotlin.math.abs

private const val V_GAP_MIN_FRAC = 0.015f
private const val H_GAP_MIN_FRAC = 0.02f

// On a tall group, 2% of its height outgrows the gap a layout actually leaves: a title sits one
// line above the columns under it, and on a full page that is ~1.5% of the height. A gap this share
// of the group's median line height is a real break between blocks; lines inside a paragraph sit
// closer than this because detection boxes are already unclipped. Only ever lowers the bar.
private const val H_GAP_LINE_FACTOR = 0.8f
private const val V_SIDE_MIN_VEXTENT_FRAC = 0.40f
private const val V_SIDE_MIN_HEXTENT_FRAC = 0.20f
private const val V_SIDE_MIN_QUADS = 2
private const val ABS_MIN_GAP_PX = 6f
private const val TIE_FRAC = 0.02f
private const val MAX_DEPTH = 14
private const val LINE_Y_FACTOR = 0.6f

object ReadingOrder {

    fun orderedLines(quads: List<Quad>, rtl: Boolean): List<List<Int>> {
        if (quads.isEmpty()) return emptyList()
        return order(quads.indices.toList(), quads, rtl, 0)
    }

    private fun order(
        indices: List<Int>,
        quads: List<Quad>,
        rtl: Boolean,
        depth: Int
    ): List<List<Int>> {
        if (indices.size <= 2 || depth >= MAX_DEPTH) return leafLines(indices, quads)

        val groupWidth = indices.maxOf { quads[it].maxX } - indices.minOf { quads[it].minX }
        val groupHeight = indices.maxOf { quads[it].maxY } - indices.minOf { quads[it].minY }

        val columns = columnSplit(indices, quads, groupWidth, groupHeight)
        val rows = rowSplit(indices, quads, groupHeight)
        val split = chooseSplit(columns, rows) ?: return leafLines(indices, quads)

        // Columns are read right to left on an RTL page; rows always top to bottom.
        val (first, second) = if (split === columns && rtl) split.second to split.first else split.first to split.second
        return order(first, quads, rtl, depth + 1) + order(second, quads, rtl, depth + 1)
    }

    private fun chooseSplit(columns: Split?, rows: Split?): Split? = when {
        columns == null -> rows
        rows == null -> columns
        // Prefer header/body when the normalized gaps are tied.
        abs(columns.gapFraction - rows.gapFraction) <= TIE_FRAC || rows.gapFraction > columns.gapFraction -> rows
        else -> columns
    }

    /** Left and right of the widest vertical gap, when that gap really separates two columns. */
    private fun columnSplit(indices: List<Int>, quads: List<Quad>, groupWidth: Float, groupHeight: Float): Split? {
        val vertical = widestGap(indices, quads, horizontal = false)
        val left = indices.filter { centerX(quads[it]) < vertical.cut }
        val right = indices.filter { centerX(quads[it]) >= vertical.cut }
        val valid =
            vertical.width >= maxOf(ABS_MIN_GAP_PX, V_GAP_MIN_FRAC * groupWidth) &&
                left.size >= V_SIDE_MIN_QUADS &&
                right.size >= V_SIDE_MIN_QUADS &&
                verticalExtent(left, quads) >= V_SIDE_MIN_VEXTENT_FRAC * groupHeight &&
                verticalExtent(right, quads) >= V_SIDE_MIN_VEXTENT_FRAC * groupHeight &&
                horizontalExtent(left, quads) >= V_SIDE_MIN_HEXTENT_FRAC * groupWidth &&
                horizontalExtent(right, quads) >= V_SIDE_MIN_HEXTENT_FRAC * groupWidth
        if (!valid) return null
        return Split(left, right, if (groupWidth > 0f) vertical.width / groupWidth else 0f)
    }

    /** Above and below the widest horizontal gap, when that gap is a real break between blocks. */
    private fun rowSplit(indices: List<Int>, quads: List<Quad>, groupHeight: Float): Split? {
        val horizontal = widestGap(indices, quads, horizontal = true)
        val top = indices.filter { centerY(quads[it]) < horizontal.cut }
        val bottom = indices.filter { centerY(quads[it]) >= horizontal.cut }
        val horizontalMin = minOf(H_GAP_MIN_FRAC * groupHeight, H_GAP_LINE_FACTOR * medianHeight(indices, quads))
        val valid = horizontal.width >= maxOf(ABS_MIN_GAP_PX, horizontalMin) && top.isNotEmpty() && bottom.isNotEmpty()
        if (!valid) return null
        return Split(top, bottom, if (groupHeight > 0f) horizontal.width / groupHeight else 0f)
    }

    private fun widestGap(
        indices: List<Int>,
        quads: List<Quad>,
        horizontal: Boolean
    ): Gap {
        val intervals = indices
            .map {
                if (horizontal) {
                    Interval(quads[it].minY, quads[it].maxY)
                } else {
                    Interval(quads[it].minX, quads[it].maxX)
                }
            }
            .sortedBy { it.start }

        var mergedEnd = intervals.first().end
        var widest = 0f
        var cut = intervals.first().start
        for (interval in intervals.drop(1)) {
            if (interval.start <= mergedEnd) {
                mergedEnd = maxOf(mergedEnd, interval.end)
            } else {
                val width = interval.start - mergedEnd
                if (width > widest) {
                    widest = width
                    cut = (mergedEnd + interval.start) / 2f
                }
                mergedEnd = interval.end
            }
        }
        return Gap(widest, cut)
    }

    private fun leafLines(indices: List<Int>, quads: List<Quad>): List<List<Int>> {
        val sorted = indices.sortedBy { quads[it].minY }
        val lines = mutableListOf<MutableList<Int>>()

        for (index in sorted) {
            val quad = quads[index]
            val quadCenterY = centerY(quad)
            val height = quad.maxY - quad.minY
            val line = lines.lastOrNull()?.takeIf { current ->
                val ref = quads[current.first()]
                val refCenter = centerY(ref)
                abs(quadCenterY - refCenter) <
                    maxOf(height, ref.maxY - ref.minY) * LINE_Y_FACTOR
            }
            if (line == null) lines.add(mutableListOf(index)) else line.add(index)
        }

        return lines.map { line -> line.sortedBy { quads[it].minX } }
    }

    private data class Interval(val start: Float, val end: Float)

    private data class Gap(val width: Float, val cut: Float)

    /** A group cut in two, in reading order, with the gap's share of the group's extent. */
    private class Split(val first: List<Int>, val second: List<Int>, val gapFraction: Float)
}

private fun medianHeight(indices: List<Int>, quads: List<Quad>): Float {
    val heights = indices.map { quads[it].maxY - quads[it].minY }.sorted()
    return heights[heights.size / 2]
}

private fun verticalExtent(indices: List<Int>, quads: List<Quad>): Float {
    if (indices.isEmpty()) return 0f
    return indices.maxOf { quads[it].maxY } - indices.minOf { quads[it].minY }
}

private fun horizontalExtent(indices: List<Int>, quads: List<Quad>): Float {
    if (indices.isEmpty()) return 0f
    return indices.maxOf { quads[it].maxX } - indices.minOf { quads[it].minX }
}

private fun centerX(quad: Quad) = (quad.minX + quad.maxX) / 2f

private fun centerY(quad: Quad) = (quad.minY + quad.maxY) / 2f
