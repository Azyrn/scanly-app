package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.io.File
import kotlin.math.abs

/**
 * Tables that run on over several pages. A PDF has no notion of it: each page draws its own piece,
 * often with the header rows repeated. A piece continues the one before when it is the first table
 * of its page, the one before was the last of its page, and both have the same column edges, with no
 * more than a page's margin text (running header, page number, a short note) between them.
 */
internal object ExactTablePages {

    /** Lines of text a page may have between a piece and the page edge: running header, footer, a note. */
    private const val MARGIN_LINES = 3

    /** The page's lowest table, when it may run on to the next page. */
    fun open(layout: ExactPageLayout): ExactTable? {
        val table = layout.tables.maxByOrNull { it.box.bottom } ?: return null
        val below = layout.blocks.filter { top(it, layout.heightPt) >= table.box.bottom }
        return table.takeIf { below.sumOf { it.lines.size } <= MARGIN_LINES }
    }

    /** [next]'s first table when it continues [open], else null. */
    fun continuation(open: ExactTable, next: ExactPageLayout): ExactTable? {
        val table = next.tables.minByOrNull { it.box.top } ?: return null
        if (table.rtl != open.rtl || !sameEdges(open, table)) return null
        val above = next.blocks.filter { top(it, next.heightPt) < table.box.top }
        return table.takeIf { above.sumOf { it.lines.size } <= MARGIN_LINES || repeatedRows(open, table) > 0 }
    }

    /**
     * How many rows at the top of [piece] repeat the header rows at the top of [first]: the same text
     * in the same cells, as Word draws a header row again on every page of the table.
     */
    fun repeatedRows(first: ExactTable, piece: ExactTable): Int {
        val limit = minOf(first.rows.size - 1, piece.rows.size - 1)
        var count = 0
        while (count < limit && texts(first.rows[count]) == texts(piece.rows[count]) &&
            texts(piece.rows[count]).any { it.isNotBlank() }
        ) {
            count++
        }
        return count
    }

    private fun texts(row: ExactTableRow): List<String> = row.cells.map { it.text.trim() }

    private fun sameEdges(a: ExactTable, b: ExactTable): Boolean {
        val ea = edges(a)
        val eb = edges(b)
        return ea.size == eb.size && ea.indices.all { abs(ea[it] - eb[it]) <= ExactRules.SNAP_PT }
    }

    private fun edges(table: ExactTable): List<Float> =
        table.columnWidthsPt.runningFold(table.box.left) { left, width -> left + width }

    private fun top(block: Block, pageHeightPt: Float): Float =
        pageHeightPt - block.lines.first().baselineV - block.pitchPt * ExactLayout.BASELINE_RATIO
}

/** A page as the Word writer receives it, held while a table on it may run on. */
internal class HeldPage(
    val widthPt: Float,
    val heightPt: Float,
    val layout: ExactPageLayout?,
    val background: File?,
    val pictures: List<PagePicture>
) {
    /** The layout whose tables can flow: not a scan's text, which sits behind the page picture. */
    val flowing: ExactPageLayout? get() = layout?.takeUnless { it.behindPicture }

    /** A tagged PDF's running text on this page ([ExactParagraphs]); null: the page is written as placed text. */
    val paras: PageParas? by lazy { flowing?.let(ExactParagraphs::of) }
}

/** Consecutive pages that one table runs over, each with its piece of the table. */
internal class PageRun {
    val pages = ArrayList<HeldPage>()
    val pieces = ArrayList<ExactTable?>()
    private var open: ExactTable? = null

    /** The run's table can go on to another page. */
    val isOpen: Boolean get() = open != null

    /** [page]'s piece of the run's table, when the page continues it. */
    fun continuation(page: HeldPage): ExactTable? {
        val last = pages.lastOrNull() ?: return null
        val layout = page.flowing ?: return null
        val open = open?.takeIf { last.widthPt == page.widthPt && last.heightPt == page.heightPt } ?: return null
        return ExactTablePages.continuation(open, layout)
    }

    /** Adds [page] with its [piece] (null: the page starts a run of its own). */
    fun add(page: HeldPage, piece: ExactTable?) {
        val lowest = page.flowing?.let(ExactTablePages::open)
        pages += page
        pieces += piece ?: lowest
        // A run goes on only through a page whose continuing piece is also its lowest table.
        open = lowest?.takeIf { piece == null || it === piece }
    }

    fun clear() {
        pages.clear()
        pieces.clear()
        open = null
    }
}
