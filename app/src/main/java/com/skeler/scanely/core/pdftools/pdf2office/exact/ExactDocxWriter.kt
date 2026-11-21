package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.EMU_PER_POINT
import com.skeler.scanely.core.pdftools.pdf2office.NS_DRAWING
import com.skeler.scanely.core.pdftools.pdf2office.NS_OFFICE_RELS
import com.skeler.scanely.core.pdftools.pdf2office.REL_IMAGE
import com.skeler.scanely.core.pdftools.pdf2office.TYPE_RELATIONSHIPS
import com.skeler.scanely.core.pdftools.pdf2office.XML_HEADER
import com.skeler.scanely.core.pdftools.pdf2office.contentTypes
import com.skeler.scanely.core.pdftools.pdf2office.copyEntry
import com.skeler.scanely.core.pdftools.pdf2office.packageRelationships
import com.skeler.scanely.core.pdftools.pdf2office.relationship
import com.skeler.scanely.core.pdftools.pdf2office.relationships
import com.skeler.scanely.core.pdftools.pdf2office.writeEntry
import com.skeler.scanely.core.pdftools.pdf2office.xmlText
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val NS_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
private const val NS_PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture"
private const val NS_WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
private const val NS_MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
internal const val DOCUMENT_NAMESPACES = "xmlns:w=\"$NS_WORD\" xmlns:r=\"$NS_OFFICE_RELS\" xmlns:wp=\"$NS_WP\" " +
    "xmlns:a=\"$NS_DRAWING\" xmlns:pic=\"$NS_PICTURE\" xmlns:wps=\"$NS_WPS\" xmlns:mc=\"$NS_MC\" mc:Ignorable=\"wps\""
private const val TYPE_DOCUMENT = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
private const val TYPE_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
private const val REL_STYLES = "$NS_OFFICE_RELS/styles"
private const val HEADING_LEVELS = 6

/**
 * A .docx that looks like the PDF page for page. Every PDF page becomes its own section with the
 * page's exact size and no margins. Behind the text sits a picture of the page drawn without its
 * text (rules, fills, figures, photos); on top, every [Block] is an anchored text box at its PDF
 * position holding real, editable paragraphs in the font, size, colour and weight of the source.
 *
 * `word/document.xml` is streamed one page at a time, so memory stays flat for long documents; the
 * background pictures are files the caller keeps on disk until [finish] copies them in.
 */
class ExactDocxWriter(out: OutputStream, private val measurer: TextMeasurer) {
    private val lineJoin = DocxLineJoin(measurer)

