package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumRule
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A ruling line along one axis, in points from the page's top left: [pos] is its y when
 * [horizontal] (else its x), [start]..[end] its extent along the other axis.
 */
internal class Rule(val pos: Float, val start: Float, val end: Float, val horizontal: Boolean)

/**
 * The rules of one table and the grid lines they give. A writer that draws every cell's border on
 * its own (Word does) leaves a rule segment per cell, so segment ends are grid lines too: the ends
 * of horizontal rules are column edges, the ends of vertical rules row edges. That is what finds
 * the columns of a table ruled only between its header and its body.
 */
internal class RuledGrid(val xs: List<Float>, val ys: List<Float>, private val rules: List<Rule>) {

    val hasVerticals: Boolean = rules.any { !it.horizontal }

    /** A vertical rule runs along x = [x] through the height [y]. */
    fun verticalAt(x: Float, y: Float): Boolean =
        rules.any { !it.horizontal && abs(it.pos - x) <= ExactRules.SNAP_PT && it.start <= y && it.end >= y }

    /** A vertical rule along x = [x] starts or ends at the height [y]: two cells meet there. */
    fun verticalBreakAt(x: Float, y: Float): Boolean = rules.any {
        !it.horizontal && abs(it.pos - x) <= ExactRules.SNAP_PT &&
            (abs(it.start - y) <= ExactRules.SNAP_PT || abs(it.end - y) <= ExactRules.SNAP_PT)
    }

    /** A horizontal rule on the line y = [y] passes over x = [x], ends included. */
    fun horizontalAt(y: Float, x: Float): Boolean = rules.any { covers(it, y, x, ExactRules.SNAP_PT) }

    /** A horizontal rule on the line y = [y] starts or ends at x = [x]: two cells meet there. */
    fun breakAt(y: Float, x: Float): Boolean = rules.any {
        it.horizontal && abs(it.pos - y) <= ExactRules.SNAP_PT &&
            (abs(it.start - x) <= ExactRules.SNAP_PT || abs(it.end - x) <= ExactRules.SNAP_PT)
    }

    private fun covers(rule: Rule, y: Float, x: Float, slack: Float): Boolean =
        rule.horizontal && abs(rule.pos - y) <= ExactRules.SNAP_PT && rule.start - slack <= x && rule.end + slack >= x
}

/** Groups a page's ruling lines into tables. Pure geometry, like the rest of the exact layout. */
internal object ExactRules {

    /** Rules and their ends closer than this are one grid line (a double border is 3 pt across). */
    const val SNAP_PT = 3f

    /** A space this much of the text size or wider can separate two cells; a word space cannot. */
    private const val CELL_GAP_EM = 0.6f

    private class Component(val rules: MutableList<Rule>) {
        val left: Float get() = rules.minOf { if (it.horizontal) it.start else it.pos }
        val right: Float get() = rules.maxOf { if (it.horizontal) it.end else it.pos }
        val top: Float get() = rules.minOf { if (it.horizontal) it.pos else it.start }
        val bottom: Float get() = rules.maxOf { if (it.horizontal) it.pos else it.end }

        /** Column edges strictly inside the component. */
        fun innerBreaks(): List<Float> {
            val all = cluster(rules.flatMap { if (it.horizontal) listOf(it.start, it.end) else listOf(it.pos) })
            return all.filter { it - left > SNAP_PT && right - it > SNAP_PT }
        }
    }

    /**
     * The page's ruled grids, top first. Rules that touch are one table; so are stacks of
     * horizontal rules that break at the same column edges with no text running across those edges
     * between them (a table ruled above and below its header and at its foot).
     */
    fun grids(rules: List<PdfiumRule>, pageHeightPt: Float, segments: List<Segment>): List<RuledGrid> {
        val lines = rules.map { rule(it, pageHeightPt) }
        if (lines.size < 2) return emptyList()
        val components = stack(touching(lines).sortedBy { it.top }, segments, pageHeightPt)
        return components.mapNotNull { component ->
            val xs = cluster(
                component.rules.flatMap { if (it.horizontal) listOf(it.start, it.end) else listOf(it.pos) }
            )
            val ys = cluster(
                component.rules.flatMap { if (it.horizontal) listOf(it.pos) else listOf(it.start, it.end) }
            )
            if (xs.size < 2 || ys.size < 2) null else RuledGrid(xs, ys, component.rules)
        }
    }

