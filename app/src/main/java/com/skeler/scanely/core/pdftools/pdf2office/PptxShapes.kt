package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.core.pdftools.pdf2office.exact.Block
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactRuns
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTable
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTableCell
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTableRow
import com.skeler.scanely.core.pdftools.pdf2office.exact.OfficeFamily
import com.skeler.scanely.core.pdftools.pdf2office.exact.OutRun
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextMeasurer
import com.skeler.scanely.core.pdftools.pdf2office.exact.boxGeometry
import com.skeler.scanely.core.pdftools.pdf2office.exact.tableGeometry
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val HUNDREDTHS = 100f
internal const val MIN_FONT_HUNDREDTHS = 100
internal const val MAX_FONT_HUNDREDTHS = 400_000
internal const val BOX_SLACK = 1.08f
internal const val FIRST_SHAPE_ID = 2

private const val EMU_PER_TWIP = 635L
private const val DESCENT_EM = 0.21f

// a:rPr baseline is in thousandths of a percent of the size: half-points x 0.5 / size x 100 x 1000.
private const val BASELINE_PER_HALF_POINT = 50_000f
private const val SPC_PER_TWIP = 5f
private const val FULL_SCALE = 100
private const val RGB_MASK = 0xFFFFFF
private const val NS_TABLE = "http://schemas.openxmlformats.org/drawingml/2006/table"
private const val GRID_EMU = 6_350
private const val GRID_COLOUR = "BFBFBF"
private const val EMU_PER_HUNDREDTH_MM = 360L

/**
 * LibreOffice's Cell::getMinimumHeight is the text height + 1 (1/100 mm, 0.028 pt), and row
 * edges snapped to whole units can come out one unit short: a line must sit two units under its
 * row (0.057 pt), and spcPts rounds to 0.01 pt. 0.03 stopped the drift on irs_p17 page 114;
 * 0.05 still let fed_h8 rows grow.
 */
private const val LINE_ROOM_PT = 0.08f

/** Measured: 1.06 em held, 1.0 em grew (see fitsSlideRows). */
private const val MIN_ROW_PITCH_EM = 1.05f

/** Border order in a:tcPr (ECMA-376 §21.1.3.17). */
private val TABLE_SIDES = listOf("lnL", "lnR", "lnT", "lnB")

/** Page points → slide EMUs for one page: uniform scale to fit, centred. */
internal class SlideFrame(pageWidthPt: Float, pageHeightPt: Float, slideWidth: Long, slideHeight: Long) {
    private val widthEmu = pageWidthPt.coerceAtLeast(1f) * EMU_PER_POINT
    private val heightEmu = pageHeightPt.coerceAtLeast(1f) * EMU_PER_POINT

    /** Slide points per page point. */
    val scale: Float = min(slideWidth / widthEmu, slideHeight / heightEmu)
    private val offsetX = ((slideWidth - widthEmu * scale) / 2f).coerceAtLeast(0f)
    private val offsetY = ((slideHeight - heightEmu * scale) / 2f).coerceAtLeast(0f)

    fun x(pt: Float): Long = (offsetX + pt * EMU_PER_POINT * scale).roundToLong().coerceAtLeast(0)
    fun y(pt: Float): Long = (offsetY + pt * EMU_PER_POINT * scale).roundToLong().coerceAtLeast(0)
    fun length(pt: Float): Long = (pt * EMU_PER_POINT * scale).roundToLong()

    /** An EMU length (or position) on the page, scaled; positions also shift by the centring offset. */
    fun scaled(emu: Long): Long = (emu * scale).roundToLong()
    fun xEmu(emu: Long): Long = (offsetX + emu * scale).roundToLong().coerceAtLeast(0)
    fun yEmu(emu: Long): Long = (offsetY + emu * scale).roundToLong().coerceAtLeast(0)
}

internal fun xfrm(x: Long, y: Long, cx: Long, cy: Long) =
    "<a:xfrm><a:off x=\"$x\" y=\"$y\"/>" +
        "<a:ext cx=\"${cx.coerceAtLeast(1)}\" cy=\"${cy.coerceAtLeast(1)}\"/></a:xfrm>"