    private val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_BYTES))
    private val writer: Writer = OutputStreamWriter(zip, Charsets.UTF_8)

    /** Every picture in the package, in order: (file, its name in word/media, "media1.png" from 1). */
    private val media = mutableListOf<Pair<File, String>>()
    private var drawingId = 0
    private var pendingSection: String? = null

    init {
        zip.putNextEntry(ZipEntry("word/document.xml"))
        writer.write(XML_HEADER)
        writer.write(
            "<w:document $DOCUMENT_NAMESPACES><w:body>"
        )
    }

    /**
     * Adds one page. [background] is a PNG or JPEG of the page without its text and pictures, or null
     * when there is nothing else to show; it and [pictures] must stay on disk until [finish].
     */
    fun addPage(
        widthPt: Float,
        heightPt: Float,
        layout: ExactPageLayout?,
        background: File?,
        pictures: List<PagePicture> = emptyList()
    ) {
        val page = HeldPage(widthPt, heightPt, layout, background, pictures)
        // A page whose lowest table may run on is held until the next page shows whether it does.
        val piece = run.continuation(page)
        if (piece == null) flushRun()
        run.add(page, piece)
        if (!run.isOpen) flushRun()
    }

    /** Pages held while a table may run on, each with its piece of that table. */
    private val run = PageRun()

    private fun flushRun() {
        if (run.pages.size > 1) {
            val first = run.pages.first()
            flow.flush(final = false)
            ExactDocxTableRun(this, run.pages.toList(), run.pieces.map { it!! }).write(first.widthPt, first.heightPt)
        } else {
            run.pages.toList().forEach(flow::add)
        }
        run.clear()
    }

    /** The running text of tagged pages as body paragraphs; it hands every other page to [writePositioned]. */
    private val flow = ExactDocxFlow(this, measurer)

    /** A page as placed text: one section of the page's size holding one paragraph of anchored boxes. */
    internal fun writePositioned(page: HeldPage) {
        writeBody("<w:p><w:pPr>$TINY_LINE</w:pPr>${pageContent(page)}</w:p>")
        endSection(sectionProperties(page.widthPt, page.heightPt, headers = extras.plainHeaders()))
        pagesWritten++
    }

    /** Pages written so far: the next page's number in the document is one more. */
    internal var pagesWritten = 0

    /**
     * The parts a flowing document adds to the package: headers, the setting they need, numbering.
     * A section's header references come from [DocxExtraParts.headers]; one without running text of its
     * own names empty ones where the section before it had some ([DocxExtraParts.plainHeaders]).
     */
    internal val extras = DocxExtraParts()

    /**
     * Everything on [page] but its piece of a flowing table ([skip]): the page
     * picture and pictures, and every other block and table as anchored boxes. [inCell]: the anchors
     * go in a table cell and are placed on the page all the same (layoutInCell off).
     */
    internal fun pageContent(page: HeldPage, skip: ExactTable? = null, inCell: Boolean = false): String {
        val layout = page.layout
        anchorsInCell = inCell
        val content = StringBuilder()
        // Text hidden behind the picture goes first: LibreOffice stacks objects of the same layer in
        // document order and ignores relativeHeight, so anything written after the picture covers it.
        // Every box also gets its own ascending relativeHeight (below the picture's, which is written
        // later): a tie (all 0) left LibreOffice's order unstable and the last lines showed through.
        if (layout?.behindPicture == true) content.append(boxes(layout, page.heightPt, behind = true, skip))
        content.append(pictures(page.widthPt, page.heightPt, page.background, page.pictures))
        if (layout != null && !layout.behindPicture) content.append(boxes(layout, page.heightPt, false, skip))
        anchorsInCell = false
        return content.toString()
    }

    /**
     * A flowing page's anchored content, for its first paragraph: the page picture and pictures, the
     * tables, and [rest], the blocks that are not running text, as text boxes.
     */
    internal fun anchors(page: HeldPage, rest: List<Block>): String {
        val layout = page.layout!!
        val placed = ExactPageLayout(layout.widthPt, layout.heightPt, rest, false, layout.tables, tags = layout.tags)
        return pictures(
            page.widthPt,
            page.heightPt,
            page.background,
            page.pictures
        ) + boxes(placed, page.heightPt, false, null)
    }

    /**
     * Raw body XML, after the previous section's closing paragraph: a section break lives in the last
     * paragraph of the section it ends, so it is written when the next section's content arrives.
     */
    internal fun writeBody(xml: String) {
        pendingSection?.let { writer.write(it) }
        pendingSection = ""
        writer.write(xml)
    }

    /**
     * Held back: written as this page's closing paragraph when another page follows, or as the
     * body's final sectPr by finish().
     */
    internal fun endSection(section: String) {
        pendingSection = "<w:p><w:pPr>$TINY_LINE$section</w:pPr></w:p>"
        lastSection = section
    }

    private var anchorsInCell = false

    /** Table rows and cells as WordprocessingML, for table boxes and tables in the body flow. */
    internal val tableXml = DocxTableXml(measurer)

    private var lastSection: String = sectionProperties(DEFAULT_WIDTH_PT, DEFAULT_HEIGHT_PT)

    /** Closes every part and the zip. The writer is unusable afterwards. */
    fun finish() {
        flushRun()
        flow.flush(final = true)
        if (pendingSection == null) writer.write("<w:p/>")
        writer.write("$lastSection</w:body></w:document>")
        writer.flush()
        zip.closeEntry()
        media.forEach { (file, name) -> zip.copyEntry("word/media/$name", file) }
        zip.writeEntry("word/styles.xml", STYLES)
        val parts = extras.parts(flow.lists)
        parts.forEach { zip.writeEntry("word/${it.name}", it.xml) }
        zip.writeEntry(
            "word/_rels/document.xml.rels",
            relationships(
                listOf(relationship("rIdStyles", REL_STYLES, "styles.xml")) +
                    parts.map { relationship(it.relationId, it.relationType, it.name) } +
                    media.mapIndexed { index, (_, name) ->
                        relationship(imageRelId(index + 1), REL_IMAGE, "media/$name")
                    }
            )
        )
        zip.writeEntry("_rels/.rels", packageRelationships("word/document.xml"))
        val overrides = DOCUMENT_TYPES + parts.associate { "/word/${it.name}" to it.contentType }
        zip.writeEntry("[Content_Types].xml", contentTypes(CONTENT_DEFAULTS, overrides))
        writer.close()
    }

    private fun boxes(layout: ExactPageLayout, heightPt: Float, behind: Boolean, skip: ExactTable?): String {
        val xml = StringBuilder()
        // A tagged page's headings are the ones its structure names, and they are in the running text.
        val headings = if (layout.tags != null && layout.tags.nodes.isNotEmpty()) {
            emptyMap()
        } else {
            ExactHeadings.levels(
                layout
            )
        }
        layout.forEachInReadingOrder(
            onBlock = { xml.append(textBox(it, heightPt, behind, headings[it])) },
            onTable = { if (it !== skip) xml.append(tableBox(it, behind)) }
        )
        return xml.toString()
    }

    /** The page background (when there is one) and the page's own pictures, in that order. */
    private fun pictures(widthPt: Float, heightPt: Float, background: File?, pictures: List<PagePicture>): String {
        val xml = StringBuilder()
        if (background != null) {
            media += background to "media${media.size + 1}" + if (background.name.endsWith(".png")) ".png" else ".jpg"
            xml.append(picture(media.size, 0f, 0f, widthPt, heightPt, "Page background", locked = true))
        }
        pictures.forEach {
            media += it.file to "media${media.size + 1}" + if (it.png) ".png" else ".jpg"
            xml.append(
                picture(media.size, it.xPt, it.yPt, it.widthPt, it.heightPt, "Picture ${media.size}", locked = false)
            )
        }
        return xml.toString()
    }

    /** A picture behind the text, at [xPt]/[yPt] from the page's top-left. */
    private fun picture(
        index: Int,
        xPt: Float,
        yPt: Float,
        widthPt: Float,
        heightPt: Float,
        name: String,
        locked: Boolean
    ): String {
        val id = ++drawingId
        val cx = emu(widthPt).coerceAtLeast(1)
        val cy = emu(heightPt).coerceAtLeast(1)
        return "<w:r><w:drawing>" +
            anchorOpen(
                Anchor(id, true, emu(xPt), emu(yPt), cx, cy, name, locked, layoutInCell = !anchorsInCell)
            ) +
            "<a:graphic><a:graphicData uri=\"$NS_PICTURE\"><pic:pic><pic:nvPicPr>" +
            "<pic:cNvPr id=\"$id\" name=\"${media[index - 1].second}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"${imageRelId(
                index
            )}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>" +
            "</wp:anchor></w:drawing></w:r>"
    }

    /**
     * One block as a text box. Geometry follows ExactLayout's model; see [boxGeometry]. A level
     * left-to-right prose block of several lines is written to reflow when edited: its lines are
     * one paragraph (a space, or a soft hyphen for a word hyphenated across lines, where the PDF
     * broke) in a box that wraps at the widest line and grows with its text, so each line still
     * breaks where it did. Where a line ends short enough that the next line's first word would
     * have fitted, a new paragraph starts instead, so Word does not pull that word up.
     */
    internal fun textBox(block: Block, pageHeightPt: Float, behind: Boolean = false, outline: Int? = null): String {
        val reflow = docxReflows(block, behind)
        val g = boxGeometry(block, pageHeightPt, reflow)
        val id = ++drawingId
        val paragraph = StringBuilder("<w:p>").append(paragraphProperties(block, g, outline = outline))
        val leftPt = g.x / EMU_PER_POINT.toFloat()
        val rightPt = (g.x + g.cx) / EMU_PER_POINT.toFloat()
        block.lines.forEachIndexed { index, line ->
            val next = block.lines.getOrNull(index + 1)
            val join = if (reflow && next != null) lineJoin.join(line, next, leftPt, rightPt) else Join.BREAK
            val runs = lineRuns(line, g.lineStarts[index], join)
            runs.forEach { paragraph.append(runXml(it)) }
            if (next != null) paragraph.append(joinXml(join, runs.last().style, block, g, outline))
        }
        paragraph.append("</w:p>")
        val anchor = Anchor(
            id,
            behind = behind,
            x = g.x,
            y = g.y,
            cx = g.cx,
            cy = g.cy,
            name = "Text $id",
            locked = false,
            layoutInCell = !anchorsInCell
        )
        return textShape(anchor, paragraph, rotation = g.rotation, vertical = g.vertical, grows = reflow)
    }

    /** A line's runs; before a soft-hyphen join the line's own trailing hyphen is dropped. */
    internal fun lineRuns(line: Segment, startPt: Float, join: Join): List<OutRun> {
        val runs = ExactRuns.runs(line, startPt, measurer).toMutableList()
        if (join == Join.SOFT_HYPHEN && runs.last().text.endsWith("-")) {
            val last = runs.removeAt(runs.lastIndex)
            if (last.text.length > 1) {
                runs += OutRun(
                    last.text.dropLast(1),
                    last.style,
                    last.spacingTwips,
                    last.scalePercent,
                    last.raiseHalfPoints
                )
            }
        }
        return runs
    }

    /**
     * A table as a native Word table inside a text box at the table's place, so the page keeps its
     * exact composition and the table is still one editable table. No borders and no cell margins:
     * the page picture behind already carries the PDF's rules and fills. Each row is as tall as the
     * distance to the next one, each cell's text indented to where it started in the PDF.
     */
    internal fun tableBox(table: ExactTable, behind: Boolean = false): String {
        val id = ++drawingId
        val geometry = tableGeometry(table)
        val widths = onTwipGrid(geometry.widthsPt)
        val heights = onTwipGrid(geometry.heightsPt)
        val xml = StringBuilder(tableProperties(if (table.rtl) widths.reversed() else widths, table.rtl))
        table.rows.indices.forEach { r -> xml.append(tableXml.row(table, geometry, widths, r, heights[r])) }
        // Block content must end in a paragraph; a 1 pt one adds nothing to the box.
        xml.append("</w:tbl><w:p><w:pPr>$TINY_LINE</w:pPr></w:p>")
        val cx = emu(geometry.widthsPt.sum()).coerceAtLeast(1)
        val cy = emu(geometry.heightsPt.sum()).coerceAtLeast(1)
        val anchor = Anchor(
            id,
            behind = behind,
            // A right-to-left table fills its box from the right: the last column's slack goes to the left.
            x = emu(table.box.left - if (table.rtl) geometry.slackPt else 0f),
            y = emu(geometry.top),
            cx = cx,
            cy = cy,
            name = "Table $id",
            layoutInCell = !anchorsInCell
        )
        return textShape(anchor, xml, rotation = 0, vertical = null)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_WIDTH_PT = 595f
        private const val DEFAULT_HEIGHT_PT = 842f

        private fun emu(points: Float): Long = (points * EMU_PER_POINT).roundToLong()

        private val STYLES = XML_HEADER +
            "<w:styles xmlns:w=\"$NS_WORD\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:cs=\"Times New Roman\"/>" +
            "<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            // No widow control: a paragraph over a page break breaks where the PDF broke it.
            "<w:pPrDefault><w:pPr><w:widowControl w:val=\"0\"/>" +
            "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>" +
            "</w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
            "<w:qFormat/></w:style>" +
            // Headings carry their level and nothing else: each paragraph keeps the look the PDF gave it.
            (1..HEADING_LEVELS).joinToString("") { level ->
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/>" +
                    "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                    "<w:pPr><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr></w:style>"
            } + "</w:styles>"
    }
}

