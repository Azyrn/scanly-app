package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * DrawingML tables (a:tbl inside a graphicFrame): the grid, cell text, fills and borders.
 */

private const val TABLE_BORDER_WIDTH = 1f
private const val TABLE_GRID_WIDTH = 0.75f
private const val TABLE_BAND_DARK = 0.4f
private const val TABLE_BAND_LIGHT = 0.2f
private const val TABLE_GRID_COLOR = 0xFF7F7F7F.toInt()
private const val CELL_INSET_X = 7.2f
private const val CELL_INSET_Y = 3.6f

/** "No Style, No Grid": a table that asks for no borders and no fills. */
private const val TABLE_STYLE_NONE = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"

/** "No Style, Table Grid": plain grid lines. */
private const val TABLE_STYLE_GRID = "{5940675A-B579-460E-94D1-54222C63F5DA}"

/**
 * How a table is dressed. Table styles live in tableStyles.xml with dozens of variants; any named
 * style is approximated by the default accent style instead, which is what most decks use.
 */
private class TableLook(
    val styled: Boolean,
    val firstRow: Boolean,
    val bandRow: Boolean,
    val accent: Int,
    val headerText: ListStyle,
    val defaultBorder: Stroke?
) {
    /** The style's fill for a row; [bodyIndex] counts body rows only, so banding skips the header. */
    fun rowFill(header: Boolean, bodyIndex: Int): Fill? = when {
        !styled -> null
        header -> Fill.Solid(accent)
        bandRow && bodyIndex % 2 == 0 -> Fill.Solid(lighten(accent, TABLE_BAND_DARK))
        else -> Fill.Solid(lighten(accent, TABLE_BAND_LIGHT))
    }
}

private fun tableLook(tblPr: XNode?, colors: Colors): TableLook {
    val styleId = tblPr?.child("tableStyleId")?.text?.trim()?.uppercase()
    val styled = styleId != null && styleId != TABLE_STYLE_NONE && styleId != TABLE_STYLE_GRID
    val accent = colors.scheme("accent1") ?: BLACK
    val light = colors.scheme("lt1") ?: WHITE
    return TableLook(
        styled = styled,
        firstRow = tblPr?.attr("firstRow") == "1",
        bandRow = tblPr?.attr("bandRow") == "1",
        accent = accent,
        headerText = uniformStyle(LevelProps(run = RunProps(bold = true, color = light))),
        defaultBorder = defaultBorder(styled, styleId, light)
    )
}

private fun defaultBorder(styled: Boolean, styleId: String?, light: Int): Stroke? = when {
    styled -> Stroke(light, TABLE_BORDER_WIDTH)
    styleId == TABLE_STYLE_NONE -> null
    else -> Stroke(TABLE_GRID_COLOR, TABLE_GRID_WIDTH)
}

internal class PptxTableReader(private val fills: FillReader, private val issues: PptxIssues) {

    fun readTable(tbl: XNode, frame: Frame, ctx: PartContext): TableElement? {
        val columns = tbl.child("tblGrid")?.children("gridCol").orEmpty()
            .map { emu(it.attr("w"))?.coerceAtLeast(0f) ?: 0f }
        if (columns.isEmpty()) return null
        val look = tableLook(tbl.child("tblPr"), ctx.colors)

        var bodyIndex = 0
        val rows = tbl.children("tr").mapIndexed { r, tr ->
            val header = look.styled && look.firstRow && r == 0
            val styleFill = look.rowFill(header, bodyIndex)
            if (!header) bodyIndex++
            val cells = tr.children("tc").map { tc -> readCell(tc, header, styleFill, look, ctx) }
            TableRow(emu(tr.attr("h"))?.coerceAtLeast(0f) ?: 0f, cells)
        }
        if (rows.isEmpty()) return null
        return TableElement(frame, columns, rows)
    }

    private fun readCell(tc: XNode, header: Boolean, styleFill: Fill?, look: TableLook, ctx: PartContext): TableCell {
        val tcPr = tc.child("tcPr")
        val body = readTextBody(
            tc.child("txBody"),
            listOfNotNull(tc.path("txBody", "bodyPr")),
            listOfNotNull(if (header) look.headerText else null),
            ctx.colors,
            issues
        )?.copy(insets = cellInsets(tcPr), anchor = anchorOf(tcPr?.attr("anchor")))
        fun border(name: String): Stroke? = cellBorder(tcPr, name, look.defaultBorder, ctx.colors)
        return TableCell(
            text = body,
            fill = fills.fillOf(tcPr, ctx, null)?.takeUnless { it == Fill.None } ?: styleFill,
            borders = CellBorders(border("lnL"), border("lnT"), border("lnR"), border("lnB")),
            gridSpan = span(tc.attr("gridSpan")),
            rowSpan = span(tc.attr("rowSpan")),
            covered = tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1"
        )
    }
}

private fun cellInsets(tcPr: XNode?): Insets = Insets(
    emu(tcPr?.attr("marL")) ?: CELL_INSET_X,
    emu(tcPr?.attr("marT")) ?: CELL_INSET_Y,
    emu(tcPr?.attr("marR")) ?: CELL_INSET_X,
    emu(tcPr?.attr("marB")) ?: CELL_INSET_Y
)

/** A cell's own line wins; an explicit noFill line removes the style's border. */
private fun cellBorder(tcPr: XNode?, name: String, defaultBorder: Stroke?, colors: Colors): Stroke? {
    val ln = tcPr?.child(name) ?: return defaultBorder
    return strokeOf(tcPr, null, colors, lineName = name)
        ?: if (ln.child("noFill") != null) null else defaultBorder
}

private fun span(value: String?): Int = value?.toIntOrNull()?.coerceAtLeast(1) ?: 1
