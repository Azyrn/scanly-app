package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import kotlin.math.floor

/** A4, in points, with Excel's default "Normal" margins (0.7" sides, 0.75" top and bottom). */
internal class PageFrame(val orientation: PageOrientation) {
    val width: Float = if (orientation == PageOrientation.PORTRAIT) A4_SHORT else A4_LONG
    val height: Float = if (orientation == PageOrientation.PORTRAIT) A4_LONG else A4_SHORT
    val marginX: Float = SIDE_MARGIN
    val marginY: Float = TOP_MARGIN
    val printableWidth: Float get() = width - 2 * marginX
    val printableHeight: Float get() = height - 2 * marginY

    private companion object {
        const val A4_SHORT = 595f
        const val A4_LONG = 842f
        const val SIDE_MARGIN = 50.4f
        const val TOP_MARGIN = 54f
    }
}

/** One printed page of a sheet: rows [rowFrom, rowTo) and columns [colFrom, colTo), as layout indexes. */
internal data class PageSlice(val rowFrom: Int, val rowTo: Int, val colFrom: Int, val colTo: Int)

/**
 * A sheet's printable range measured in points. Indexes are relative to [firstRow]/[firstCol]:
 * row i spans `rowTops[i]..rowTops[i + 1]`, so a hidden row is simply zero points tall.
 */
internal class SheetLayout(
    val sheet: XlsxSheet,
    val firstRow: Int,
    val firstCol: Int,
    val rowTops: FloatArray,
    val colLefts: FloatArray,
    val frame: PageFrame,
    val scale: Float,
    val slices: List<PageSlice>
) {
    private val rowCells = HashMap<Int, IntRange>()
    private val mergeAnchors = HashMap<Long, CellRange>()
    private val mergeCovered = HashSet<Long>()

    init {
        var start = 0
        while (start < sheet.cells.size) {
            val row = sheet.cells[start].row
            var end = start
            while (end + 1 < sheet.cells.size && sheet.cells[end + 1].row == row) end++
            rowCells[row] = start..end
            start = end + 1
        }
        var budget = MAX_MERGED_CELLS
        for (merge in sheet.merges) {
            val area = (merge.lastRow - merge.firstRow + 1).toLong() * (merge.lastCol - merge.firstCol + 1)
            if (area <= 1 || area > budget) continue
            budget -= area
            mergeAnchors[key(merge.firstRow, merge.firstCol)] = merge
            for (r in merge.firstRow..merge.lastRow) {
                for (c in merge.firstCol..merge.lastCol) {
                    if (r != merge.firstRow || c != merge.firstCol) mergeCovered += key(r, c)
                }
            }
        }
    }

    val rowCount: Int get() = rowTops.size - 1
    val colCount: Int get() = colLefts.size - 1

    /** The sheet's cells in [row] (absolute), in column order. */
    fun cellsIn(row: Int): List<XlsxCell> =
        rowCells[row]?.let { sheet.cells.subList(it.first, it.last + 1) }.orEmpty()

    fun mergeAt(row: Int, col: Int): CellRange? = mergeAnchors[key(row, col)]

    fun isCovered(row: Int, col: Int): Boolean = key(row, col) in mergeCovered

    val merges: Collection<CellRange> get() = mergeAnchors.values

    /** Left edge of absolute column [col], clamped into the laid-out range. */
    fun xOf(col: Int): Float = colLefts[(col - firstCol).coerceIn(0, colCount)]

    fun yOf(row: Int): Float = rowTops[(row - firstRow).coerceIn(0, rowCount)]

    private fun key(row: Int, col: Int): Long = (row.toLong() shl COLUMN_BITS) or col.toLong()

    private companion object {
        const val COLUMN_BITS = 15

        /** Merges are indexed cell by cell; past this many covered cells the rest print unmerged. */
        const val MAX_MERGED_CELLS = 1_000_000L
    }
}

/**
 * Sizes and paginates a sheet the way Excel prints it: only the used range, columns that don't fit
 * continue on later pages, pages ordered down then over, nothing repeated.
 */
internal object XlsxLayout {

    /**
     * [measureWrapped] gives the height a wrapped cell's text needs at a width, both in points; it
     * is how rows the author didn't size grow to show their text, as Excel's autofit does on open.
     * Null for a sheet with nothing to print.
     */
    fun layout(sheet: XlsxSheet, measureWrapped: (XlsxCell, Float) -> Float): SheetLayout? {
        val range = usedRange(sheet) ?: return null
        val colLefts = edges(range.lastCol - range.firstCol + 1) {
            columnWidthPt(sheet.columns, range.firstCol + it)
        }
        val heights = FloatArray(range.lastRow - range.firstRow + 1) { rowHeightPt(sheet.rows, range.firstRow + it) }
        autofitRows(sheet, range, colLefts, heights, measureWrapped)
        val rowTops = edges(heights.size) { heights[it] }
        val totalWidth = colLefts.last()
        val totalHeight = rowTops.last()
        val setup = sheet.setup
        val orientation = setup.orientation
            ?: if (totalWidth > PageFrame(PageOrientation.PORTRAIT).printableWidth) {
                PageOrientation.LANDSCAPE
            } else {
                PageOrientation.PORTRAIT
            }
        val frame = PageFrame(orientation)
        val scale = pageScale(setup, frame, totalWidth, totalHeight)
        val colBands = bands(colLefts, frame.printableWidth / scale)
        val rowBands = bands(rowTops, frame.printableHeight / scale)
        val slices = colBands.flatMap { cols ->
            rowBands.map { rows -> PageSlice(rows.first, rows.second, cols.first, cols.second) }
        }
        return SheetLayout(sheet, range.firstRow, range.firstCol, rowTops, colLefts, frame, scale, slices)
    }