// XML parts that need no writer state.

/** Rows and cells of a native Word table placed as the PDF had them. */
internal class DocxTableXml(private val measurer: TextMeasurer) {

    /**
     * Row [r] of [table], [heightTwips] tall at least. [rowProperties] go in its w:trPr; [anchors]
     * (drawings placed on the page) go in its first paragraph.
     */
    fun row(
        table: ExactTable,
        geometry: TableGeometry,
        widths: List<Int>,
        r: Int,
        heightTwips: Int,
        rowProperties: String = "",
        anchors: String = ""
    ): String {
        val row = table.rows[r]
        // Never taller than the row as written: a line a twip over grows the row, and that adds up.
        val lineTwips = (geometry.lineHeightsPt[r] * TWIPS).roundToInt().coerceIn(1, heightTwips)
        val xml = StringBuilder("<w:tr><w:trPr>$rowProperties")
            .append("<w:trHeight w:val=\"$heightTwips\" w:hRule=\"atLeast\"/></w:trPr>")
        var column = 0
        val cells = row.cells.map { cell ->
            val span = column until (column + cell.span).coerceAtMost(widths.size)
            column += cell.span
            val right = geometry.lefts.getOrElse(span.last + 1) { table.box.right }
            val place = CellPlace(span.sumOf { widths[it] }, geometry.lefts[span.first], right, lineTwips)
            cellXml(cell, place, geometry.spaceBefore(r, cell))
        }.toMutableList()
        // A right-to-left table lists its cells from the right edge of the page.
        if (table.rtl) cells.reverse()
        if (anchors.isNotEmpty()) {
            val at = cells[0].indexOf("</w:pPr>") + "</w:pPr>".length
            cells[0] = cells[0].substring(0, at) + anchors + cells[0].substring(at)
        }
        cells.forEach { xml.append(it) }
        return xml.append("</w:tr>").toString()
    }

