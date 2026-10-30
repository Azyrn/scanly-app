package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lines holding right-to-left text, as [Segment]s. pdfium's order for these is unreliable (it
 * reverses some words and not others), so only positions are trusted: glyphs are put in visual order,
 * marks are attached to the letter they sit on, spaces are found where the page has a gap, and the
 * reading order comes from running the bidirectional algorithm backwards ([ExactBidi]). Presentation
 * forms are normalised to base letters, which is what Word wants to shape itself.
 */
internal object ExactBidiSegments {

    /** One drawn character (with the marks that sit on it) or a space, in visual order. */
    private class BidiItem(val glyph: Glyph?, val text: String, val code: Int, val u0: Float, val u1: Float)

    fun segments(line: Line): List<Segment> {
        val glyphs = line.glyphs
        val rtl = glyphs.count { it.rtl } > glyphs.count { it.strongLtr }
        val marks = glyphs.filter { ExactBidi.isMarkCode(it.code) }
        val bases = glyphs.filter { !ExactBidi.isMarkCode(it.code) }
        if (bases.isEmpty()) return emptyList()
        // A letter sitting off the line's baseline (Amiri lowers some for their marks) has an ink
        // box pdfium misreads as a sliver; its advance box is what places it.
        for (g in bases) {
            if (abs(g.v - line.v) > line.size * OFF_BASELINE && g.looseU1 > g.looseU0) {
                g.u0 = g.looseU0
                g.u1 = g.looseU1
            }
        }
        val owner = owners(marks, bases, line.size)
        val visual = bases.sortedBy { (it.u0 + it.u1) / 2 }
        val attached = attach(marks, owner, visual)
        val lineSize = ExactGlyphs.dominantSize(visual)
        // A space pdfium puts on another baseline than the line's belongs to a separate text object
        // (a lowered letter or a mark in a fallback font), not to a gap between words.
        val onLine = line.items.filterIsInstance<Space>().filter { abs(it.v - line.v) <= lineSize * SPACE_BASELINE }
        val explicit = explicitSpaces(visual, onLine, marks, lineSize)
        val spaceBefore = HashMap<Glyph, Boolean>()
        visual.forEachIndexed { i, g -> spaceBefore[g] = explicit[i] }
        return groups(
            visual,
            explicit,
            lineSize
        ).map { group -> segment(group, attached, spaceBefore, rtl, line.angle) }
    }

    /**
     * Some producers draw a letter with its marks as one glyph; pdfium reports it as a base and marks
     * on one origin and splits the drawn extent between them. That origin says which base a mark
     * belongs to (geometry would pick a neighbour), and the cluster's ink is the union.
     */
    private fun owners(marks: List<Glyph>, bases: List<Glyph>, lineSize: Float): Map<Glyph, Glyph> {
        val owner = HashMap<Glyph, Glyph>()
        for (mark in marks) {
            val same = bases.filter {
                abs(it.origin - mark.origin) < ORIGIN_TOLERANCE_PT && abs(it.v - mark.v) < lineSize * OFF_BASELINE
            }
            val base = same.minByOrNull { abs((it.u0 + it.u1) / 2 - (mark.u0 + mark.u1) / 2) } ?: continue
            owner[mark] = base
            base.u0 = min(base.u0, mark.u0)
            base.u1 = max(base.u1, mark.u1)
        }
        return owner
    }

    /** Each mark's text on the base it belongs to: its [owner], else the base it sits over or nearest. */
    private fun attach(marks: List<Glyph>, owner: Map<Glyph, Glyph>, visual: List<Glyph>): Map<Glyph, StringBuilder> {
        val attached = HashMap<Glyph, StringBuilder>()
        for (mark in marks) {
            val centre = (mark.u0 + mark.u1) / 2
            val base = owner[mark] ?: visual.minBy { b ->
                if (centre in b.u0..b.u1) 0f else min(abs(centre - b.u0), abs(centre - b.u1))
            }
            attached.getOrPut(base) { StringBuilder() }.append(mark.text)
        }
        return attached
    }

