package com.skeler.scanely.core.pdftools.office2pdf.xlsx

/** The workbook's cell formats, indexed by a cell's `s` attribute. */
internal class XlsxStyles(private val xfs: List<CellStyle>) {
    fun style(index: Int): CellStyle = xfs.getOrNull(index) ?: xfs.firstOrNull() ?: DEFAULT

    private companion object {
        val DEFAULT = CellStyle()
    }
}

private class RawXf(
    val numFmtId: Int,
    val fontId: Int,
    val fillId: Int,
    val borderId: Int,
    val horizontal: HorizontalAlign,
    val vertical: VerticalAlign,
    val wrap: Boolean
)

/**
 * Reads `styles.xml` into flattened [CellStyle]s. Only `cellXfs` is read; named cell styles,
 * differential formats (conditional formatting) and table styles are skipped, since a cell's own
 * xf already carries everything Excel prints for it.
 */
internal class XlsxStylesReader(private val colors: XlsxColors) {
    private val numFmts = HashMap<Int, String>()
    private val fonts = mutableListOf<FontStyle>()
    private val fills = mutableListOf<Int?>()
    private val borders = mutableListOf<Borders>()
    private val xfs = mutableListOf<RawXf>()

    fun read(cursor: XmlCursor): XlsxStyles {
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> onStart(cursor)
                XmlCursor.END_DOCUMENT -> break
            }
        }
        return XlsxStyles(xfs.map(::resolve))
    }

    private fun onStart(cursor: XmlCursor) {
        when (cursor.name) {
            "numFmt" -> cursor.attr("numFmtId")?.toIntOrNull()?.let { id ->
                numFmts[id] = cursor.attr("formatCode").orEmpty()
            }
            "font" -> fonts += readFont(cursor)
            "fill" -> fills += readFill(cursor)
            "border" -> borders += readBorder(cursor)
            "xf" -> xfs += readXf(cursor)
            in SKIPPED -> cursor.skipElement()
        }
    }

    private fun resolve(xf: RawXf): CellStyle = CellStyle(
        font = fonts.getOrNull(xf.fontId) ?: fonts.firstOrNull() ?: FontStyle(),
        fill = fills.getOrNull(xf.fillId),
        borders = borders.getOrNull(xf.borderId) ?: Borders(),
        horizontal = xf.horizontal,
        vertical = xf.vertical,
        wrap = xf.wrap,
        numFmtId = xf.numFmtId,
        numFmtCode = numFmts[xf.numFmtId] ?: builtInFormat(xf.numFmtId) ?: GENERAL_FORMAT
    )

    private fun readFont(cursor: XmlCursor): FontStyle {
        var font = FontStyle()
        forEachChild(cursor) { name ->
            font = when (name) {
                "b" -> font.copy(bold = cursor.flag())
                "i" -> font.copy(italic = cursor.flag())
                "u" -> font.copy(underline = cursor.attr("val") != "none")
                "strike" -> font.copy(strike = cursor.flag())
                "sz" -> font.copy(sizePt = sizeOf(cursor, font.sizePt))
                "color" -> font.copy(color = colorOf(cursor))
                else -> font
            }
        }
        return font
    }

    /** Only solid fills are printed; patterns and gradients are rare in data and left blank. */
    private fun readFill(cursor: XmlCursor): Int? {
        var solid = false
        var foreground: Int? = null
        var background: Int? = null
        forEachChild(cursor) { name ->
            when (name) {
                "patternFill" -> solid = cursor.attr("patternType") == "solid"
                "fgColor" -> foreground = colors.resolve(cursor)
                "bgColor" -> background = colors.resolve(cursor)
            }
        }
        return if (solid) foreground ?: background else null
    }

    private fun readBorder(cursor: XmlCursor): Borders {
        var borders = Borders()
        var edge: String? = null
        var line: BorderLine? = null
        val start = cursor.depth
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> when (cursor.name) {
                    in EDGES -> {
                        edge = cursor.name
                        line = edgeLine(cursor)
                    }
                    "color" -> line = line?.copy(color = colorOf(cursor))
                }
                XmlCursor.END -> when {
                    cursor.depth == start -> return borders
                    cursor.name == edge -> {
                        borders = withEdge(borders, edge, line)
                        edge = null
                    }
                }
                XmlCursor.END_DOCUMENT -> throw XlsxFormatException("unclosed border")
            }
        }
    }

    /** A positive `sz` in points, or [fallback]. */
    private fun sizeOf(cursor: XmlCursor, fallback: Float): Float =
        cursor.attr("val")?.toFloatOrNull()?.takeIf { it > 0 } ?: fallback

    /** The colour at [cursor]; black when it can't be resolved, as Excel prints it. */
    private fun colorOf(cursor: XmlCursor): Int = colors.resolve(cursor) ?: BLACK

    /** A border edge's line from its style, in black until a colour follows; null for no line. */
    private fun edgeLine(cursor: XmlCursor): BorderLine? =
        cursor.attr("style")?.let(::lineWidth)?.let { BorderLine(it, BLACK) }

    private fun withEdge(borders: Borders, edge: String?, line: BorderLine?): Borders = when (edge) {
        "left", "start" -> borders.copy(left = line)
        "right", "end" -> borders.copy(right = line)
        "top" -> borders.copy(top = line)
        "bottom" -> borders.copy(bottom = line)
        else -> borders
    }

    private fun readXf(cursor: XmlCursor): RawXf {
        val numFmtId = cursor.attr("numFmtId")?.toIntOrNull() ?: 0
        val fontId = cursor.attr("fontId")?.toIntOrNull() ?: 0
        val fillId = cursor.attr("fillId")?.toIntOrNull() ?: 0
        val borderId = cursor.attr("borderId")?.toIntOrNull() ?: 0
        var horizontal = HorizontalAlign.GENERAL
        var vertical = VerticalAlign.BOTTOM
        var wrap = false
        forEachChild(cursor) { name ->
            if (name == "alignment") {
                horizontal = horizontalOf(cursor.attr("horizontal"))
                vertical = verticalOf(cursor.attr("vertical"))
                wrap = cursor.flag("wrapText")
            }
        }
        return RawXf(numFmtId, fontId, fillId, borderId, horizontal, vertical, wrap)
    }

    private companion object {
        val SKIPPED = setOf("cellStyleXfs", "dxfs", "tableStyles", "cellStyles", "colors", "extLst")
        val EDGES = setOf("left", "right", "top", "bottom", "start", "end")
        const val THIN = 0.5f
        const val MEDIUM = 1f
        const val THICK = 1.5f

        fun lineWidth(style: String): Float? = when (style) {
            "none" -> null
            "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot", "double" -> MEDIUM
            "thick" -> THICK
            else -> THIN
        }

        fun horizontalOf(value: String?): HorizontalAlign = when (value) {
            "left", "fill", "justify", "distributed" -> HorizontalAlign.LEFT
            "center", "centerContinuous" -> HorizontalAlign.CENTER
            "right" -> HorizontalAlign.RIGHT
            else -> HorizontalAlign.GENERAL
        }

        fun verticalOf(value: String?): VerticalAlign = when (value) {
            "top" -> VerticalAlign.TOP
            "center", "justify", "distributed" -> VerticalAlign.CENTER
            else -> VerticalAlign.BOTTOM
        }
    }
}

/** A boolean element or attribute: present and not "0"/"false". */
internal fun XmlCursor.flag(attribute: String = "val"): Boolean {
    val value = attr(attribute) ?: return attribute == "val"
    return value != "0" && value != "false"
}

/** Calls [block] for each start tag inside the current element; returns on its end tag. */
internal inline fun forEachChild(cursor: XmlCursor, block: (String) -> Unit) {
    val start = cursor.depth
    while (true) {
        when (cursor.next()) {
            XmlCursor.START -> block(cursor.name)
            XmlCursor.END -> if (cursor.depth == start) return
            XmlCursor.END_DOCUMENT -> throw XlsxFormatException("unclosed element")
        }
    }
}