    /**
     * One cell: a paragraph per line, each from where it started in the PDF and as far below the
     * line above as it was there; segments sharing a line are at tab stops.
     */
    private fun cellXml(cell: ExactTableCell, place: CellPlace, spaceBefore: List<Float>): String {
        val xml = StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"${place.widthTwips}\" w:type=\"dxa\"/>")
        if (cell.span > 1) xml.append("<w:gridSpan w:val=\"${cell.span}\"/>")
        when (cell.merge) {
            CellMerge.RESTART -> xml.append("<w:vMerge w:val=\"restart\"/>")
            CellMerge.CONTINUE -> xml.append("<w:vMerge/>")
            CellMerge.NONE -> Unit
        }
        xml.append("</w:tcPr>")
        if (cell.lines.isEmpty()) {
            xml.append("<w:p>").append(cellParagraphProperties(emptyList(), place, 0)).append("</w:p>")
        }
        cell.lines.forEachIndexed { i, line ->
            val before = (spaceBefore[i] * TWIPS).roundToInt()
            xml.append("<w:p>").append(cellParagraphProperties(line.segments, place, before))
            cellLineRuns(line, xml)
            xml.append("</w:p>")
        }
        return xml.append("</w:tc>").toString()
    }

    /** A cell line's runs: segments right to left in a right-to-left line, apart by a space or a tab. */
    private fun cellLineRuns(line: ExactCellLine, xml: StringBuilder) {
        val rtl = line.segments.first().rtl
        val segments = if (rtl) line.segments.sortedByDescending { it.u1 } else line.segments
        segments.forEachIndexed { s, segment ->
            if (s > 0) xml.append(if (rtl) "<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>" else "<w:r><w:tab/></w:r>")
            val start = if (segment.rtl) segment.u1 else segment.u0
            ExactRuns.runs(segment, start, measurer).forEach { xml.append(runXml(it)) }
        }
    }
}

