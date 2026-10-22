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
        try {
            session.tableStyle = session.styles.table(null)
            bordered = session.styles.tableBorders(null) == true
            parser.children { name ->
                when {
                    !parser.isW() -> parser.skip()
                    name == "tblPr" -> bordered = readTableProps(parser)
                    name == "tblGrid" -> readGrid(parser, columns)
                    else -> readRows(parser, name, rows, bordered)
                }
            }
        } finally {
            session.tableStyle = saved
        }
        return DocxTable(columns, rows)
    }

    /** Sets the table style in force for the cells and answers whether the table draws lines. */
    private fun readTableProps(parser: XmlPullParser): Boolean {
        var styleId: String? = null
        var borders: Boolean? = null
        parser.children { name ->
            when (name) {
                "tblStyle" -> styleId = parser.attr("val").also { parser.skip() }
                "tblBorders" -> borders = parseBorders(parser)
                else -> parser.skip()
            }
        }
        session.tableStyle = session.styles.table(styleId)
        return borders ?: session.styles.tableBorders(styleId) ?: false
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
            blocks = blocks
        )
    }

    private class CellProps(
        val span: Int = 1,
        val merge: VerticalMerge = VerticalMerge.NONE,
        val fill: Int? = null,
        val bordered: Boolean? = null
    )

    private fun readCellProps(parser: XmlPullParser): CellProps {
        var span = 1
        var merge = VerticalMerge.NONE
        var fill: Int? = null
        var bordered: Boolean? = null
        parser.children { name ->
            when (name) {
                "gridSpan" -> span = parser.intAttr("val") ?: 1
                // A bare <w:vMerge/> continues the merge above; only "restart" begins one.
                "vMerge" -> merge = if (parser.attr("val") == "restart") {
                    VerticalMerge.RESTART
                } else {
                    VerticalMerge.CONTINUE
                }
                "shd" -> fill = parseColor(parser.attr("fill"))?.takeIf { it != AUTO_COLOR }
                "tcBorders" -> bordered = parseBorders(parser)
            }
            if (name != "tcBorders") parser.skip()
        }
        return CellProps(span, merge, fill, bordered)
    }

    private companion object {
        /** Word's own limit on columns, so a hostile gridSpan can't make one cell a million wide. */
        const val MAX_SPAN = 63
    }
}