/** The shapes of one exact slide, in stacking order, and the images they show. */
internal class ExactSlide(
    private val frame: SlideFrame,
    private val pageHeightPt: Float,
    private val measurer: TextMeasurer
) {
    val shapes = StringBuilder()
    val media = ArrayList<String>()

    /** The id the next shape gets. */
    var nextId = FIRST_SHAPE_ID
        private set

    /** A picture already copied into the package as ppt/media/[entry]. */
    fun picture(entry: String, x: Float, y: Float, w: Float, h: Float, name: String) {
        media += entry
        shapes.append("<p:pic><p:nvPicPr><p:cNvPr id=\"${nextId++}\" name=\"$name\"/>")
            .append("<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
            .append("<p:blipFill><a:blip r:embed=\"rIdImage${media.size}\"/>")
            .append("<a:stretch><a:fillRect/></a:stretch></p:blipFill>")
            .append("<p:spPr>${xfrm(frame.x(x), frame.y(y), frame.length(w), frame.length(h))}")
            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
    }

    /** Every block as a text box, every table as a native table where Impress can hold its rows. */
    fun text(layout: ExactPageLayout) {
        layout.blocks.forEach { shapes.append(PptxExactShapes.textBox(nextId++, frame, it, pageHeightPt, measurer)) }
        layout.tables.forEach { table ->
            if (PptxExactShapes.fitsSlideRows(table)) {
                shapes.append(PptxExactShapes.table(nextId++, frame, table, measurer))
            } else {
                table.rows.flatMap { row -> row.cells.flatMap { it.segments } }.forEach { segment ->
                    val block = ExactLayout.block(listOf(segment))
                    shapes.append(PptxExactShapes.textBox(nextId++, frame, block, pageHeightPt, measurer))
                }
            }
        }
    }
}

/** Exact-layout text on a slide: text boxes, native tables and the runs inside both. */
internal object PptxExactShapes {

    /** One block as a text box: one paragraph, a line break per PDF line, runs as [ExactRuns] fits them. */
    fun textBox(id: Int, frame: SlideFrame, block: Block, pageHeightPt: Float, measurer: TextMeasurer): String {
        val g = boxGeometry(block, pageHeightPt)
        val scale = frame.scale
        val content = StringBuilder(paragraphStart(block, g.indentLeftTwips, g.firstLineTwips, frame))
        block.lines.forEachIndexed { index, line ->
            if (index > 0) {
                content.append(
                    "<a:br><a:rPr lang=\"en-US\" sz=\"${(line.sizePt * scale * HUNDREDTHS).roundToInt()}\"/></a:br>"
                )
            }
            ExactRuns.runs(
                line,
                g.lineStarts[index],
                measurer
            ).forEach { content.append(PptxRuns.run(it, scale, measurer)) }
        }
        content.append("</a:p>")
        val rotation = if (g.rotation != 0) " rot=\"${g.rotation}\"" else ""
        val vertical = g.vertical?.let { " vert=\"$it\"" } ?: ""
        val lift = if (g.rotation == 0 && g.vertical == null) {
            lift(
                block.pitchPt,
                block.lines.maxOf { it.sizePt }
            )
        } else {
            0f
        }
        val top = frame.yEmu(g.y - (lift * EMU_PER_POINT).roundToLong())
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm$rotation><a:off x=\"${frame.xEmu(g.x)}\" y=\"$top\"/>" +
            "<a:ext cx=\"${frame.scaled(
                g.cx
            ).coerceAtLeast(1)}\" cy=\"${frame.scaled(g.cy).coerceAtLeast(1)}\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr$vertical wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"" +
            " rtlCol=\"0\" anchor=\"t\">" +
            "<a:noAutofit/></a:bodyPr><a:lstStyle/>$content</p:txBody></p:sp>"
    }

    private fun paragraphStart(block: Block, indentLeftTwips: Int, firstLineTwips: Int, frame: SlideFrame): String {
        val pitch = (block.pitchPt * frame.scale * HUNDREDTHS).roundToInt().coerceAtLeast(1)
        val align = if (block.rtl) " algn=\"r\" rtl=\"1\"" else ""
        val indent = if (indentLeftTwips != 0 || firstLineTwips != 0) {
            " marL=\"${frame.scaled(
                indentLeftTwips * EMU_PER_TWIP
            )}\" indent=\"${frame.scaled(firstLineTwips * EMU_PER_TWIP)}\""
        } else {
            ""
        }
        return "<a:p><a:pPr$indent$align><a:lnSpc><a:spcPts val=\"$pitch\"/></a:lnSpc>" +
            "<a:spcBef><a:spcPts val=\"0\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft></a:pPr>"
    }

    /**
     * With exact line spacing LibreOffice Impress puts the baseline a descent above the line's bottom,
     * the Word model (boxGeometry) puts it at 0.8 of the line: lift the box by the difference. Measured
     * on the corpus (shift.py): 2 pt on 25 pt lines of 11 pt text, none on tightly set text.
     */
    private fun lift(linePt: Float, sizePt: Float): Float =
        (linePt * (1f - ExactLayout.BASELINE_RATIO) - sizePt * DESCENT_EM).coerceAtLeast(0f)

    /**
     * Impress won't set a table row much shorter than its text (rows 7 pt apart for 7 pt text grew
     * about 1 pt each on irs_p17 page 124; 7.43 pt held on page 114), and every grown row pushes the
     * rest down. Such a table stays positioned text on a slide; Word sets it, so the .docx keeps it.
     */
    fun fitsSlideRows(table: ExactTable): Boolean = table.rows.zipWithNext().all { (row, next) ->
        baseline(next) - baseline(row) >= maxOf(row.fontSize, next.fontSize) * MIN_ROW_PITCH_EM
    }

    private fun baseline(row: ExactTableRow): Float = row.box.top + row.box.height * ExactLayout.BASELINE_RATIO

    /**
     * A table at its PDF place as a native PowerPoint table: no borders or fill (the page picture
     * behind carries the PDF's), no cell margins, each cell's text indented to where it started and
     * set like [textBox] sets a line, including its lift.
     */
    fun table(id: Int, frame: SlideFrame, table: ExactTable, measurer: TextMeasurer): String {
        val g = tableGeometry(table)
        val emptySize = (table.fontSize * frame.scale * HUNDREDTHS).roundToInt().coerceIn(
            MIN_FONT_HUNDREDTHS,
            MAX_FONT_HUNDREDTHS
        )
        val cells = table.rows.mapIndexed { r, row ->
            // A hair under the row: Impress grows a row whose line fills it exactly, and it adds up.
            val pitch = ((g.lineHeightsPt[r] - LINE_ROOM_PT) * frame.scale * HUNDREDTHS).roundToInt().coerceAtLeast(1)
            row.cells.mapIndexed { c, cell -> cell(cell, g.lefts[c], pitch, emptySize, frame, measurer) }
        }
        // The same baseline correction as textBox, from the first row's line.
        val lift = lift(g.lineHeightsPt.first(), table.fontSize)
        return PptxTables.frame(
            id,
            frame.x(table.box.left),
            frame.y(g.top - lift),
            g.widthsPt.map(frame::length),
            g.heightsPt.map(frame::length),
            cells,
            border = false
        )
    }

    private fun cell(
        cell: ExactTableCell,
        leftPt: Float,
        pitch: Int,
        emptySize: Int,
        frame: SlideFrame,
        measurer: TextMeasurer
    ): String {
        val first = cell.segments.firstOrNull()
        val rtl = first?.rtl == true
        val indent = frame.length(if (first == null || rtl) 0f else first.u0 - leftPt)
        // Segments sharing a cell each start at a tab stop where they started in the PDF
        // (a:tabLst follows a:spcAft, ECMA-376 §21.1.2.2.7). LibreOffice Impress measures stops
        // from the paragraph's marL, so such a cell has none: its first segment is reached by a
        // stop too, and every stop is measured from the cell edge whichever way a reader counts.
        val stops = if (rtl) emptyList() else cell.segments.drop(1).map { frame.length(it.u0 - leftPt) }
        // Only a stop past the cell edge: a tab to the stop it already stands on jumps to the next.
        val lead = stops.isNotEmpty() && indent > 0
        val content = StringBuilder(cellParagraphStart(rtl, indent, stops, lead, pitch))
        val tab = "<a:r><a:rPr lang=\"en-US\" sz=\"$emptySize\" dirty=\"0\"/><a:t>\t</a:t></a:r>"
        val space = "<a:r><a:rPr lang=\"en-US\" sz=\"$emptySize\" dirty=\"0\"/><a:t> </a:t></a:r>"
        if (lead) content.append(tab)
        content.append(segments(cell, if (rtl) space else tab, frame.scale, measurer))
        // Impress sizes an empty paragraph by its end mark's font, not its fixed line (an 8 pt
        // mark grew fed_h8's 9 pt rows to 9.6): an empty cell gets the smallest mark.
        if (first == null) content.append("<a:endParaRPr lang=\"en-US\" sz=\"$MIN_FONT_HUNDREDTHS\"/>")
        return content.append("</a:p>").toString()
    }

    /** The cell's segments in order, [separator] between them (a tab, or a space in right-to-left text). */
    private fun segments(cell: ExactTableCell, separator: String, scale: Float, measurer: TextMeasurer): String {
        val xml = StringBuilder()
        cell.segments.forEachIndexed { s, segment ->
            if (s > 0) xml.append(separator)
            val start = if (segment.rtl) segment.u1 else segment.u0
            ExactRuns.runs(segment, start, measurer).forEach { xml.append(PptxRuns.run(it, scale, measurer)) }
        }
        return xml.toString()
    }

    private fun cellParagraphStart(rtl: Boolean, indent: Long, stops: List<Long>, lead: Boolean, pitch: Int): String {
        val align = if (rtl) " algn=\"r\" rtl=\"1\"" else ""
        val margin = if (indent > 0 && stops.isEmpty()) " marL=\"$indent\"" else ""
        val allStops = if (lead) listOf(indent) + stops else stops
        val tabs = if (allStops.isEmpty()) {
            ""
        } else {
            allStops.joinToString(
                "",
                "<a:tabLst>",
                "</a:tabLst>"
            ) { "<a:tab pos=\"$it\" algn=\"l\"/>" }
        }
        return "<a:p><a:pPr$margin$align><a:lnSpc><a:spcPts val=\"$pitch\"/></a:lnSpc>" +
            "<a:spcBef><a:spcPts val=\"0\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft>$tabs</a:pPr>"
    }
}