/** A page's section: its size, no margins unless a table flows over its pages ([topPt], [bottomPt]). */
internal fun sectionProperties(
    widthPt: Float,
    heightPt: Float,
    topPt: Float = 0f,
    bottomPt: Float = 0f,
    leftPt: Float = 0f,
    rightPt: Float = 0f,
    /** w:headerReference elements ([DocxExtraParts.headers]); [titlePage]: the first page has a header of its own. */
    headers: String = "",
    titlePage: Boolean = false
): String {
    val w = (widthPt * TWIPS).roundToInt()
    val h = (heightPt * TWIPS).roundToInt()
    val orient = if (w > h) " w:orient=\"landscape\"" else ""
    val top = (topPt * TWIPS).roundToInt()
    val bottom = (bottomPt * TWIPS).roundToInt()
    return "<w:sectPr>$headers<w:pgSz w:w=\"$w\" w:h=\"$h\"$orient/>" +
        "<w:pgMar w:top=\"$top\" w:right=\"${(rightPt * TWIPS).roundToInt()}\" w:bottom=\"$bottom\"" +
        " w:left=\"${(leftPt * TWIPS).roundToInt()}\"" +
        " w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
        (if (titlePage) "<w:titlePg/>" else "") + "</w:sectPr>"
}

private fun imageRelId(index: Int) = "rIdMedia$index"

