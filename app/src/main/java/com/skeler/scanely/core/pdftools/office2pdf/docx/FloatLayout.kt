package com.skeler.scanely.core.pdftools.office2pdf.docx

/**
 * What Word places on the page rather than in the text: text frames ([FramePosition]) and floating tables
 * ([TablePosition]). Each is drawn whole on the current page, and the body keeps clear of it.
 */
internal class FloatLayout(private val tables: TableLayout) {

    /**
     * A text frame drawn at its place on this page. Anchored to the text without an alignment it stands where
     * the text has reached and the text goes on below it; aligned (or anchored to the page or margin) it keeps
     * its place, and the body keeps clear of it: above it when it lies in the page's lower half, below it
     * otherwise. Returns how many paragraphs it took.
     */
    fun frame(paragraphs: List<DocxParagraph>, frame: FramePosition, flow: PageFlow): Int {
        flow.ensurePage()
        val box = flow.box
        val width = if (frame.widthTwips > 0) frame.widthTwips / TWIPS_PER_POINT else box.contentWidth
        val items = tables.measure(paragraphs, width, box.contentHeight * IMAGE_PAGE_SHARE)
        val declared = frame.heightTwips / TWIPS_PER_POINT
        val height = if (frame.exactHeight) declared else maxOf(items.totalHeight(), declared)
        val x = frameX(frame, box, width)
        val inFlow = frame.vAnchor == "text" && frame.yAlign == null
        val y = if (inFlow) flow.y + frame.yTwips / TWIPS_PER_POINT else frameY(frame, box, height)
        var at = y
        for (item in items) {
            item.draw(flow.canvas, x, at)
            at += item.height
        }
        flow.used = true
        keepClear(flow, inFlow, frame.wrapNone, y, height)
        return paragraphs.size
    }

    /** The body keeps clear of a frame at [y], [height] tall: above it in the page's lower half, else below. */
    private fun keepClear(flow: PageFlow, inFlow: Boolean, wrapNone: Boolean, y: Float, height: Float) {
        val below = inFlow || y <= flow.box.height / 2f
        if (!wrapNone && !below) flow.bottom = minOf(flow.bottom, y)
        if (!wrapNone && below) flow.y = maxOf(flow.y, y + height)
        if (inFlow || !wrapNone && below) {
            flow.pendingAfter = 0f
            flow.previous = null
        }
    }

    /**
     * A floating table anchored to the page or its margins: drawn whole at its place on this page, and the
     * body goes on below it when it reaches into the text (Word wraps text around it; a table as wide as the
     * text, as mastheads are, leaves nothing beside it).
     */
    fun table(table: DocxTable, position: TablePosition, flow: PageFlow) {
        flow.ensurePage()
        val box = flow.box
        val edges = tables.columns(table, box.contentWidth)
        val top = position.yTwips / TWIPS_PER_POINT + if (position.fromPage) 0f else box.top
        val left = position.xTwips / TWIPS_PER_POINT + if (position.xFromPage) 0f else box.left
        val fills = HashMap<Int, Int?>()
        var y = top
        table.rows.forEachIndexed { index, row ->
            val plan = tables.row(table, row, edges, box.contentHeight * IMAGE_PAGE_SHARE)
            tables.drawRow(flow.canvas, table, index, plan, RowOrigin(left, y, box.height.toFloat()), fills)
            y += plan.height
        }
        flow.used = true
        flow.y = maxOf(flow.y, y + position.belowTwips / TWIPS_PER_POINT)
        flow.pendingAfter = 0f
        flow.previous = null
    }
}

/** The paragraphs from [start] that share [frame]: Word draws consecutive ones in one frame. */
internal fun framed(blocks: List<DocxBlock>, start: Int, frame: FramePosition): List<DocxParagraph> =
    blocks.subList(start, blocks.size).takeWhile { (it as? DocxParagraph)?.style?.frame == frame }
        .filterIsInstance<DocxParagraph>()

/** A frame's left edge: from the page or the margin (Word's "text"), by alignment or offset. */
private fun frameX(frame: FramePosition, box: PageBox, width: Float): Float {
    val (left, span) = if (frame.hAnchor == "page") 0f to box.width.toFloat() else box.left to box.contentWidth
    return when (frame.xAlign) {
        "center" -> left + (span - width) / 2f
        "right", "outside" -> left + span - width
        "left", "inside" -> left
        else -> left + frame.xTwips / TWIPS_PER_POINT
    }
}

/** A placed frame's top: from the page or the margin, by alignment or offset. */
private fun frameY(frame: FramePosition, box: PageBox, height: Float): Float {
    val (top, extent) = if (frame.vAnchor == "page") 0f to box.height.toFloat() else box.top to box.contentHeight
    return when (frame.yAlign) {
        "center" -> top + (extent - height) / 2f
        "bottom", "outside" -> top + extent - height
        "top", "inside" -> top
        else -> top + frame.yTwips / TWIPS_PER_POINT
    }
}
