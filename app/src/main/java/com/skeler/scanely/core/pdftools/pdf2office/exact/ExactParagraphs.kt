package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs

/** The segments drawn on one baseline of a paragraph, in reading order (left to right). */
internal class ParaLine(val segments: List<Segment>) {
    val baselineV: Float get() = segments.first().baselineV
    val sizePt: Float get() = segments.maxOf { it.sizePt }
    val u0: Float get() = segments.first().u0
    val u1: Float get() = segments.last().u1
}

/**
 * One paragraph of a page's running text, as the PDF's own structure names it: every line of it on
 * this page, top to bottom. [label] is a list item's marker as drawn ("3.", "(a)", a bullet), which
 * the writer turns into numbering; it is not part of [lines].
 */
internal class Para(val block: TagBlock, val lines: List<ParaLine>, val label: Segment? = null) {
    /** The exact pitch that carries every line; a single line is as tall as its largest glyph needs. */
    val pitchPt: Float = if (lines.size == 1) {
        lines[0].segments.maxOf(ExactBlocks::singleLineHeight)
    } else {
        (lines.first().baselineV - lines.last().baselineV) / (lines.size - 1)
    }

    /** Where the lines after the first start; for a single line, where its text starts. */
    val leftPt: Float = if (lines.size == 1) lines[0].u0 else lines.drop(1).minOf { it.u0 }

    /** Where the first line starts: its label when it has one. */
    val firstPt: Float get() = label?.u0 ?: lines[0].u0
    val rightPt: Float = lines.maxOf { it.u1 }

    fun topPt(pageHeightPt: Float): Float =
        pageHeightPt - lines.first().baselineV - pitchPt * ExactLayout.BASELINE_RATIO

    fun bottomPt(pageHeightPt: Float): Float =
        pageHeightPt - lines.last().baselineV + pitchPt * (1f - ExactLayout.BASELINE_RATIO)
}

/**
 * A page of a tagged PDF split into its running text ([flow]: paragraphs in reading order, each
 * below the one before) and everything else ([rest]: blocks that keep their place on the page as
 * text boxes — notes, text boxes, a second column, right-to-left and turned text). [artifacts] is the
 * text the PDF marks as no part of the document's content: running headers and footers, page numbers.
 */
internal class PageParas(val flow: List<Para>, val rest: List<Block>, val artifacts: List<Block>)

/**
 * Third stage for a tagged PDF: the page's segments grouped by the paragraph the PDF's structure tree
 * puts them in ([ExactTags]), instead of by how their lines happen to be spaced. Nothing is inferred
 * here that the tree states: which lines are one paragraph, which paragraph is a heading or a list
 * item. Geometry only decides what can flow: one column of upright left-to-right paragraphs.
 */
internal object ExactParagraphs {

    /** Null when the page has no structure to read, or its text does not flow as one column. */
    fun of(layout: ExactPageLayout): PageParas? {
        val tags = layout.tags?.let(::ExactTags) ?: return null
        if (layout.behindPicture) return null
        val segments = layout.blocks.flatMap { it.lines }
        val sorted = Sorted()
        for (segment in segments) sorted.add(segment, segment.tokens.firstNotNullOfOrNull { tags.block(it.mcid) })
        val column = column(sorted)
        // Running text that does not read as one column (a second column, a sidebar) is most of what a
        // multi-column page holds: such a page keeps every line where it was instead.
        if (column.flow.isEmpty() || column.beside > (column.flowing + column.beside) * MAX_BESIDE) return null
        return PageParas(
            column.flow,
            ExactBlocks.blocks(segments.filter { it in sorted.rest }),
            ExactBlocks.blocks(segments.filter { it in sorted.artifacts })
        )
    }

    /** The paragraphs that flow, and how many letters flow and stay beside them. */
    private class Column(val flow: List<Para>, val flowing: Int, val beside: Int)

    /** The paragraphs that read as one column, each below the one before; the others go to [Sorted.rest]. */
    private fun column(sorted: Sorted): Column {
        val flow = ArrayList<Para>()
        var flowing = 0
        var beside = 0
        for (para in sorted.candidates()) {
            val above = flow.lastOrNull()
            if (regular(para) && (above == null || below(para, above))) {
                flow += labelled(para)
                flowing += letters(para)
            } else {
                para.lines.forEach { sorted.rest += it.segments }
                beside += letters(para)
            }
        }
        return Column(flow, flowing, beside)
    }