    /** The print scale: the sheet's own percentage, or whatever fits it to the page's width or height. */
    private fun pageScale(setup: SheetPageSetup, frame: PageFrame, totalWidth: Float, totalHeight: Float): Float {
        var scale = if (setup.fitToWidth || setup.fitToHeight) 1f else setup.scalePercent / PERCENT
        if (setup.fitToWidth && totalWidth > 0) scale = minOf(scale, frame.printableWidth / totalWidth)
        if (setup.fitToHeight && totalHeight > 0) scale = minOf(scale, frame.printableHeight / totalHeight)
        return scale.coerceAtLeast(MIN_SCALE)
    }

    /** Excel stores widths in digit widths of the default font, padding included; 7 px per digit at 96 dpi. */
    fun columnWidthPt(columns: ColumnSpec, col: Int): Float {
        if (col in columns.hidden) return 0f
        val chars = columns.widths[col] ?: columns.defaultWidthChars
        val pixels = if (chars != null) {
            floor(chars * DIGIT_PX + ROUNDING_PX)
        } else {
            columns.baseWidthChars * DIGIT_PX + DEFAULT_PADDING_PX
        }
        return pixels * PT_PER_PX
    }

    private fun rowHeightPt(rows: RowSpec, row: Int): Float =
        if (row in rows.hidden) 0f else rows.heightsPt[row] ?: rows.defaultHeightPt

    private fun usedRange(sheet: XlsxSheet): CellRange? {
        if (sheet.cells.isEmpty()) return null
        var firstRow = Int.MAX_VALUE
        var firstCol = Int.MAX_VALUE
        var lastRow = -1
        var lastCol = -1
        sheet.cells.forEach {
            firstRow = minOf(firstRow, it.row); lastRow = maxOf(lastRow, it.row)
            firstCol = minOf(firstCol, it.col); lastCol = maxOf(lastCol, it.col)
        }
        // A merge only widens the range when its anchor holds something to show.
        val anchors = sheet.cells.mapTo(HashSet()) { it.row.toLong() shl ROW_SHIFT or it.col.toLong() }
        sheet.merges.filter { (it.firstRow.toLong() shl ROW_SHIFT or it.firstCol.toLong()) in anchors }
            .forEach {
                lastRow = maxOf(lastRow, it.lastRow)
                lastCol = maxOf(lastCol, it.lastCol)
            }
        return CellRange(firstRow, firstCol, lastRow, lastCol)
    }

    private fun autofitRows(
        sheet: XlsxSheet,
        range: CellRange,
        colLefts: FloatArray,
        heights: FloatArray,
        measureWrapped: (XlsxCell, Float) -> Float
    ) {
        val merged = sheet.merges.filter { it.firstRow != it.lastRow || it.firstCol != it.lastCol }
        val mergedAnchors = merged.mapTo(HashSet()) { it.firstRow.toLong() shl ROW_SHIFT or it.firstCol.toLong() }
        for (cell in sheet.cells) {
            val index = cell.row - range.firstRow
            // Sized rows, hidden rows, empty cells and merge anchors keep the height they have.
            val fixed = cell.row in sheet.rows.custom || heights[index] == 0f || cell.text.isEmpty()
            val mergeAnchor = (cell.row.toLong() shl ROW_SHIFT or cell.col.toLong()) in mergedAnchors
            if (fixed || mergeAnchor) continue
            val width = colLefts[cell.col - range.firstCol + 1] - colLefts[cell.col - range.firstCol]
            heights[index] = maxOf(heights[index], neededHeight(cell, width, measureWrapped).coerceAtMost(MAX_ROW_PT))
        }
    }

    /** The row height [cell] needs: its wrapped text at [width], or one line of its font. */
    private fun neededHeight(cell: XlsxCell, width: Float, measureWrapped: (XlsxCell, Float) -> Float): Float =
        if (cell.style.wrap && width > 0) {
            measureWrapped(cell, width) + 2 * CELL_PADDING_PT
        } else {
            cell.style.font.sizePt * LINE_HEIGHT
        }

    private inline fun edges(count: Int, size: (Int) -> Float): FloatArray {
        val edges = FloatArray(count + 1)
        for (i in 0 until count) edges[i + 1] = edges[i] + size(i)
        return edges
    }

    /** Greedy page bands over [edges]; an item larger than a page gets a page to itself, clipped. */
    private fun bands(edges: FloatArray, capacity: Float): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val count = edges.size - 1
        var start = 0
        while (start < count) {
            var end = start + 1
            while (end < count && edges[end + 1] - edges[start] <= capacity + EPSILON) end++
            result += start to end
            start = end
        }
        return result
    }

    const val CELL_PADDING_PT = 2f
    private const val LINE_HEIGHT = 1.3f
    private const val MAX_ROW_PT = 409f
    private const val DIGIT_PX = 7f
    private const val ROUNDING_PX = 18f / 256f * DIGIT_PX
    private const val DEFAULT_PADDING_PX = 8
    private const val PT_PER_PX = 0.75f
    private const val PERCENT = 100f
    private const val MIN_SCALE = 0.1f
    private const val EPSILON = 0.01f
    private const val ROW_SHIFT = 15
}
