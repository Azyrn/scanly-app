package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/**
 * w:tbl. Borders are reduced to "this cell has lines or not", taken from the cell, then the
 * table, then the table style; per-side styles and widths aren't kept.
 */
internal class DocxTableReader(private val session: ReadSession) {

    fun read(parser: XmlPullParser): DocxTable {
        val saved = session.tableStyle
        val columns = ArrayList<Int>()
        val rows = ArrayList<DocxRow>()
        var bordered = false
        val margins = IntArray(2) { NO_MARGIN }
        var rtl = false
        var position: TablePosition? = null
        try {
            session.tableStyle = session.styles.table(null)
            bordered = session.styles.tableBorders(null) == true
            parser.children { name ->
                when {
                    !parser.isW() -> parser.skip()
                    name == "tblPr" -> readTableProps(parser, margins).let { props ->
                        bordered = props.bordered
                        rtl = props.rtl
                        position = props.position
                    }
                    name == "tblGrid" -> readGrid(parser, columns)
                    else -> readRows(parser, name, rows, bordered)
                }
            }
        } finally {
            session.tableStyle = saved
        }
        return DocxTable(
            columns,
            rows,
            margins[0].takeIf { it != NO_MARGIN },
            margins[1].takeIf { it != NO_MARGIN },
            rtl,
            position
        )
    }

    private class TableProps(val bordered: Boolean, val rtl: Boolean, val position: TablePosition?)

    /** A table's direction and float, as its tblPr is read. */
    private class TableShape {
        var rtl = false
        var position: TablePosition? = null
    }

    /** Sets the table style in force for the cells; answers whether it draws lines, its direction and float. */
    private fun readTableProps(parser: XmlPullParser, margins: IntArray): TableProps {
        var styleId: String? = null
        var borders: Boolean? = null
        val shape = TableShape()
        parser.children { name ->
            when (name) {
                "tblStyle" -> styleId = parser.attr("val").also { parser.skip() }
                "tblBorders" -> borders = parseBorders(parser)
                "tblCellMar" -> readCellMargins(parser, margins)
                else -> readShape(name, parser, shape)
            }
        }
        session.tableStyle = session.styles.table(styleId)
        val lines = borders ?: session.styles.tableBorders(styleId) == true
        return TableProps(lines, shape.rtl, shape.position)
    }

    private fun readShape(name: String, parser: XmlPullParser, shape: TableShape) {
        when (name) {
            "bidiVisual" -> shape.rtl = parser.toggle()
            "tblpPr" -> shape.position = readPosition(parser)
        }
        parser.skip()
    }

    /**
     * w:tblpPr as Word reads it (MS-OI29500 §2.1.162 on ISO/IEC 29500-1 §17.4.57): vertAnchor defaults to the
     * margin and horzAnchor to the text column; an element with no attributes is ignored; Word writes a 0
     * offset as 1 twip and reads 1 back as 0. A table anchored to its paragraph (vertAnchor text) flows as
     * usual; one anchored to the page or margin is placed at tblpX / tblpY.
     */
    private fun readPosition(parser: XmlPullParser): TablePosition? {
        if (parser.attributeCount == 0) return null
        val vertical = parser.attr("vertAnchor") ?: "margin"
        if (vertical != "page" && vertical != "margin") return null
        return TablePosition(
            xTwips = wordOffset(parser, "tblpX"),
            yTwips = wordOffset(parser, "tblpY"),
            fromPage = vertical == "page",
            xFromPage = parser.attr("horzAnchor") == "page",
            belowTwips = parser.intAttr("bottomFromText") ?: 0
        )
    }

    /** Word writes a 0 offset as 1 twip and reads 1 back as 0. */
    private fun wordOffset(parser: XmlPullParser, name: String): Int =
        (parser.intAttr(name) ?: 0).let { if (it == 1) 0 else it }

    /** Left and right cell margins, in twips; only dxa widths, the form Word writes. */
    private fun readCellMargins(parser: XmlPullParser, margins: IntArray) {
        parser.children { name ->
            val side = when (name) {
                "left", "start" -> 0
                "right", "end" -> 1
                else -> null
            }
            val width = parser.intAttr("w")
            val dxa = parser.attr("type").let { it == null || it == "dxa" }
            if (side != null && width != null && dxa) margins[side] = width.coerceAtLeast(0)
            parser.skip()
        }
    }