    /** A page's segments by what the structure tree says they are. */
    private class Sorted {
        val rest = HashSet<Segment>()
        val artifacts = HashSet<Segment>()
        private val grouped = LinkedHashMap<Int, MutableList<Segment>>()
        private val blocks = HashMap<Int, TagBlock>()

        // A paragraph flows whole or not at all: one right-to-left or turned line keeps all of it in place.
        private val placed = HashSet<Int>()

        fun add(segment: Segment, block: TagBlock?) {
            if (block == null) {
                artifacts += segment
                return
            }
            if (!flows(segment, block)) placed += block.node
            blocks[block.node] = block
            grouped.getOrPut(block.node) { ArrayList() } += segment
        }

        /** The paragraphs that may flow, top to bottom; the others' segments go to [rest]. */
        fun candidates(): List<Para> {
            placed.forEach { node -> rest += grouped.remove(node).orEmpty() }
            return grouped.map { (node, own) -> Para(blocks.getValue(node), lines(own)) }
                .sortedByDescending { it.lines.first().baselineV }
        }
    }

    /** Upright left-to-right running text: a paragraph, a heading or a list item. */
    private fun flows(segment: Segment, block: TagBlock): Boolean =
        segment.angleDeg == 0 && !segment.rtl && !segment.bidi &&
            (block.kind == TagKind.BODY || block.kind == TagKind.HEADING || block.kind == TagKind.LIST)

    private fun lines(segments: List<Segment>): List<ParaLine> {
        val sorted = segments.sortedByDescending { it.baselineV }
        val lines = ArrayList<MutableList<Segment>>()
        for (segment in sorted) {
            val line = lines.lastOrNull()
            if (line != null && abs(line[0].baselineV - segment.baselineV) <= SAME_BASELINE_PT) {
                line += segment
            } else {
                lines += arrayListOf(segment)
            }
        }
        return lines.map { line -> ParaLine(line.sortedBy { it.u0 }) }
    }

    /** One exact pitch must carry the paragraph's lines, or its later lines would drift off their baselines. */
    private fun regular(para: Para): Boolean {
        if (para.lines.size == 1) return true
        val first = para.lines.first().baselineV
        val limit = para.pitchPt * PITCH_TOLERANCE
        return para.pitchPt >= para.lines.maxOf { it.sizePt } * MIN_PITCH &&
            para.lines.withIndex().all { (i, line) -> abs(first - i * para.pitchPt - line.baselineV) <= limit }
    }

    /** [para] starts under [above]'s last line: one column, read downwards. */
    private fun below(para: Para, above: Para): Boolean =
        above.lines.last().baselineV - para.lines.first().baselineV >= para.lines.first().sizePt * MIN_PITCH

    /**
     * A list item's label taken out of its first line. Word draws it as text of its own in front of the
     * item, a tab's width before it; other writers leave a space. Only what reads as a marker counts
     * ([ListLabel]), so an item whose first words are text keeps them.
     */
    private fun labelled(para: Para): Para {
        if (para.block.kind != TagKind.LIST) return para
        val first = para.lines.first()
        val head = first.segments.first()
        val text = head.tokens.joinToString("") { (if (it.spaceBefore) " " else "") + it.text }
        if (first.segments.size > 1 && ListLabel.parse(text) != null) {
            return Para(para.block, listOf(ParaLine(first.segments.drop(1))) + para.lines.drop(1), head)
        }
        val marker = head.tokens.first()
        val after = head.tokens.getOrNull(1) ?: return para
        if (!after.spaceBefore || ListLabel.parse(marker.text) == null) return para
        val label = Segment(listOf(marker), head.baselineV, head.sizePt, false, 0)
        val body = Segment(
            listOf(after.withoutSpace()) + head.tokens.drop(2),
            head.baselineV,
            head.sizePt,
            false,
            0
        )
        return Para(para.block, listOf(ParaLine(listOf(body) + first.segments.drop(1))) + para.lines.drop(1), label)
    }

    private fun Token.withoutSpace() = Token(text, style, u0, u1, false, raisePt, gapBeforePt, uncertain, mcid)

    private fun letters(para: Para): Int =
        para.lines.sumOf { line -> line.segments.sumOf { segment -> segment.tokens.sumOf { it.text.length } } }

    /** The share of a page's running text that may lie beside its one column before the page is not flowed. */
    private const val MAX_BESIDE = 0.2f
    private const val SAME_BASELINE_PT = 1f
    private const val PITCH_TOLERANCE = 0.08f
    private const val MIN_PITCH = 0.7f
}
