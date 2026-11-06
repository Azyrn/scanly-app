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
private const val TYPE_DOCUMENT = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
private const val TYPE_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
private const val REL_STYLES = "$NS_OFFICE_RELS/styles"

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

    private val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_BYTES))
    private val writer: Writer = OutputStreamWriter(zip, Charsets.UTF_8)

    /** Every picture in the package, in order: (file, is PNG). */
    private val media = mutableListOf<Pair<File, Boolean>>()
    private var drawingId = 0
    private var pendingSection: String? = null

    init {
        zip.putNextEntry(ZipEntry("word/document.xml"))
        writer.write(XML_HEADER)
        writer.write(
            "<w:document xmlns:w=\"$NS_WORD\" xmlns:r=\"$NS_OFFICE_RELS\" xmlns:wp=\"$NS_WP\" " +
                "xmlns:a=\"$NS_DRAWING\" xmlns:pic=\"$NS_PICTURE\" xmlns:wps=\"$NS_WPS\" xmlns:mc=\"$NS_MC\" " +
                "mc:Ignorable=\"wps\"><w:body>"
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
        // The previous page's section closes in this page's first paragraph's predecessor: a
        // section break lives in the last paragraph of the section it ends.
        pendingSection?.let { writer.write(it) }
        val content = StringBuilder()
        // Text hidden behind the picture goes first: LibreOffice stacks objects of the same layer in
        // document order and ignores relativeHeight, so anything written after the picture covers it.
        // Every box also gets its own ascending relativeHeight (below the picture's, which is written
        // later): a tie (all 0) left LibreOffice's order unstable and the last lines showed through.
        if (layout?.behindPicture == true) content.append(boxes(layout, heightPt, behind = true))
        content.append(pictures(widthPt, heightPt, background, pictures))
        if (layout != null && !layout.behindPicture) content.append(boxes(layout, heightPt, behind = false))
        val section = sectionProperties(widthPt, heightPt)
        writer.write("<w:p><w:pPr>$TINY_LINE</w:pPr>$content</w:p>")
        // Held back: written as this page's closing paragraph when another page follows, or as the
        // body's final sectPr by finish().
        pendingSection = "<w:p><w:pPr>$TINY_LINE$section</w:pPr></w:p>"
        lastSection = section
    }

    private var lastSection: String = sectionProperties(DEFAULT_WIDTH_PT, DEFAULT_HEIGHT_PT)

    /** Closes every part and the zip. The writer is unusable afterwards. */
    fun finish() {
        if (pendingSection == null) writer.write("<w:p/>")
        writer.write("$lastSection</w:body></w:document>")
        writer.flush()
        zip.closeEntry()
        media.forEachIndexed { index, (file, _) ->
            zip.copyEntry(
                "word/${mediaName(index + 1).let { "media/$it" }}",
                file
            )
        }
        zip.writeEntry("word/styles.xml", STYLES)
        zip.writeEntry(
            "word/_rels/document.xml.rels",
            relationships(
                listOf(relationship("rIdStyles", REL_STYLES, "styles.xml")) +
                    media.indices.map { relationship(imageRelId(it + 1), REL_IMAGE, "media/${mediaName(it + 1)}") }
            )
        )
        zip.writeEntry("_rels/.rels", packageRelationships("word/document.xml"))
        zip.writeEntry(
            "[Content_Types].xml",
            contentTypes(
                defaults = mapOf(
                    "rels" to TYPE_RELATIONSHIPS,
                    "xml" to "application/xml",
                    "jpg" to "image/jpeg",
                    "png" to "image/png"
                ),
                overrides = mapOf("/word/document.xml" to TYPE_DOCUMENT, "/word/styles.xml" to TYPE_STYLES)
            )
        )
        writer.close()
    }

    // --- page parts -------------------------------------------------------------------------

    private fun boxes(layout: ExactPageLayout, heightPt: Float, behind: Boolean): String {
        val xml = StringBuilder()
        layout.forEachInReadingOrder(
            onBlock = { xml.append(textBox(it, heightPt, behind)) },
            onTable = { xml.append(tableBox(it, behind)) }
        )
        return xml.toString()
    }

    /** The page background (when there is one) and the page's own pictures, in that order. */
    private fun pictures(widthPt: Float, heightPt: Float, background: File?, pictures: List<PagePicture>): String {
        val xml = StringBuilder()
        if (background != null) {
            media += background to background.name.endsWith(".png")
            xml.append(picture(media.size, 0f, 0f, widthPt, heightPt, "Page background", locked = true))
        }
        pictures.forEach {
            media += it.file to it.png
            xml.append(
                picture(media.size, it.xPt, it.yPt, it.widthPt, it.heightPt, "Picture ${media.size}", locked = false)
            )
        }
        return xml.toString()
    }

    private fun sectionProperties(widthPt: Float, heightPt: Float): String {
        val w = (widthPt * TWIPS).roundToInt()
        val h = (heightPt * TWIPS).roundToInt()
        val orient = if (w > h) " w:orient=\"landscape\"" else ""
        return "<w:sectPr><w:pgSz w:w=\"$w\" w:h=\"$h\"$orient/>" +
            "<w:pgMar w:top=\"0\" w:right=\"0\" w:bottom=\"0\" w:left=\"0\"" +
            " w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
            "</w:sectPr>"
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
                Anchor(id, behind = true, x = emu(xPt), y = emu(yPt), cx = cx, cy = cy, name = name, locked = locked)
            ) +
            "<a:graphic><a:graphicData uri=\"$NS_PICTURE\"><pic:pic><pic:nvPicPr>" +
            "<pic:cNvPr id=\"$id\" name=\"${mediaName(index)}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"${imageRelId(
                index
            )}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>" +
            "</wp:anchor></w:drawing></w:r>"
    }

    private fun mediaName(index: Int) = "media$index." + if (media[index - 1].second) "png" else "jpg"

    /**
     * One block as a text box. Geometry follows ExactLayout's model; see [boxGeometry]. A level
     * left-to-right prose block of several lines is written to reflow when edited: its lines are
     * one paragraph (a space, or a soft hyphen for a word hyphenated across lines, where the PDF
     * broke) in a box that wraps at the widest line and grows with its text, so each line still
     * breaks where it did. Where a line ends short enough that the next line's first word would
     * have fitted, a new paragraph starts instead, so Word does not pull that word up.
     */
    internal fun textBox(block: Block, pageHeightPt: Float, behind: Boolean = false): String {
        val reflow = reflows(block, behind)
        val g = boxGeometry(block, pageHeightPt, reflow)
        val id = ++drawingId
        val paragraph = StringBuilder("<w:p>").append(paragraphProperties(block, g))
        val leftPt = g.x / EMU_PER_POINT.toFloat()
        val rightPt = (g.x + g.cx) / EMU_PER_POINT.toFloat()
        block.lines.forEachIndexed { index, line ->
            val runs = ExactRuns.runs(line, g.lineStarts[index], measurer).toMutableList()
            val next = block.lines.getOrNull(index + 1)
            val join = if (reflow && next != null) lineJoin(line, next, leftPt, rightPt) else Join.BREAK
            if (join == Join.SOFT_HYPHEN && runs.last().text.endsWith("-")) {
                val last = runs.removeAt(runs.lastIndex)
                if (last.text.length > 1) {
                    runs += OutRun(last.text.dropLast(1), last.style, last.spacingTwips, last.scalePercent, last.raiseHalfPoints)
                }
            }
            runs.forEach { paragraph.append(runXml(it)) }
            if (next == null) return@forEachIndexed
            when (join) {
                Join.BREAK -> paragraph.append("<w:r><w:br/></w:r>")
                Join.SPACE -> paragraph.append(runXml(OutRun(" ", runs.last().style, 0, FULL_SCALE, 0)))
                Join.SOFT_HYPHEN -> paragraph.append(
                    "<w:r><w:rPr>${fontProperties(runs.last().style)}${adjustments(
                        OutRun("-", runs.last().style, 0, FULL_SCALE, 0)
                    )}</w:rPr><w:softHyphen/></w:r>"
                )
                Join.PARAGRAPH -> paragraph.append("</w:p><w:p>").append(paragraphProperties(block, g, first = false))
            }
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
            locked = false
        )
        return textShape(anchor, paragraph, rotation = g.rotation, vertical = g.vertical, grows = reflow)
    }

    private enum class Join { BREAK, SPACE, SOFT_HYPHEN, PARAGRAPH }

    private fun reflows(block: Block, behind: Boolean): Boolean =
        !behind && block.lines.size > 1 && block.angleDeg == 0 &&
            block.lines.none { (it.bidi && !block.rtl) || it.rtl != block.rtl || it.tokens.isEmpty() } &&
            // A long link broken where it ran out of room: the PDF put no space there and Word would
            // break it at its own place (a slash or hyphen), so such a block keeps its line breaks.
            block.lines.zipWithNext().none { (line, next) ->
                unbreakable(line.tokens.last().text) || unbreakable(next.tokens.first().text) ||
                    next.tokens.first().text.first() in NO_WORD_START
            }

    /** How [line] continues into [next] in a reflowing block whose box spans [leftPt]..[rightPt]. */
    private fun lineJoin(line: Segment, next: Segment, leftPt: Float, rightPt: Float): Join {
        val lastText = line.tokens.last().text
        val firstText = next.tokens.first().text
        val hyphen = !line.rtl && lastText.length > 1 && lastText.endsWith("-") &&
            lastText[lastText.length - 2].isLetter() && firstText.firstOrNull()?.isLowerCase() == true
        val style = next.tokens.first().style
        val space = if (hyphen) 0f else measurer.width(" ", style.family, style.arabic, style.bold, style.italic, style.sizePt)
        val word = listOf(next.tokens.first()) + next.tokens.drop(1).takeWhile { !it.spaceBefore }
        val wordWidth = word.maxOf { it.u1 } - word.minOf { it.u0 }
        val room = when {
            line.rtl -> line.u0 - leftPt
            hyphen -> rightPt - (line.tokens.last().u1 - hyphenWidth(line.tokens.last().style))
            else -> rightPt - line.u1
        }
        val fits = space + wordWidth <= room
        return when {
            fits -> if (hyphen) Join.BREAK else Join.PARAGRAPH
            hyphen -> Join.SOFT_HYPHEN
            else -> Join.SPACE
        }
    }

    private fun unbreakable(text: String) =
        "://" in text || "www." in text || (text.length > LONG_TOKEN && text.any { it in LINK_CHARS })

    private fun hyphenWidth(s: TextStyle) = measurer.width("-", s.family, s.arabic, s.bold, s.italic, s.sizePt)

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
        val xml = StringBuilder(tableProperties(widths))
        table.rows.forEachIndexed { r, row ->
            // Never taller than the row as written: a line a twip over grows the row, and that adds up.
            val lineTwips = (geometry.lineHeightsPt[r] * TWIPS).roundToInt().coerceIn(1, heights[r])
            xml.append("<w:tr><w:trPr><w:trHeight w:val=\"${heights[r]}\" w:hRule=\"atLeast\"/></w:trPr>")
            row.cells.forEachIndexed { c, cell -> xml.append(cellXml(cell, widths[c], geometry.lefts[c], lineTwips)) }
            xml.append("</w:tr>")
        }
        // Block content must end in a paragraph; a 1 pt one adds nothing to the box.
        xml.append("</w:tbl><w:p><w:pPr>$TINY_LINE</w:pPr></w:p>")
        val cx = emu(geometry.widthsPt.sum()).coerceAtLeast(1)
        val cy = emu(geometry.heightsPt.sum()).coerceAtLeast(1)
        val anchor = Anchor(
            id,
            behind = behind,
            x = emu(table.box.left),
            y = emu(geometry.top),
            cx = cx,
            cy = cy,
            name = "Table $id"
        )
        return textShape(anchor, xml, rotation = 0, vertical = null)
    }

    /** One cell: its text from where it started in the PDF, segments sharing it at tab stops. */
    private fun cellXml(cell: ExactTableCell, widthTwips: Int, leftPt: Float, lineTwips: Int): String {
        val xml = StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"$widthTwips\" w:type=\"dxa\"/></w:tcPr><w:p>")
        xml.append(cellParagraphProperties(cell, leftPt, lineTwips))
        val rtl = cell.segments.firstOrNull()?.rtl == true
        cell.segments.forEachIndexed { s, segment ->
            if (s > 0) xml.append(if (rtl) "<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>" else "<w:r><w:tab/></w:r>")
            val start = if (segment.rtl) segment.u1 else segment.u0
            ExactRuns.runs(segment, start, measurer).forEach { xml.append(runXml(it)) }
        }
        return xml.append("</w:p></w:tc>").toString()
    }

    private fun imageRelId(index: Int) = "rIdMedia$index"

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val LONG_TOKEN = 20
        private const val LINK_CHARS = "/%=&_?#"
        private const val NO_WORD_START = "=/%&_?#)]}.,;:"
        private const val DEFAULT_WIDTH_PT = 595f
        private const val DEFAULT_HEIGHT_PT = 842f

        private fun emu(points: Float): Long = (points * EMU_PER_POINT).roundToLong()

        private val STYLES = XML_HEADER +
            "<w:styles xmlns:w=\"$NS_WORD\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:cs=\"Times New Roman\"/>" +
            "<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>" +
            "</w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
            "<w:qFormat/></w:style></w:styles>"
    }
}