    private fun readGrid(parser: XmlPullParser, columns: MutableList<Int>) {
        parser.children { name ->
            if (name == "gridCol") columns += (parser.intAttr("w") ?: 0).coerceAtLeast(0)
            parser.skip()
        }
    }

    /** Rows may sit inside content controls or custom XML; those wrappers are see-through. */
    private fun readRows(parser: XmlPullParser, name: String, rows: MutableList<DocxRow>, bordered: Boolean) {
        when (name) {
            "tr" -> rows += readRow(parser, bordered)
            "sdt" -> session.readSdt(parser) { parser.children { readRows(parser, it, rows, bordered) } }
            "customXml" -> parser.children { readRows(parser, it, rows, bordered) }
            else -> parser.skip()
        }
    }

    private fun readRow(parser: XmlPullParser, bordered: Boolean): DocxRow {
        session.checkCancelled()
        val cells = ArrayList<DocxCell>()
        var height = 0
        var exact = false
        parser.children { name ->
            when (name) {
                "trPr" -> parser.children { child ->
                    if (child == "trHeight") {
                        height = parser.intAttr("val") ?: 0
                        exact = parser.attr("hRule") == "exact"
                    }
                    parser.skip()
                }
                else -> readCells(parser, name, cells, bordered)
            }
        }
        return DocxRow(cells, height.coerceAtLeast(0), exact)
    }

    private fun readCells(parser: XmlPullParser, name: String, cells: MutableList<DocxCell>, bordered: Boolean) {
        when (name) {
            "tc" -> cells += readCell(parser, bordered)
            "sdt" -> session.readSdt(parser) { parser.children { readCells(parser, it, cells, bordered) } }
            "customXml" -> parser.children { readCells(parser, it, cells, bordered) }
            else -> parser.skip()
        }
    }

    private fun readCell(parser: XmlPullParser, tableBordered: Boolean): DocxCell {
        val blocks = ArrayList<DocxBlock>()
        var props = CellProps()
        session.readBlocks(parser, blocks, sink = null) { name ->
            val isProps = name == "tcPr" && parser.isW()
            if (isProps) props = readCellProps(parser)
            isProps
        }
        return DocxCell(
            span = props.span.coerceIn(1, MAX_SPAN),
            merge = props.merge,
            fill = props.fill,
            bordered = props.bordered ?: tableBordered,
            blocks = blocks,
            align = props.align
        )
    }

    private class CellProps(
        val span: Int = 1,
        val merge: VerticalMerge = VerticalMerge.NONE,
        val fill: Int? = null,
        val bordered: Boolean? = null,
        val align: CellAlign = CellAlign.TOP
    )

    private fun readCellProps(parser: XmlPullParser): CellProps {
        var span = 1
        var merge = VerticalMerge.NONE
        var fill: Int? = null
        var bordered: Boolean? = null
        var align = CellAlign.TOP
        parser.children { name ->
            when (name) {
                "gridSpan" -> span = parser.intAttr("val") ?: 1
                "vMerge" -> merge = mergeOf(parser)
                "shd" -> fill = cellFill(parser)
                "tcBorders" -> bordered = parseBorders(parser)
                "vAlign" -> align = CELL_ALIGNS[parser.attr("val")] ?: CellAlign.TOP
            }
            if (name != "tcBorders") parser.skip()
        }
        return CellProps(span, merge, fill, bordered, align)
    }

    /** A bare <w:vMerge/> continues the merge above; only "restart" begins one. */
    private fun mergeOf(parser: XmlPullParser): VerticalMerge =
        if (parser.attr("val") == "restart") VerticalMerge.RESTART else VerticalMerge.CONTINUE

    private fun cellFill(parser: XmlPullParser): Int? = parseColor(parser.attr("fill"))?.takeIf { it != AUTO_COLOR }

    private companion object {
        val CELL_ALIGNS = mapOf("center" to CellAlign.CENTER, "bottom" to CellAlign.BOTTOM)

        /** Word's own limit on columns, so a hostile gridSpan can't make one cell a million wide. */
        const val MAX_SPAN = 63
        const val NO_MARGIN = -1
    }
}
