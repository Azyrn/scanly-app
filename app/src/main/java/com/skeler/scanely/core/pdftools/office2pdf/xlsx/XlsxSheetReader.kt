package com.skeler.scanely.core.pdftools.office2pdf.xlsx

/**
 * Streams one worksheet part into an [XlsxSheet]. Blank cells are dropped unless they carry a fill
 * or border, so a sheet styled down to row a million costs nothing; past [MAX_CELLS] the sheet is
 * cut at the last whole row and the cut is reported, rather than running a phone out of memory.
 */
internal class XlsxSheetReader(
    private val name: String,
    private val sharedStrings: List<String>,
    private val styles: XlsxStyles,
    private val formatter: NumberFormatter,
    private val notes: XlsxNotes,
    private val checkCancel: () -> Unit
) {
    private val cells = ArrayList<XlsxCell>()
    private val columnWidths = HashMap<Int, Float>()
    private val hiddenColumns = HashSet<Int>()
    private val rowHeights = HashMap<Int, Float>()
    private val hiddenRows = HashSet<Int>()
    private val customRows = HashSet<Int>()
    private val merges = mutableListOf<CellRange>()
    private var defaultColumnWidth: Float? = null
    private var baseColumnWidth = DEFAULT_BASE_WIDTH
    private var defaultRowHeight = DEFAULT_ROW_HEIGHT_PT
    private var showGridLines = true
    private var setup = SheetPageSetup()
    private var fitToPage = false
    private var row = -1
    private var col = -1
    private var rowsSeen = 0
    private var cutAfterRow: Int? = null

    /** The `r:id` of the sheet's drawing part, if it has one. */
    var drawingId: String? = null
        private set

    fun read(cursor: XmlCursor): XlsxSheet {
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> onStart(cursor)
                XmlCursor.END_DOCUMENT -> break
            }
        }
        if (!fitToPage) setup = setup.copy(fitToWidth = false, fitToHeight = false)
        cells.sortWith(compareBy<XlsxCell> { it.row }.thenBy { it.col })
        return XlsxSheet(
            name = name,
            cells = cells,
            columns = ColumnSpec(columnWidths, hiddenColumns, defaultColumnWidth, baseColumnWidth),
            rows = RowSpec(rowHeights, hiddenRows, customRows, defaultRowHeight),
            merges = cutAfterRow?.let { cut -> merges.filter { it.firstRow < cut } } ?: merges,
            showGridLines = showGridLines,
            setup = setup,
            cutAfterRow = cutAfterRow
        )
    }

    private fun onStart(cursor: XmlCursor) {
        if (cursor.name in LAYOUT_TAGS) onLayoutStart(cursor) else onContentStart(cursor)
    }

    /** Tags that shape the printed sheet: sizes, page setup, gridlines, merges. */
    private fun onLayoutStart(cursor: XmlCursor) {
        when (cursor.name) {
            "sheetFormatPr" -> readFormat(cursor)
            "sheetView" -> showGridLines = cursor.attr("showGridLines") !in GRID_LINES_OFF
            "pageSetUpPr" -> fitToPage = cursor.flag("fitToPage")
            "pageSetup" -> readPageSetup(cursor)
            "col" -> readColumn(cursor)
            "mergeCell" -> cursor.attr("ref")?.let(::parseRange)?.let { merges += it }
        }
    }

    /** Rows, cells, and the parts that are counted or skipped rather than drawn. */
    private fun onContentStart(cursor: XmlCursor) {
        when (cursor.name) {
            "row" -> startRow(cursor)
            "c" -> if (cutAfterRow == null) readCell(cursor) else cursor.skipElement()
            "conditionalFormatting" -> {
                notes.conditionalFormats++
                cursor.skipElement()
            }
            "drawing" -> drawingId = cursor.attr("id")
            "extLst" -> cursor.skipElement()
        }
    }

    private fun readFormat(cursor: XmlCursor) {
        defaultColumnWidth = cursor.attr("defaultColWidth")?.toFloatOrNull()?.takeIf { it > 0 }
        baseColumnWidth = cursor.attr("baseColWidth")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BASE_WIDTH
        defaultRowHeight = cursor.attr("defaultRowHeight")?.toFloatOrNull()?.takeIf { it > 0 } ?: DEFAULT_ROW_HEIGHT_PT
    }

    private fun readPageSetup(cursor: XmlCursor) {
        val orientation = when (cursor.attr("orientation")) {
            "landscape" -> PageOrientation.LANDSCAPE
            "portrait" -> PageOrientation.PORTRAIT
            else -> null
        }
        // fitToWidth/Height default to 1 and only mean something with fitToPage; 0 is "automatic".
        setup = SheetPageSetup(
            orientation = orientation,
            fitToWidth = (cursor.attr("fitToWidth")?.toIntOrNull() ?: 1) == 1,
            fitToHeight = (cursor.attr("fitToHeight")?.toIntOrNull() ?: 1) == 1,
            scalePercent = cursor.attr("scale")?.toIntOrNull()?.coerceIn(MIN_SCALE, MAX_SCALE) ?: FULL_SCALE
        )
    }

    private fun readColumn(cursor: XmlCursor) {
        val min = cursor.attr("min")?.toIntOrNull() ?: return
        val max = (cursor.attr("max")?.toIntOrNull() ?: min).coerceAtMost(MAX_COLUMNS)
        if (min < 1 || max < min) return
        val width = cursor.attr("width")?.toFloatOrNull()?.takeIf { it >= 0 }
        val hidden = cursor.flag("hidden")
        for (column in min - 1 until max) {
            width?.let { columnWidths[column] = it }
            if (hidden) hiddenColumns += column
        }
    }

    private fun startRow(cursor: XmlCursor) {
        val next = cursor.attr("r")?.toIntOrNull()?.minus(1) ?: (row + 1)
        cutIfFull()
        row = next.coerceIn(0, MAX_ROWS - 1)
        col = -1
        if (++rowsSeen % CANCEL_EVERY_ROWS == 0) checkCancel()
        if (cutAfterRow != null) return
        cursor.attr("ht")?.toFloatOrNull()?.takeIf { it >= 0 }?.let { rowHeights[row] = it.coerceAtMost(MAX_ROW_PT) }
        if (cursor.flag("hidden")) hiddenRows += row
        if (cursor.flag("customHeight")) customRows += row
    }

    /** Past [MAX_CELLS], the sheet ends after the current row; the cut is noted once, at its earliest. */
    private fun cutIfFull() {
        if (cells.size >= MAX_CELLS && cutAfterRow == null) {
            cutAfterRow = row + 1
            notes.cutAtRow = notes.cutAtRow?.coerceAtMost(row + 1) ?: (row + 1)
        }
    }

    private fun readCell(cursor: XmlCursor) {
        val ref = cursor.attr("r")?.let(::parseRef)
        if (ref != null) {
            row = ref.first
            col = ref.second
        } else {
            col++
        }
        val style = styles.style(cursor.attr("s")?.toIntOrNull() ?: 0)
        val type = cursor.attr("t")
        val content = readCellContent(cursor)
        if (content.hasFormula) {
            if (content.value == null) notes.missingFormulas++ else notes.cachedFormulas++
        }
        val (text, kind) = display(type, content, style)
        if (text.isEmpty() && !style.isVisible) return
        if (col !in 0 until MAX_COLUMNS) return
        cells += XlsxCell(row, col, text, kind, style)
    }

    private class CellContent(val value: String?, val inline: String?, val hasFormula: Boolean)

    private fun readCellContent(cursor: XmlCursor): CellContent {
        var value: String? = null
        var inline: String? = null
        var formula = false
        forEachChild(cursor) { child ->
            when (child) {
                "v" -> value = cursor.readText()
                "f" -> {
                    formula = true
                    cursor.readText()
                }
                "is" -> inline = cursor.readRichText()
            }
        }
        return CellContent(value, inline, formula)
    }

    private fun display(type: String?, content: CellContent, style: CellStyle): Pair<String, CellKind> {
        val value = content.value
        return when (type) {
            "s" -> text(value?.trim()?.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty())
            "inlineStr" -> text(content.inline ?: value.orEmpty())
            "str", "d" -> text(decodeEscapes(value.orEmpty()))
            "b" -> (if (value?.trim() == "1") "TRUE" else "FALSE") to CellKind.BOOLEAN
            "e" -> value.orEmpty() to CellKind.ERROR
            else -> numberDisplay(value, style)
        }
    }

    /** A number cell in its format; a value that doesn't parse as a number shows as text. */
    private fun numberDisplay(value: String?, style: CellStyle): Pair<String, CellKind> {
        val number = value?.trim()?.toDoubleOrNull()
        return if (number == null) {
            text(value.orEmpty())
        } else {
            formatter.format(number, style.numFmtCode) to CellKind.NUMBER
        }
    }

    companion object {
        /** Cells kept per sheet; roughly what a phone lays out and prints in a reasonable time. */
        const val MAX_CELLS = 200_000
        const val MAX_COLUMNS = 16_384
        const val MAX_ROWS = 1_048_576
        private const val MAX_ROW_PT = 409f
        private const val DEFAULT_BASE_WIDTH = 8
        private const val DEFAULT_ROW_HEIGHT_PT = 15f
        private const val MIN_SCALE = 10
        private const val MAX_SCALE = 400
        private const val FULL_SCALE = 100
        private const val CANCEL_EVERY_ROWS = 512
        private const val LETTERS = 26
        private val LAYOUT_TAGS = setOf("sheetFormatPr", "sheetView", "pageSetUpPr", "pageSetup", "col", "mergeCell")
        private val GRID_LINES_OFF = setOf("0", "false")

        private fun text(value: String): Pair<String, CellKind> =
            value.take(XlsxReader.MAX_CELL_CHARS) to (if (value.isEmpty()) CellKind.BLANK else CellKind.TEXT)

        /** `"AB12"` → (11, 27), 0-based row then column; null if it isn't a cell reference. */
        fun parseRef(ref: String): Pair<Int, Int>? {
            var i = 0
            var column = 0
            val clean = ref.replace("$", "")
            while (i < clean.length && clean[i].isLetter()) {
                column = column * LETTERS + (clean[i].uppercaseChar() - 'A' + 1)
                if (column > MAX_COLUMNS) return null
                i++
            }
            val rowNumber = clean.substring(i).toIntOrNull()
            if (i == 0 || rowNumber == null || rowNumber !in 1..MAX_ROWS) return null
            return (rowNumber - 1) to (column - 1)
        }

        fun parseRange(ref: String): CellRange? {
            val first = parseRef(ref.substringBefore(':')) ?: return null
            val last = parseRef(ref.substringAfter(':', ref)) ?: return null
            return CellRange(
                minOf(first.first, last.first),
                minOf(first.second, last.second),
                maxOf(first.first, last.first),
                maxOf(first.second, last.second)
            )
        }
    }
}