// --- XML parts that need no writer state ----------------------------------------------------

private const val TWIPS = 20f
private const val RGB_MASK = 0xFFFFFF
private const val FULL_SCALE = 100
private const val MIN_HALF_POINTS = 2
private const val MAX_HALF_POINTS = 3276

/** Where Word starts its relativeHeight values (0x0F000000). */
private const val Z_BASE = 251_658_240L

private val TABLE_BORDERS = listOf("top", "left", "bottom", "right", "insideH", "insideV")
private val CELL_SIDES = listOf("top", "left", "bottom", "right")

/** The anchor paragraph itself: 1 pt tall, so it never pushes a page over. */
private const val TINY_LINE = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>"

/** A drawing's place on the page in EMUs; its id doubles as its stacking order. */
private class Anchor(
    val id: Int,
    val behind: Boolean,
    val x: Long,
    val y: Long,
    val cx: Long,
    val cy: Long,
    val name: String,
    val locked: Boolean = false
)

// Offset like Word's own values: LibreOffice puts 0 and 1 above everything (tdf#159158), which
// put the first page's background over that page's pictures. Stays far below Int.MAX_VALUE,
// past which LibreOffice misorders behind-text drawings (tdf#120760).
private fun anchorOpen(a: Anchor) =
    "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"${Z_BASE + a.id}\" " +
        "behindDoc=\"${if (a.behind) 1 else 0}\" locked=\"${if (a.locked) 1 else 0}\" layoutInCell=\"1\" " +
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
        (if (grows) "<a:spAutoFit/>" else "<a:noAutofit/>") + "</wps:bodyPr></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"
}

