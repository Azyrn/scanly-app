package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.BlockKind
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBlock
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.PageLayout
import com.skeler.scanely.core.pdftools.pdf2office.ParagraphBlock
import com.skeler.scanely.core.pdftools.pdf2office.TableBlock
import com.skeler.scanely.core.pdftools.pdf2office.XlsxWriter

/**
 * PDF → Excel from the exact layout: the same tables Word and PowerPoint get (ruled grids, wrapped
 * cells as one cell, spans), a sheet per page in reading order, text lines as rows of one cell. A
 * table that runs on over several pages ([ExactTablePages]) is one table on the sheet of the page it
 * starts on, without the header rows the later pages repeat; those pages' sheets keep the rest of
 * their text.
 */
internal class ExactSheets(private val writer: XlsxWriter) {

    private class Sheet(val name: String, val blocks: MutableList<LayoutBlock>)

    /** Sheets held while a table may run on, the first holding that table. */
    private val held = ArrayList<Sheet>()
    private var first: ExactTable? = null
    private var firstBlock = -1
    private var open: ExactTable? = null

    /** Adds page [layout] as sheet [name]; an empty page adds no sheet. */
    fun add(name: String, layout: ExactPageLayout) {
        val piece = open?.let { ExactTablePages.continuation(it, layout) }
        if (piece == null) flush()
        val lowest = ExactTablePages.open(layout)
        val blocks = ArrayList<LayoutBlock>()
        layout.forEachInReadingOrder(
            onBlock = { blocks += paragraph(it, layout.heightPt) },
            onTable = { table -> table(table, piece, lowest, blocks) }
        )
        if (blocks.isNotEmpty() || piece != null) held += Sheet(name, blocks)
        open = lowest?.takeIf { (piece == null && it === first) || it === piece }
        if (open == null) flush()
    }

    /**
     * A table on the page: [piece] goes under the run's table on its first sheet; the page's
     * [lowest] table starts a run of its own when the page continues none.
     */
    private fun table(table: ExactTable, piece: ExactTable?, lowest: ExactTable?, blocks: MutableList<LayoutBlock>) {
        if (table === piece) {
            append(piece)
            return
        }
        if (table === lowest && piece == null) {
            first = table
            firstBlock = blocks.size
        }
        blocks += tableBlock(table, 0)
    }

    /** Writes every held sheet. */
    fun finish() = flush()

    private fun flush() {
        held.filter { it.blocks.isNotEmpty() }.forEach { writer.addPage(it.name, PageLayout(it.blocks, true)) }
        held.clear()
        first = null
        firstBlock = -1
        open = null
    }

    /** [piece]'s rows after the header rows it repeats, under the table on the run's first sheet. */
    private fun append(piece: ExactTable) {
        val start = first ?: return
        val sheet = held.first()
        val table = sheet.blocks[firstBlock] as TableBlock
        val more = tableBlock(piece, ExactTablePages.repeatedRows(start, piece))
        sheet.blocks[firstBlock] = table.copy(rows = table.rows + more.rows, rowBoxes = table.rowBoxes + more.rowBoxes)
    }

    private fun paragraph(block: Block, pageHeightPt: Float): ParagraphBlock {
        val lines = block.lines.map(::segmentText)
        val boxes = block.lines.map {
            val baseline = pageHeightPt - it.baselineV
            LayoutBox(it.u0, baseline - it.sizePt, it.u1, baseline)
        }
        val box = boxes.reduce(LayoutBox::union)
        return ParagraphBlock(BlockKind.BODY, lines.joinToString(" "), lines, boxes, box, block.lines.first().sizePt)
    }

    /**
     * [table] from row [from] on, a cell per column: a cell spanning columns fills the first and
     * leaves the rest empty, as does a cell merged with the one above.
     */
    private fun tableBlock(table: ExactTable, from: Int): TableBlock {
        val rows = table.rows.drop(from)
        val cells = rows.map { row ->
            row.cells.flatMap { cell ->
                val text = if (cell.merge == CellMerge.CONTINUE) "" else cell.text
                listOf(text) + List(cell.span - 1) { "" }
            }
        }
        return TableBlock(cells, rows.map { it.box }, table.box, table.fontSize, table.columnWidthsPt)
    }
}