/** One fitted run as DrawingML text: font, size, letter spacing standing in for glyph scaling. */
internal object PptxRuns {

    /**
     * DrawingML has no horizontal glyph scaling (Word's `w:w`), so a run's width correction becomes
     * character spacing (`spc`, hundredths of a point, after every character).
     */
    fun run(run: OutRun, scale: Float, measurer: TextMeasurer): String {
        val s = run.style
        val font = xmlText(s.family.officeName)
        val complex = if (s.arabic) OfficeFamily.ARABIC_NAME else s.family.officeName
        return "<a:r><a:rPr ${runAttributes(run, scale, measurer)}>" +
            "<a:solidFill><a:srgbClr val=\"${"%06X".format(s.argb and RGB_MASK)}\"/></a:solidFill>" +
            "<a:latin typeface=\"$font\"/><a:cs typeface=\"${xmlText(complex)}\"/></a:rPr>" +
            "<a:t>${xmlText(run.text)}</a:t></a:r>"
    }

    private fun runAttributes(run: OutRun, scale: Float, measurer: TextMeasurer): String {
        val s = run.style
        val size = (s.sizePt * scale * HUNDREDTHS).roundToInt().coerceIn(MIN_FONT_HUNDREDTHS, MAX_FONT_HUNDREDTHS)
        val spacing = spacing(run, scale, measurer).roundToInt()
        val attrs = StringBuilder("lang=\"${if (s.arabic) "ar-SA" else "en-US"}\" sz=\"$size\"")
        if (s.bold) attrs.append(" b=\"1\"")
        if (s.italic) attrs.append(" i=\"1\"")
        if (!s.arabic) attrs.append(" kern=\"0\"")
        if (spacing != 0) attrs.append(" spc=\"$spacing\"")
        if (run.raiseHalfPoints != 0) {
            attrs.append(
                " baseline=\"${(run.raiseHalfPoints * BASELINE_PER_HALF_POINT / s.sizePt).roundToInt()}\""
            )
        }
        return attrs.append(" dirty=\"0\"").toString()
    }