/** A block's exact line pitch, indents and direction. */
private fun paragraphProperties(block: Block, g: BoxGeometry, first: Boolean = true): String {
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
    return xml.append("</w:pPr>").toString()
}

/** Fixed layout, no borders, no cell margins, and the column grid; opens the table. */
private fun tableProperties(widths: List<Int>): String {
    val xml = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"${widths.sum()}\" w:type=\"dxa\"/>")
        .append("<w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblBorders>")
    TABLE_BORDERS.forEach { xml.append("<w:$it w:val=\"nil\"/>") }
    xml.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar>")
    CELL_SIDES.forEach { xml.append("<w:$it w:w=\"0\" w:type=\"dxa\"/>") }
    xml.append("</w:tblCellMar></w:tblPr><w:tblGrid>")
    widths.forEach { xml.append("<w:gridCol w:w=\"$it\"/>") }
    return xml.append("</w:tblGrid>").toString()
}

/**
 * Schema order (ECMA-376 §17.3.1.26): tabs, bidi, spacing, ind. Segments sharing a cell (a wide
 * merged column) each start at a tab stop where they started in the PDF.
 */
private fun cellParagraphProperties(cell: ExactTableCell, leftPt: Float, lineTwips: Int): String {
    val first = cell.segments.firstOrNull()
    val rtl = first?.rtl == true
    val xml = StringBuilder("<w:pPr>")
    val stops = if (rtl) emptyList() else cell.segments.drop(1).map { ((it.u0 - leftPt) * TWIPS).roundToInt() }
    if (stops.isNotEmpty()) {
        xml.append(stops.joinToString("", "<w:tabs>", "</w:tabs>") { "<w:tab w:val=\"left\" w:pos=\"$it\"/>" })
    }
    if (rtl) xml.append("<w:bidi/>")
    xml.append("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"$lineTwips\" w:lineRule=\"exact\"/>")
    val indent = if (first == null || rtl) 0 else ((first.u0 - leftPt) * TWIPS).roundToInt()
    if (indent > 0) xml.append("<w:ind w:left=\"$indent\"/>")
    return xml.append("</w:pPr>").toString()
}

/** A run, with properties in the order the WordprocessingML schema requires (ECMA-376 §17.3.2). */
private fun runXml(run: OutRun): String =
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
