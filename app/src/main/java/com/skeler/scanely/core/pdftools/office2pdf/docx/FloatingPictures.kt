package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.graphics.Canvas
import android.graphics.RectF

/**
 * Floating pictures ([ImageAnchor]) drawn at their place on the page, outside the text: wp:positionH /
 * wp:positionV measured from the page, the margins or the paragraph (ISO/IEC 29500-1 §20.4.3.4-5), by an
 * offset or an alignment. Anchors this layout has no frame for (column, character, line) count from the
 * margin / paragraph, which is where they are in a one-column document.
 */
internal class FloatingPictures(private val images: DocxImages) {

    /**
     * Draws the floating pictures of [blocks] on a page [box], paragraphs starting at [top]; inside tables
     * only the ones placed on the page itself (the reader floats no other there).
     */
    fun draw(canvas: Canvas, blocks: List<DocxBlock>, box: PageBox, top: Float) {
        for (block in blocks) {
            when (block) {
                is DocxParagraph -> draw(canvas, block, box, top)
                is DocxTable -> block.rows.forEach { row -> row.cells.forEach { draw(canvas, it.blocks, box, top) } }
                DocxPageBreak -> Unit
            }
        }
    }

    /** Draws the floating pictures of [paragraph], whose top is at [top] on a page [box]. */
    fun draw(canvas: Canvas, paragraph: DocxParagraph, box: PageBox, top: Float) {
        paragraph.inlines.filterIsInstance<DocxImage>().forEach { image ->
            image.anchor?.let { draw(canvas, image, it, box, top) }
        }
    }

    private fun draw(canvas: Canvas, image: DocxImage, anchor: ImageAnchor, box: PageBox, top: Float) {
        val size = images.size(image.entry) ?: return
        val width = if (image.widthEmu > 0) image.widthEmu / EMU_PER_POINT else size.width.toFloat()
        val height = if (image.heightEmu > 0) image.heightEmu / EMU_PER_POINT else size.height.toFloat()
        val x = place(anchor.x, anchor.xAlign, width, horizontal(anchor.xFrom, box))
        val y = place(anchor.y, anchor.yAlign, height, vertical(anchor.yFrom, box, top))
        images.draw(canvas, image.entry, RectF(x, y, x + width, y + height))
    }

    /** Start and length of what an anchor counts from. */
    private class Span(val start: Float, val length: Float)

    private fun horizontal(from: String, box: PageBox): Span = when (from) {
        "page" -> Span(0f, box.width.toFloat())
        "leftMargin", "insideMargin" -> Span(0f, box.left)
        "rightMargin", "outsideMargin" -> Span(box.right, box.width - box.right)
        else -> Span(box.left, box.contentWidth)
    }

    private fun vertical(from: String, box: PageBox, top: Float): Span = when (from) {
        "page" -> Span(0f, box.height.toFloat())
        "margin" -> Span(box.top, box.contentHeight)
        "topMargin", "insideMargin" -> Span(0f, box.top)
        "bottomMargin", "outsideMargin" -> Span(box.bottom, box.height - box.bottom)
        else -> Span(top, 0f)
    }

    private fun place(offset: Long, align: String?, size: Float, span: Span): Float = when (align) {
        "left", "top", "inside" -> span.start
        "center" -> span.start + (span.length - size) / 2f
        "right", "bottom", "outside" -> span.start + span.length - size
        else -> span.start + offset / EMU_PER_POINT
    }
}