internal const val TWIPS = 20f
private const val RGB_MASK = 0xFFFFFF
private const val FULL_SCALE = 100
private const val MIN_HALF_POINTS = 2
private const val MAX_HALF_POINTS = 3276

/** Where Word starts its relativeHeight values (0x0F000000). */
private const val Z_BASE = 251_658_240L

private val TABLE_BORDERS = listOf("top", "left", "bottom", "right", "insideH", "insideV")
private val CELL_SIDES = listOf("top", "left", "bottom", "right")

/** The anchor paragraph itself: 1 pt tall, so it never pushes a page over. */
internal const val TINY_LINE = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>"

/** A drawing's place on the page in EMUs; its id doubles as its stacking order. */
private class Anchor(
    val id: Int,
    val behind: Boolean,
    val x: Long,
    val y: Long,
    val cx: Long,
    val cy: Long,
    val name: String,
    val locked: Boolean = false,
    /** Off for a box anchored in a table cell but placed on the page (ECMA-376 §20.4.2.3). */
    val layoutInCell: Boolean = true
)

// Offset like Word's own values: LibreOffice puts 0 and 1 above everything (tdf#159158), which
// put the first page's background over that page's pictures. Stays far below Int.MAX_VALUE,
// past which LibreOffice misorders behind-text drawings (tdf#120760).
private fun anchorOpen(a: Anchor) =
    "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"${Z_BASE + a.id}\" " +
        "behindDoc=\"${if (a.behind) 1 else 0}\" locked=\"${if (a.locked) 1 else 0}\" " +
        "layoutInCell=\"${if (a.layoutInCell) 1 else 0}\" " +
        "allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>" +
        "<wp:positionH relativeFrom=\"page\"><wp:posOffset>${a.x}</wp:posOffset></wp:positionH>" +
        "<wp:positionV relativeFrom=\"page\"><wp:posOffset>${a.y}</wp:posOffset></wp:positionV>" +
        "<wp:extent cx=\"${a.cx}\" cy=\"${a.cy}\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>" +
        "<wp:docPr id=\"${a.id}\" name=\"${xmlText(a.name)}\"/><wp:cNvGraphicFramePr/>"

/** A borderless, unfilled text box holding [content] (block-level WordprocessingML). */
private fun textShape(
    anchor: Anchor,
    content: CharSequence,
    rotation: Int,
    vertical: String?,
    grows: Boolean = false
): String {
    val rot = if (rotation != 0) " rot=\"$rotation\"" else ""
    return "<w:r><w:drawing>" + anchorOpen(anchor) +
        "<a:graphic><a:graphicData uri=\"$NS_WPS\"><wps:wsp><wps:cNvSpPr txBox=\"1\"/>" +
        "<wps:spPr><a:xfrm$rot><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${anchor.cx}\" cy=\"${anchor.cy}\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>" +
        "<wps:txbx><w:txbxContent>$content</w:txbxContent></wps:txbx>" +
        "<wps:bodyPr rot=\"0\"${vertical?.let { " vert=\"$it\"" } ?: ""} wrap=\"${if (grows) "square" else "none"}\"" +
        " lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\">" +
        (if (grows) "<a:spAutoFit/>" else "<a:noAutofit/>") +
        "</wps:bodyPr></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"
}