    /** [visual] cut where a gap is wider than a word space (wider still with no space drawn there). */
    private fun groups(visual: List<Glyph>, explicit: BooleanArray, lineSize: Float): List<List<Glyph>> {
        val groups = ArrayList<MutableList<Glyph>>()
        var current = ArrayList<Glyph>()
        for ((index, glyph) in visual.withIndex()) {
            val prior = visual.getOrNull(index - 1)
            val size = max(lineSize, glyph.style.sizePt)
            val limit = size * (if (explicit[index]) GAP_WITH_SPACE else GAP_WITHOUT_SPACE)
            if (prior != null && glyph.u0 - prior.u1 > limit) {
                groups += current
                current = ArrayList()
            }
            current += glyph
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /**
     * Which gaps between neighbouring glyphs (in visual order) hold a space character the PDF drew.
     * A space belongs to the one gap nearest its position, so a non-joining letter's own side
     * bearing next to the word gap isn't taken for a second space.
     */
    private fun explicitSpaces(
        visual: List<Glyph>,
        spaces: List<Space>,
        marks: List<Glyph>,
        size: Float
    ): BooleanArray {
        val explicit = BooleanArray(visual.size)
        for (space in spaces) {
            val best = nearestGap(visual, space.u)?.takeIf { (_, distance) -> distance <= size * SPACE_REACH }?.first
            // A space pdfium made up between two letters with a mark in the gap is the mark's own
            // box (a letter drawn as its own text object), not a word break.
            val markInGap = best != null && space.generated && marks.any { mark ->
                (mark.u0 + mark.u1) / 2 in
                    visual[best - 1].u1 - size * GENERATED_SPACE_GAP..visual[best].u0 + size * GENERATED_SPACE_GAP
            }
            if (best != null && !markInGap) explicit[best] = true
        }
        return explicit
    }

    /** The gap (index of the glyph after it) nearest [u] and its distance; null for NaN or a lone glyph. */
    private fun nearestGap(visual: List<Glyph>, u: Float): Pair<Int, Float>? {
        if (u.isNaN()) return null
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 1 until visual.size) {
            val lo = min(visual[i - 1].u1, visual[i].u0)
            val hi = max(visual[i - 1].u1, visual[i].u0)
            val distance = if (u in lo..hi) 0f else min(abs(u - lo), abs(u - hi))
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (best < 0) null else best to bestDistance
    }

    private fun segment(
        group: List<Glyph>,
        attached: Map<Glyph, StringBuilder>,
        spaceBefore: Map<Glyph, Boolean>,
        rtl: Boolean,
        angle: Int
    ): Segment {
        val size = ExactGlyphs.dominantSize(group)
        val lineV = group.filter { it.style.sizePt == size }.map { it.v }.average().toFloat()
        val items = items(group, attached, spaceBefore, size)
        val reading = ExactBidi.reading(IntArray(items.size) { items[it].code }, rtl)
        val builder = TokenBuilder(size, lineV)
        var pendingSpace = false
        var pendingGap = 0f
        for (k in items.indices) {
            val item = items[reading.order[k]]
            val glyph = item.glyph
            if (glyph == null) {
                builder.flush()
                pendingSpace = true
                pendingGap = item.u1 - item.u0
            } else {
                val open = builder.openStyle
                if (open != null && glyph.style != open) builder.flush()
                if (builder.openStyle == null) {
                    builder.start(glyph, item.u0, item.u1, pendingSpace, pendingGap)
                    pendingSpace = false
                }
                // A mirrored bracket read in an odd-level run is written as the bracket the reader typed.
                val code = reading.codes[k]
                val single = item.text.codePointCount(0, item.text.length) == 1
                builder.append(
                    if (code != item.code && single) String(Character.toChars(code)) else item.text,
                    item.u0,
                    item.u1
                )
            }
        }
        builder.flush()
        return Segment(builder.tokens, lineV, size, rtl, angle, bidi = true)
    }

    /** [group] in visual order with the spaces its gaps hold: a gap with no space drawn must be wide to count. */
    private fun items(
        group: List<Glyph>,
        attached: Map<Glyph, StringBuilder>,
        spaceBefore: Map<Glyph, Boolean>,
        size: Float
    ): List<BidiItem> {
        val items = ArrayList<BidiItem>()
        for ((index, glyph) in group.withIndex()) {
            val prior = group.getOrNull(index - 1)
            if (prior != null) {
                val gap = glyph.u0 - prior.u1
                val s = max(size, glyph.style.sizePt)
                // A gap with no space character in it must be wide: after a letter that doesn't join,
                // an Arabic word shows 0.25 em of side bearing between its own letters. Where the
                // script changes (a number beside Arabic) a smaller gap is already a space.
                val alone = if (prior.style.arabic == glyph.style.arabic) SPACE_GAP_ALONE else SPACE_GAP_SCRIPT_CHANGE
                if (gap > s * (if (spaceBefore[glyph] == true) SPACE_GAP_WITH_EVIDENCE else alone)) {
                    items += BidiItem(null, " ", ' '.code, prior.u1, glyph.u0)
                }
            }
            val normalised = java.text.Normalizer.normalize(glyph.text, java.text.Normalizer.Form.NFKC)
            items += BidiItem(
                glyph,
                normalised + (attached[glyph] ?: ""),
                normalised.codePointAt(0),
                glyph.u0,
                glyph.u1
            )
        }
        return items
    }

    private const val GAP_WITH_SPACE = 2.0f
    private const val GAP_WITHOUT_SPACE = 0.8f
    private const val OFF_BASELINE = 0.1f
    private const val SPACE_BASELINE = 9f
    private const val SPACE_REACH = 0.15f
    private const val SPACE_GAP_WITH_EVIDENCE = 0.03f
    private const val ORIGIN_TOLERANCE_PT = 0.02f
    private const val GENERATED_SPACE_GAP = 0.1f
    private const val SPACE_GAP_ALONE = 0.25f
    private const val SPACE_GAP_SCRIPT_CHANGE = 0.2f
}
