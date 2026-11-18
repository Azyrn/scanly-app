package com.skeler.scanely.core.pdftools.office2pdf.xlsx

/**
 * The slice of a workbook that printing needs, already resolved: every colour is ARGB, every number
 * is the text Excel would show. Nothing here knows about Android, so the reader is testable on the JVM.
 */

internal enum class HorizontalAlign { GENERAL, LEFT, CENTER, RIGHT }

internal enum class VerticalAlign { TOP, CENTER, BOTTOM }

internal enum class CellKind { TEXT, NUMBER, BOOLEAN, ERROR, BLANK }

internal enum class PageOrientation { PORTRAIT, LANDSCAPE }

internal data class FontStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = DEFAULT_FONT_SIZE_PT,
    val color: Int = BLACK,
    /** The font's family name as the workbook names it ("Calibri", "Arial"); null when unnamed. */
    val name: String? = null
)

/** One edge of a cell's border, in points. */
internal data class BorderLine(val widthPt: Float, val color: Int)

internal data class Borders(
    val left: BorderLine? = null,
    val right: BorderLine? = null,
    val top: BorderLine? = null,
    val bottom: BorderLine? = null
) {
    val any: Boolean get() = left != null || right != null || top != null || bottom != null
}

/** A cellXfs entry, flattened: font, fill, borders and alignment resolved, number format kept as code. */
internal data class CellStyle(
    val font: FontStyle = FontStyle(),
    val fill: Int? = null,
    val borders: Borders = Borders(),
    val horizontal: HorizontalAlign = HorizontalAlign.GENERAL,
    val vertical: VerticalAlign = VerticalAlign.BOTTOM,
    val wrap: Boolean = false,
    val numFmtId: Int = 0,
    val numFmtCode: String = GENERAL_FORMAT
) {
    /** A blank cell is worth printing only if the reader would see something there. */
    val isVisible: Boolean get() = fill != null || borders.any
}

/** [row] and [col] are 0-based. [text] is what Excel would display, already formatted. */
internal class XlsxCell(
    val row: Int,
    val col: Int,
    val text: String,
    val kind: CellKind,
    val style: CellStyle
)

/** Inclusive, 0-based. */
internal data class CellRange(val firstRow: Int, val firstCol: Int, val lastRow: Int, val lastCol: Int) {
    fun contains(row: Int, col: Int): Boolean = row in firstRow..lastRow && col in firstCol..lastCol
}

/**
 * Column widths stay in Excel's own unit (characters of the default font's digit) until layout,
 * which owns the conversion to points.
 */
internal class ColumnSpec(
    val widths: Map<Int, Float>,
    val hidden: Set<Int>,
    val defaultWidthChars: Float?,
    val baseWidthChars: Int
)

internal class RowSpec(
    val heightsPt: Map<Int, Float>,
    val hidden: Set<Int>,
    /** Rows whose height the author fixed; only the others grow to fit wrapped text. */
    val custom: Set<Int>,
    val defaultHeightPt: Float
)

/** What the sheet's own Page Setup asked for; null orientation means "let the converter pick". */
internal data class SheetPageSetup(
    val orientation: PageOrientation? = null,
    val fitToWidth: Int = 0,
    val fitToHeight: Int = 0,
    val scalePercent: Int = 100,
    /** ECMA-376 paperSize code; 1 (Letter) is the schema default, used when the sheet names none. */
    val paperSize: Int = PaperSizes.LETTER,
    val margins: PageMargins = PageMargins.EXCEL_NORMAL
)

/** Page margins in points (the sheet stores inches). */
internal data class PageMargins(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    companion object {
        /** Excel's "Normal" margins: 0.7" left and right, 0.75" top and bottom. */
        val EXCEL_NORMAL = PageMargins(50.4f, 50.4f, 54f, 54f)
    }
}

/** Portrait page sizes in points for the ECMA-376 paperSize codes office files use (§18.3.1.63). */
internal object PaperSizes {
    const val LETTER = 1
    private val SIZES = mapOf(
        LETTER to (612f to 792f),
        3 to (792f to 1224f), // Tabloid
        5 to (612f to 1008f), // Legal
        7 to (522f to 756f), // Executive
        8 to (842f to 1191f), // A3
        9 to (595f to 842f), // A4
        11 to (420f to 595f) // A5
    )

    /** (width, height) of [code]; a code without an entry here prints on the default, Letter. */
    fun portrait(code: Int): Pair<Float, Float> = SIZES[code] ?: SIZES.getValue(LETTER)
}

internal class XlsxSheet(
    val name: String,
    /** Sorted by row, then column. */
    val cells: List<XlsxCell>,
    val columns: ColumnSpec,
    val rows: RowSpec,
    val merges: List<CellRange>,
    val showGridLines: Boolean,
    val setup: SheetPageSetup,
    /** 1-based last row kept when the sheet hit the cell cap; null when nothing was cut. */
    val cutAfterRow: Int?
)

internal data class SheetRef(val name: String, val path: String)

/**
 * What the conversion had to leave out or simplify, counted while reading so the result screen
 * can say so instead of the PDF silently differing from the workbook.
 */
internal class XlsxNotes {
    var charts = 0
    var pictures = 0
    var hiddenSheets = 0
    var cachedFormulas = 0
    var missingFormulas = 0
    var conditionalFormats = 0
    var pivotTables = 0
    var cutAtRow: Int? = null
    var pageCap: Int? = null
}

internal const val DEFAULT_FONT_SIZE_PT = 11f
internal const val BLACK = 0xFF000000.toInt()
internal const val WHITE = 0xFFFFFFFF.toInt()
internal const val GENERAL_FORMAT = "General"