    /** The run's letter spacing, plus its glyph scaling spread over its letters, in hundredths of a point. */
    private fun spacing(run: OutRun, scale: Float, measurer: TextMeasurer): Float {
        val s = run.style
        var spacing = run.spacingTwips * SPC_PER_TWIP * scale
        if (run.scalePercent != FULL_SCALE && run.text.isNotEmpty()) {
            val natural = measurer.width(run.text, s.family, s.arabic, s.bold, s.italic, s.sizePt)
            spacing += natural * (run.scalePercent - FULL_SCALE) / FULL_SCALE / run.text.length * HUNDREDTHS * scale
        }
        return spacing
    }
}

/** Native PowerPoint tables: the frame both writers' tables use, and the flowing layout's tables. */
internal object PptxTables {

    /**
     * A flowing-layout table as a native PowerPoint table at its PDF place, with a light grid so it
     * still reads as a table on a slide with no page picture. Column widths and row heights come
     * from the PDF when known, else the table's box is divided equally.
     */
    fun textTable(id: Int, frame: SlideFrame, block: TableBlock): String {
        val columns = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val widths = (block.columnWidthsPt.takeIf { it.size == columns } ?: List(columns) { block.box.width / columns })
            .toMutableList().apply { this[lastIndex] *= BOX_SLACK }
        val size = (block.fontSize.coerceAtLeast(1f) * frame.scale * HUNDREDTHS).roundToInt()
            .coerceIn(MIN_FONT_HUNDREDTHS, MAX_FONT_HUNDREDTHS)
        val cells = block.rows.map { row ->
            List(columns) { c ->
                val text = row.getOrElse(c) { "" }
                if (text.isEmpty()) {
                    "<a:p><a:endParaRPr lang=\"en-US\" sz=\"$size\"/></a:p>"
                } else {
                    "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"$size\" dirty=\"0\"/><a:t>${xmlText(text)}</a:t></a:r></a:p>"
                }
            }
        }
        return frame(
            id,
            frame.x(block.box.left),
            frame.y(block.box.top),
            widths.map(frame::length),
            rowHeights(block).map(frame::length),
            cells,
            border = true
        )
    }