/** What goes between two lines of a block, by how they [Join]. */
private fun joinXml(join: Join, style: TextStyle, block: Block, g: BoxGeometry, outline: Int?): String = when (join) {
    Join.BREAK -> "<w:r><w:br/></w:r>"
    Join.SPACE -> spaceXml(style)
    Join.SOFT_HYPHEN -> softHyphenXml(style)
    Join.PARAGRAPH -> "</w:p><w:p>" + paragraphProperties(block, g, first = false, outline = outline)
}

/** A space between two lines that are one paragraph. */
internal fun spaceXml(style: TextStyle): String = runXml(OutRun(" ", style, 0, FULL_SCALE, 0))

/** Where a word was hyphenated over a line break: a hyphen that only shows when the word breaks there again. */
internal fun softHyphenXml(style: TextStyle): String =
    "<w:r><w:rPr>${fontProperties(style)}${adjustments(OutRun("-", style, 0, FULL_SCALE, 0))}</w:rPr>" +
        "<w:softHyphen/></w:r>"

/** A block's exact line pitch, indents and direction. */
private fun paragraphProperties(block: Block, g: BoxGeometry, first: Boolean = true, outline: Int? = null): String {
    val xml = StringBuilder("<w:pPr>")
    if (block.rtl) xml.append("<w:bidi/>")
    xml.append("<w:spacing w:before=\"0\" w:after=\"0\" ")
        .append("w:line=\"${(block.pitchPt * TWIPS).roundToInt()}\" w:lineRule=\"exact\"/>")
    if (g.indentLeftTwips != 0 || g.firstLineTwips != 0) {
        xml.append("<w:ind w:left=\"${g.indentLeftTwips}\"")
        if (first && g.firstLineTwips > 0) xml.append(" w:firstLine=\"${g.firstLineTwips}\"")
        if (g.firstLineTwips < 0) {
            // Later paragraphs start at the other lines' edge: indent and hang cancel.
            xml.append(if (first) " w:hanging=\"${-g.firstLineTwips}\"" else "")
        }
        xml.append("/>")
    }
    // A heading ([ExactHeadings]): Word's navigation pane and contents list it; the page looks the same.
    if (outline != null) xml.append("<w:outlineLvl w:val=\"$outline\"/>")
    return xml.append("</w:pPr>").toString()
}

/** A cell's width and exact line in twips, and its left and right edges on the page in points. */
private class CellPlace(val widthTwips: Int, val leftPt: Float, val rightPt: Float, val lineTwips: Int)

/**
 * Fixed layout, no borders, no cell margins, and the column grid; opens the table. [rtl]: the
 * columns run from the right (w:bidiVisual, before w:tblW in ECMA-376 §17.4.60's order).
 */
internal fun tableProperties(widths: List<Int>, rtl: Boolean, indentTwips: Int = 0): String {
    val xml = StringBuilder("<w:tbl><w:tblPr>").append(if (rtl) "<w:bidiVisual/>" else "")
        .append("<w:tblW w:w=\"${widths.sum()}\" w:type=\"dxa\"/>")
        .append("<w:tblInd w:w=\"$indentTwips\" w:type=\"dxa\"/><w:tblBorders>")
    TABLE_BORDERS.forEach { xml.append("<w:$it w:val=\"nil\"/>") }
    xml.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar>")
    CELL_SIDES.forEach { xml.append("<w:$it w:w=\"0\" w:type=\"dxa\"/>") }
    xml.append("</w:tblCellMar></w:tblPr><w:tblGrid>")
    widths.forEach { xml.append("<w:gridCol w:w=\"$it\"/>") }
    return xml.append("</w:tblGrid>").toString()
}

