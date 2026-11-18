package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.roundToInt

/**
 * A table that runs on over several pages ([ExactTablePages]), written as one Word table in the body
 * flow so it can be edited as one: a text box cannot run on to another page. Its pages become one
 * section whose top margin is where the pieces start on the pages after the first, and whose bottom
 * margin leaves just the room the last piece needs. The first page's other content sits in a spacer
 * paragraph as tall as the gap above the table; each later page's content is anchored in its first
 * row, placed on the page (layoutInCell off). Header rows that the pieces repeat are written once and
 * marked as header rows, so Word and LibreOffice repeat them; rows never split, and the last row of
 * each piece reaches the foot of the body, so the next row starts the next page as it did in the PDF.
 */
internal class ExactDocxTableRun(
    private val writer: ExactDocxWriter,
    private val pages: List<HeldPage>,
    private val pieces: List<ExactTable>
) {
    private val header = pieces.drop(1).minOf { ExactTablePages.repeatedRows(pieces.first(), it) }
    private val geometries = pieces.map(::tableGeometry)
    private val widths = onTwipGrid(geometries.first().widthsPt)

    fun write(widthPt: Float, heightPt: Float) {
        val first = pieces.first()
        val topPt = geometries.minOf { it.top }.coerceAtLeast(0f)
        val bottomPt = geometries.maxOf { it.top + it.heightsPt.sum() } + FOOT_ROOM_PT
        val bodyBottom = (bottomPt * TWIPS).roundToInt().coerceAtMost((heightPt * TWIPS).roundToInt())
        val spacer = ((geometries.first().top - topPt) * TWIPS).roundToInt().coerceAtLeast(1)
        val xml = StringBuilder("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"$spacer\" ")
            .append("w:lineRule=\"exact\"/></w:pPr>").append(writer.pageContent(pages[0], pieces[0])).append("</w:p>")
        xml.append(tableProperties(if (first.rtl) widths.reversed() else widths, first.rtl, indent(widthPt)))
        // Rows after the first page start under the repeated header rows, at the top margin.
        val restart = (topPt * TWIPS).roundToInt() + onTwipGrid(geometries.first().heightsPt).take(header).sum()
        pieces.indices.forEach { k -> xml.append(pieceRows(k, if (k == 0) null else restart, bodyBottom)) }
        // Block content ends in a paragraph, and so must the body.
        xml.append("</w:tbl><w:p><w:pPr>$TINY_LINE</w:pPr></w:p>")
        writer.writeBody(xml.toString())
        val bottomMargin = (heightPt * TWIPS).roundToInt() - bodyBottom
        writer.endSection(
            sectionProperties(widthPt, heightPt, topPt, bottomMargin / TWIPS, headers = writer.extras.plainHeaders())
        )
        writer.pagesWritten += pages.size
    }

    /**
     * The rows of piece [k], starting at [startTwips] down the page (null: where the piece starts in
     * the PDF). Its last row reaches to half the next piece's first row above [bodyBottom], so that
     * row cannot fit and starts the next page.
     */
    private fun pieceRows(k: Int, startTwips: Int?, bodyBottom: Int): String {
        val piece = pieces[k]
        val geometry = geometries[k]
        val from = if (k == 0) 0 else header
        val heights = onTwipGrid(geometry.heightsPt).toMutableList()
        if (k < pieces.lastIndex) {
            val start = startTwips ?: (geometry.top * TWIPS).roundToInt()
            val next = onTwipGrid(geometries[k + 1].heightsPt)[header]
            val used = start + heights.subList(from, heights.lastIndex).sum()
            heights[heights.lastIndex] = maxOf(heights.last(), bodyBottom - used - next / 2)
        }
        val xml = StringBuilder()
        for (r in from until piece.rows.size) {
            val properties = ROW_KEEP + if (k == 0 && r < header) ROW_HEADER else ""
            val anchors = if (k > 0 && r == from) writer.pageContent(pages[k], pieces[k], inCell = true) else ""
            xml.append(writer.tableXml.row(piece, geometry, widths, r, heights[r], properties, anchors))
        }
        return xml.toString()
    }

    /** The table's indent from its leading edge: the left, or the right for a right-to-left table. */
    private fun indent(widthPt: Float): Int {
        val first = pieces.first()
        if (!first.rtl) return (first.box.left * TWIPS).roundToInt()
        val left = first.box.left - geometries.first().slackPt
        return ((widthPt - left) * TWIPS).roundToInt() - widths.sum()
    }

    private companion object {
        /** Room under the table for the paragraphs that close the body and the section (1 pt each). */
        const val FOOT_ROOM_PT = 3f
        const val ROW_KEEP = "<w:cantSplit/>"
        const val ROW_HEADER = "<w:tblHeader/>"
    }
}