    /** Each row from its top to the next row's, the last its own height; the box divided equally when unknown. */
    private fun rowHeights(block: TableBlock): List<Float> {
        val rows = block.rows.size.coerceAtLeast(1)
        return block.rowBoxes.takeIf { it.size == block.rows.size && it.isNotEmpty() }?.let { boxes ->
            boxes.mapIndexed { i, box -> boxes.getOrNull(i + 1)?.let { it.top - box.top } ?: box.height }
        } ?: List(rows) { block.box.height / rows }
    }

    /** [cells] holds each cell's paragraphs, row by row; [widths] and [heights] are slide EMUs. */
    fun frame(
        id: Int,
        x: Long,
        y: Long,
        widths: List<Long>,
        heights: List<Long>,
        cells: List<List<String>>,
        border: Boolean
    ): String {
        val columnWidths = onLibreOfficeGrid(widths)
        val rowHeights = onLibreOfficeGrid(heights)
        // Every cell says what it looks like: the deck has no table style to fall back on.
        val stroke = if (border) " w=\"$GRID_EMU\"" else ""
        val paint = if (border) "<a:solidFill><a:srgbClr val=\"$GRID_COLOUR\"/></a:solidFill>" else "<a:noFill/>"
        val sides = TABLE_SIDES.joinToString("") { "<a:$it$stroke>$paint</a:$it>" }
        val cellProperties = "<a:tcPr marL=\"0\" marR=\"0\" marT=\"0\" marB=\"0\">$sides<a:noFill/></a:tcPr>"
        val xml = StringBuilder("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"$id\" name=\"Table $id\"/>")
            .append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr>")
            .append("<p:nvPr/></p:nvGraphicFramePr>")
            .append("<p:xfrm><a:off x=\"$x\" y=\"$y\"/>")
            .append("<a:ext cx=\"${columnWidths.sum()}\" cy=\"${rowHeights.sum()}\"/></p:xfrm>")
            .append("<a:graphic><a:graphicData uri=\"$NS_TABLE\"><a:tbl><a:tblPr/><a:tblGrid>")
        columnWidths.forEach { xml.append("<a:gridCol w=\"$it\"/>") }
        xml.append("</a:tblGrid>")
        cells.forEachIndexed { r, row ->
            xml.append("<a:tr h=\"${rowHeights[r]}\">")
            row.forEach { paragraphs ->
                xml.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>$paragraphs</a:txBody>$cellProperties</a:tc>")
            }
            xml.append("</a:tr>")
        }
        return xml.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>").toString()
    }

    /**
     * Lengths laid end to end, as whole 1/100 mm (LibreOffice's unit): the edges are rounded as
     * running totals, so no length is rounded again on import and the last edge stays in place.
     * Rounded one by one, 75 rows of a tax table drifted 1.1 pt down.
     */
    private fun onLibreOfficeGrid(lengths: List<Long>): List<Long> {
        var exact = 0L
        var snapped = 0L
        return lengths.map { length ->
            exact += length
            val edge = ((exact + EMU_PER_HUNDREDTH_MM / 2) / EMU_PER_HUNDREDTH_MM * EMU_PER_HUNDREDTH_MM)
                .coerceAtLeast(snapped + EMU_PER_HUNDREDTH_MM)
            (edge - snapped).also { snapped = edge }
        }
    }
}