/**
 * Schema order (ECMA-376 §17.3.1.26): tabs, bidi, spacing, ind. Segments sharing a line (a wide
 * merged column) each start at a tab stop where they started in the PDF. A right-to-left line is
 * indented from the cell's right edge: in a bidi paragraph w:left is the leading (right) side.
 */
private fun cellParagraphProperties(segments: List<Segment>, place: CellPlace, beforeTwips: Int): String {
    val first = segments.firstOrNull()
    val rtl = first?.rtl == true
    val xml = StringBuilder("<w:pPr>")
    val stops = if (rtl) emptyList() else segments.drop(1).map { ((it.u0 - place.leftPt) * TWIPS).roundToInt() }
    if (stops.isNotEmpty()) {
        xml.append(stops.joinToString("", "<w:tabs>", "</w:tabs>") { "<w:tab w:val=\"left\" w:pos=\"$it\"/>" })
    }
    if (rtl) xml.append("<w:bidi/>")
    xml.append(
        "<w:spacing w:before=\"$beforeTwips\" w:after=\"0\" w:line=\"${place.lineTwips}\" w:lineRule=\"exact\"/>"
    )
    val indent = when {
        first == null -> 0
        rtl -> ((place.rightPt - segments.maxOf { it.u1 }) * TWIPS).roundToInt()
        else -> ((first.u0 - place.leftPt) * TWIPS).roundToInt()
    }
    if (indent > 0) xml.append("<w:ind w:left=\"$indent\"/>")
    return xml.append("</w:pPr>").toString()
}

/** A run, with properties in the order the WordprocessingML schema requires (ECMA-376 §17.3.2). */
internal fun runXml(run: OutRun): String =
    "<w:r><w:rPr>${fontProperties(run.style)}${adjustments(run)}</w:rPr>" +
        "<w:t xml:space=\"preserve\">${xmlText(run.text)}</w:t></w:r>"

/** rFonts, b, i, noProof, color. */
private fun fontProperties(s: TextStyle): String {
    val font = xmlText(s.family.officeName)
    val complex = if (s.arabic) OfficeFamily.ARABIC_NAME else s.family.officeName
    val xml = StringBuilder("<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$complex\"/>")
    if (s.bold) xml.append("<w:b/><w:bCs/>")
    if (s.italic) xml.append("<w:i/><w:iCs/>")
    xml.append("<w:noProof/>")
    val rgb = s.argb and RGB_MASK
    if (rgb != 0) xml.append("<w:color w:val=\"%06X\"/>".format(rgb))
    return xml.toString()
}

/** spacing, w, kern, position, sz, szCs, rtl, lang. */
private fun adjustments(run: OutRun): String {
    val s = run.style
    val xml = StringBuilder()
    if (run.spacingTwips != 0) xml.append("<w:spacing w:val=\"${run.spacingTwips}\"/>")
    if (run.scalePercent != FULL_SCALE) xml.append("<w:w w:val=\"${run.scalePercent}\"/>")
    // Kerning off: widths are measured unkerned, and LibreOffice kerns DOCX text unless told.
    // Not for Arabic: with kerning off LibreOffice also draws a final yeh without its dots.
    if (!s.arabic) xml.append("<w:kern w:val=\"0\"/>")
    if (run.raiseHalfPoints != 0) xml.append("<w:position w:val=\"${run.raiseHalfPoints}\"/>")
    val half = (s.sizePt * 2).roundToInt().coerceIn(MIN_HALF_POINTS, MAX_HALF_POINTS)
    xml.append("<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/>")
    if (s.arabic) xml.append("<w:rtl/><w:lang w:bidi=\"ar-SA\"/>")
    return xml.toString()
}

private fun emu(points: Float): Long = (points * EMU_PER_POINT).roundToLong()

private val CONTENT_DEFAULTS = mapOf(
    "rels" to TYPE_RELATIONSHIPS,
    "xml" to "application/xml",
    "jpg" to "image/jpeg",
    "png" to "image/png"
)
private val DOCUMENT_TYPES = mapOf("/word/document.xml" to TYPE_DOCUMENT, "/word/styles.xml" to TYPE_STYLES)
