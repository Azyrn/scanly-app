package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.graphics.Canvas

/** A page as its header and footer see it: its box, its section's setup, the number it shows. */
internal class PageFrame(val box: PageBox, val setup: PageSetup, val number: Int, val first: Boolean)

/** Where a page's body runs, top to bottom, in points. */
internal class BodyBand(val top: Float, val bottom: Float)

/**
 * Running headers and footers. Each page takes its section's header and footer of one kind: the
 * section's "first" on its first page when the section has a title page (w:titlePg), "even" on
 * even-numbered pages when the document has evenAndOddHeaders, "default" otherwise; a kind the
 * section does not name is none (ISO/IEC 29500-1 §17.10.1, §17.10.5).
 *
 * The header's top sits [PageSetup.headerTwips] below the page's top edge and the footer's last
 * line ends [PageSetup.footerTwips] above its bottom edge. The body runs between the top and bottom
 * margins unless a header or footer needs more room: then it starts under the header (the larger of
 * the top margin and the header's bottom) and ends above the footer. A negative margin keeps the
 * body where it is and lets the header run into it. A header or footer is never given more than
 * [MAX_SHARE] of the page.
 */
internal class PageDecor(
    private val document: DocxDocument,
    private val tables: TableLayout,
    private val floats: FloatingPictures
) {

    fun draw(frame: PageFrame, canvas: Canvas): BodyBand {
        val setup = frame.setup
        val kind = kindOf(frame)
        val headerEnd = drawHeader(setup.headers[kind], frame, canvas)
        val footerStart = drawFooter(setup.footers[kind], frame, canvas)
        val top = if (headerEnd == null || setup.fixedTop) frame.box.top else maxOf(frame.box.top, headerEnd)
        val bottom = if (footerStart == null || setup.fixedBottom) {
            frame.box.bottom
        } else {
            minOf(frame.box.bottom, footerStart)
        }
        return BodyBand(top, bottom.coerceAtLeast(top + MIN_BODY))
    }

    private fun kindOf(frame: PageFrame): HeaderKind = when {
        frame.setup.titlePage && frame.first -> HeaderKind.FIRST
        document.evenAndOddHeaders && frame.number % 2 == 0 -> HeaderKind.EVEN
        else -> HeaderKind.DEFAULT
    }

    /** Draws header part [id] from its distance below the page's top; returns where it ends, null for none. */
    private fun drawHeader(id: String?, frame: PageFrame, canvas: Canvas): Float? {
        val items = items(id, frame) ?: return null
        val box = frame.box
        val start = frame.setup.headerTwips / TWIPS_PER_POINT
        floats.draw(canvas, document.parts[id].orEmpty(), box, start)
        return start + drawItems(canvas, items, box.left, start, box.height * MAX_SHARE)
    }

    /** Draws footer part [id] so it ends its distance above the page's bottom; returns where it starts. */
    private fun drawFooter(id: String?, frame: PageFrame, canvas: Canvas): Float? {
        val items = items(id, frame) ?: return null
        val box = frame.box
        val height = items.sumOf { it.height.toDouble() }.toFloat().coerceAtMost(box.height * MAX_SHARE)
        val start = box.height - frame.setup.footerTwips / TWIPS_PER_POINT - height
        floats.draw(canvas, document.parts[id].orEmpty(), box, start)
        drawItems(canvas, items, box.left, start, height)
        return start
    }

    /** The part's blocks with this page's number, measured to the text width; null when there are none. */
    private fun items(id: String?, frame: PageFrame): List<CellItem>? {
        val blocks = document.parts[id ?: return null]?.takeIf { it.isNotEmpty() } ?: return null
        val numbered = numbered(blocks, frame.number.toString())
        return tables.measure(numbered, frame.box.contentWidth, frame.box.height * MAX_SHARE)
    }

    /** Draws [items] from [top] down, no further than [limit] below it; returns the height drawn. */
    private fun drawItems(canvas: Canvas, items: List<CellItem>, left: Float, top: Float, limit: Float): Float {
        var y = top
        for (item in items) {
            if (y + item.height > top + limit) break
            item.draw(canvas, left, y)
            y += item.height
        }
        return y - top
    }

    private companion object {
        /** Word cuts a header or footer that would leave the body no room at about 0.4 of the page. */
        const val MAX_SHARE = 0.4f

        /** However tall the header and footer, the body keeps room for a line. */
        const val MIN_BODY = 12f
    }
}

/** [blocks] with every PAGE field showing [number]. */
internal fun numbered(blocks: List<DocxBlock>, number: String): List<DocxBlock> =
    mapInlines(blocks) { if (it is DocxPageField) DocxText(number, it.style) else it }

/** [blocks] with [change] applied to every inline, inside tables too; untouched paragraphs stay the same objects. */
internal fun mapInlines(
    blocks: List<DocxBlock>,
    change: (DocxInline) -> DocxInline
): List<DocxBlock> = blocks.map { block ->
    when (block) {
        is DocxParagraph -> {
            val inlines = block.inlines.map(change)
            if (inlines.indices.all { inlines[it] === block.inlines[it] }) block else block.copy(inlines = inlines)
        }
        is DocxTable -> block.copy(
            rows = block.rows.map { row ->
                row.copy(cells = row.cells.map { cell -> cell.copy(blocks = mapInlines(cell.blocks, change)) })
            }
        )
        DocxPageBreak -> block
    }
}

/** Every footnote reference inside [blocks] (table cells included), in order. */
internal fun noteRefs(blocks: List<DocxBlock>): List<DocxNoteRef> = blocks.flatMap { block ->
    when (block) {
        is DocxParagraph -> block.inlines.filterIsInstance<DocxNoteRef>()
        is DocxTable -> block.rows.flatMap { row -> row.cells.flatMap { noteRefs(it.blocks) } }
        DocxPageBreak -> emptyList()
    }
}
