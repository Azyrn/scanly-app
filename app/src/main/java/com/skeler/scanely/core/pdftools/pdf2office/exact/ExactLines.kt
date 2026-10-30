package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs
import kotlin.math.max

internal class Line(val angle: Int) {
    val items = ArrayList<Item>()
    var v = 0f
    var size = 0f
    var lastU1 = Float.NaN
    var lastU0 = Float.NaN
    var hasRtl = false
    val glyphs get() = items.filterIsInstance<Glyph>()

    fun add(glyph: Glyph) {
        items += glyph
        if (glyph.style.sizePt > size) {
            size = glyph.style.sizePt
            v = glyph.v
        }
        lastU0 = glyph.u0
        lastU1 = glyph.u1
        if (glyph.rtl) hasRtl = true
    }
}

/**
 * Collects glyphs into [Token]s: one token per run of one style, raised when it is set smaller
 * than [lineSize] off the line's baseline [lineV] (a superscript).
 */
internal class TokenBuilder(private val lineSize: Float, private val lineV: Float) {
    val tokens = ArrayList<Token>()
    private val text = StringBuilder()
    private var first: Glyph? = null
    private var u0 = 0f
    private var u1 = 0f
    private var spaceBefore = false
    private var gapBefore = 0f

    /** The style of the token being built, null when none is open. */
    val openStyle: TextStyle? get() = first?.style

    fun start(glyph: Glyph, u0: Float, u1: Float, spaceBefore: Boolean, gapBefore: Float = 0f) {
        first = glyph
        this.u0 = u0
        this.u1 = u1
        this.spaceBefore = spaceBefore
        this.gapBefore = gapBefore
    }

    /** Bidi order: the token covers every glyph it holds. */
    fun append(text: String, u0: Float, u1: Float) {
        this.text.append(text)
        this.u0 = minOf(this.u0, u0)
        this.u1 = maxOf(this.u1, u1)
    }

    /** Visual order: the token runs from its first glyph's left edge to its last glyph's right edge. */
    fun appendInOrder(text: String, u1: Float) {
        this.text.append(text)
        this.u1 = u1
    }

    fun flush() {
        val start = first ?: return
        val raise = if (start.style.sizePt < lineSize * SUPERSCRIPT_SIZE) start.v - lineV else 0f
        tokens += Token(text.toString(), start.style, u0, u1, spaceBefore, raise, gapBefore)
        text.clear()
        first = null
    }

    private companion object {
        const val SUPERSCRIPT_SIZE = 0.85f
    }
}

/** Second stage of [ExactLayout]: glyphs into lines, and left-to-right lines into [Segment]s. */
internal object ExactLines {

    fun lines(items: List<Item>): List<Line> {
        val lines = ArrayList<Line>()
        var line: Line? = null
        for (item in items) {
            when (item) {
                LineBreak -> line = null
                is Space -> line?.items?.add(item)
                is Glyph -> {
                    val current = line
                    val target = if (current != null && fits(
                            current,
                            item
                        )
                    ) {
                        current
                    } else {
                        newLine(item).also { lines += it }
                    }
                    line = target
                    target.add(item)
                }
            }
        }
        return lines.filter { it.glyphs.isNotEmpty() }
    }

    private fun newLine(glyph: Glyph) = Line(glyph.angle).also {
        it.v = glyph.v
        it.size = glyph.style.sizePt
    }

    private fun fits(line: Line, glyph: Glyph): Boolean {
        if (glyph.angle != line.angle) return false
        val size = max(line.size, glyph.style.sizePt)
        if (abs(glyph.v - line.v) > size * SAME_LINE) return false
        // Going back along the line means a new line, unless the script runs right to left.
        val backwards = glyph.u1 < line.lastU0 - size * BACKWARDS
        // Mixed text steps back along the line wherever a left-to-right run sits inside the
        // right-to-left flow.
        return !backwards || glyph.rtl || line.hasRtl
    }

    fun segments(line: Line): List<Segment> {
        if (line.hasRtl) return ExactBidiSegments.segments(line)
        val spaced = spacedGlyphs(line)
        val visual = line.glyphs.sortedBy { (it.u0 + it.u1) / 2 }
        // Brackets read as drawn: a pair's opener on the left (pdfium flips them beside right-to-left text).
        val fixed = ExactBidi.canonicalBrackets(IntArray(visual.size) { visual[it].code })
        visual.forEachIndexed { i, g -> if (fixed[i] != g.code) g.text = String(Character.toChars(fixed[i])) }
        // Size and baseline are each segment's own: a column gap can separate a small caption from
        // a large heading that pdfium reported on the same line.
        return groups(visual, spaced, ExactGlyphs.dominantSize(visual)).map { group ->
            val glyphsInGroup = group.map { it.first }
            val size = ExactGlyphs.dominantSize(glyphsInGroup)
            val v = glyphsInGroup.filter { it.style.sizePt == size }.map { it.v }.average().toFloat()
            segment(group, v, size, line.angle)
        }
    }

    /** Glyphs that come right after a space the PDF drew. */
    private fun spacedGlyphs(line: Line): Set<Glyph> {
        val spaced = HashSet<Glyph>()
        var pendingSpace = false
        for (item in line.items) {
            if (item is Space) {
                pendingSpace = true
            } else if (item is Glyph) {
                if (pendingSpace) spaced += item
                pendingSpace = false
            }
        }
        return spaced
    }

    /** [visual] cut at wide gaps; each glyph paired with whether a space comes before it. */
    private fun groups(visual: List<Glyph>, spaced: Set<Glyph>, lineSize: Float): List<List<Pair<Glyph, Boolean>>> {
        val groups = ArrayList<MutableList<Pair<Glyph, Boolean>>>()
        var current = ArrayList<Pair<Glyph, Boolean>>()
        var previous: Glyph? = null
        for (glyph in visual) {
            val prior = previous
            val markedSpace = glyph in spaced
            val gap = if (prior == null) 0f else glyph.u0 - prior.u1
            val size = max(lineSize, glyph.style.sizePt)
            val split = prior != null && (gap > size * (if (markedSpace) GAP_WITH_SPACE else GAP_WITHOUT_SPACE))
            if (split) {
                groups += current
                current = ArrayList()
            }
            current += glyph to (prior != null && !split && spaced(markedSpace, gap, size))
            previous = glyph
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /** A space the PDF drew, or a gap wide enough to be one. */
    private fun spaced(
        markedSpace: Boolean,
        gap: Float,
        size: Float
    ): Boolean = markedSpace || gap > size * IMPLIED_SPACE

    private fun segment(group: List<Pair<Glyph, Boolean>>, lineV: Float, lineSize: Float, angle: Int): Segment {
        // Tokens in visual order: a new token at every space and every style or baseline change.
        val builder = TokenBuilder(lineSize, lineV)
        for ((glyph, space) in group) {
            val open = builder.openStyle
            if (open != null && (space || glyph.style != open)) builder.flush()
            if (builder.openStyle == null) builder.start(glyph, glyph.u0, glyph.u1, space)
            builder.appendInOrder(glyph.text, glyph.u1)
        }
        builder.flush()
        return Segment(builder.tokens, lineV, lineSize, false, angle)
    }

    private const val SAME_LINE = 0.5f
    private const val BACKWARDS = 0.6f
    private const val GAP_WITH_SPACE = 2.0f
    private const val GAP_WITHOUT_SPACE = 0.8f
    private const val IMPLIED_SPACE = 0.15f
}