    private fun rule(rule: PdfiumRule, pageHeightPt: Float): Rule {
        val top = pageHeightPt - rule.top
        val bottom = pageHeightPt - rule.bottom
        return if (rule.right - rule.left >= bottom - top) {
            Rule((top + bottom) / 2f, rule.left, rule.right, horizontal = true)
        } else {
            Rule((rule.left + rule.right) / 2f, top, bottom, horizontal = false)
        }
    }

    /** Connected components of rules that cross, meet at a corner, or continue each other. */
    private fun touching(lines: List<Rule>): List<Component> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var node = i
            while (parent[node] != root) node = parent[node].also { parent[node] = root }
            return root
        }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (touch(lines[i], lines[j])) parent[find(i)] = find(j)
            }
        }
        return lines.indices.groupBy(::find).values.map { group -> Component(group.mapTo(ArrayList()) { lines[it] }) }
    }

    private fun touch(a: Rule, b: Rule): Boolean = if (a.horizontal == b.horizontal) {
        abs(a.pos - b.pos) <= SNAP_PT && min(a.end, b.end) - max(a.start, b.start) >= -SNAP_PT
    } else {
        a.pos >= b.start - SNAP_PT && a.pos <= b.end + SNAP_PT && b.pos >= a.start - SNAP_PT && b.pos <= a.end + SNAP_PT
    }

    /** Joins each component to the one above it when they are parts of one table. */
    private fun stack(sorted: List<Component>, segments: List<Segment>, pageHeightPt: Float): List<Component> {
        val out = ArrayList<Component>()
        for (component in sorted) {
            val above = out.lastOrNull { sameTable(it, component, segments, pageHeightPt) }
            if (above == null) out += component else above.rules += component.rules
        }
        return out
    }

    private fun sameTable(above: Component, below: Component, segments: List<Segment>, pageHeightPt: Float): Boolean {
        if (min(above.right, below.right) - max(above.left, below.left) <= 0f) return false
        val belowBreaks = below.innerBreaks()
        val shared = above.innerBreaks().filter { x -> belowBreaks.any { abs(it - x) <= SNAP_PT } }
        if (shared.isEmpty()) return false
        val between = segments.filter { segment ->
            val baseline = pageHeightPt - segment.baselineV
            baseline > above.bottom && baseline - segment.sizePt < below.top
        }
        return between.none { segment -> shared.any { runsAcross(segment, it) } }
    }

    /** [segment]'s text passes over x = [x] with no cell-wide space there. */
    fun runsAcross(segment: Segment, x: Float): Boolean {
        if (segment.u0 >= x || segment.u1 <= x) return false
        val left = segment.tokens.filter { (it.u0 + it.u1) / 2f < x }.maxOfOrNull { it.u1 } ?: return false
        val right = segment.tokens.filter { (it.u0 + it.u1) / 2f >= x }.minOfOrNull { it.u0 } ?: return false
        return right - left < segment.sizePt * CELL_GAP_EM
    }

    /** Sorted values with those closer than [SNAP_PT] to a cluster's first merged into their mean. */
    fun cluster(values: List<Float>): List<Float> {
        val out = ArrayList<Float>()
        var first = 0f
        var sum = 0f
        var count = 0
        for (value in values.sorted()) {
            if (count > 0 && value - first > SNAP_PT) {
                out += sum / count
                count = 0
                sum = 0f
            }
            if (count == 0) first = value
            sum += value
            count++
        }
        if (count > 0) out += sum / count
        return out
    }
}
