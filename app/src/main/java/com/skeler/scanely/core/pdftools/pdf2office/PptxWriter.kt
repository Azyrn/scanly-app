package com.skeler.scanely.core.pdftools.pdf2office

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val NS_PRESENTATION = "http://schemas.openxmlformats.org/presentationml/2006/main"
private const val PML = "application/vnd.openxmlformats-officedocument.presentationml"
private const val TYPE_PRESENTATION = "$PML.presentation.main+xml"
private const val TYPE_SLIDE = "$PML.slide+xml"
private const val TYPE_MASTER = "$PML.slideMaster+xml"
private const val TYPE_LAYOUT = "$PML.slideLayout+xml"
private const val TYPE_PRES_PROPS = "$PML.presProps+xml"
private const val TYPE_TABLE_STYLES = "$PML.tableStyles+xml"
private const val TYPE_THEME = "application/vnd.openxmlformats-officedocument.theme+xml"
private const val REL_SLIDE = "$NS_OFFICE_RELS/slide"
private const val REL_MASTER = "$NS_OFFICE_RELS/slideMaster"
private const val REL_LAYOUT = "$NS_OFFICE_RELS/slideLayout"
private const val REL_THEME = "$NS_OFFICE_RELS/theme"
private const val REL_PRES_PROPS = "$NS_OFFICE_RELS/presProps"
private const val REL_TABLE_STYLES = "$NS_OFFICE_RELS/tableStyles"
private const val NAMESPACES = "xmlns:a=\"$NS_DRAWING\" xmlns:r=\"$NS_OFFICE_RELS\" xmlns:p=\"$NS_PRESENTATION\""

/**
 * A .pptx written slide by slide: each slide and its image are complete zip entries the moment
 * the page is done, and the parts that list slides (presentation, its relationships, the content
 * types) are written by [finish] once the count is known.
 *
 * One slide size serves the whole deck, taken from the first page; a page of a different shape is
 * scaled to fit and centred. Text pages become text boxes at the PDF's own positions, one
 * paragraph per original line so a different font can't rewrap a line into its neighbour.
 */
internal class PptxWriter(out: OutputStream, firstPageWidthPt: Float, firstPageHeightPt: Float) {

    private val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_BYTES))
    private val slideWidth: Long
    private val slideHeight: Long
    private var slides = 0
    private var images = 0

    init {
        // PowerPoint only accepts slide sides between 1 and 56 inches.
        val width = firstPageWidthPt.coerceAtLeast(1f) * EMU_PER_POINT
        val height = firstPageHeightPt.coerceAtLeast(1f) * EMU_PER_POINT
        val grow = max(1f, max(MIN_SLIDE_EMU / width, MIN_SLIDE_EMU / height))
        val shrink = min(1f, min(MAX_SLIDE_EMU / (width * grow), MAX_SLIDE_EMU / (height * grow)))
        slideWidth = (width * grow * shrink).roundToLong().coerceIn(MIN_SLIDE_EMU.toLong(), MAX_SLIDE_EMU.toLong())
        slideHeight = (height * grow * shrink).roundToLong().coerceIn(MIN_SLIDE_EMU.toLong(), MAX_SLIDE_EMU.toLong())
    }

    fun addTextSlide(layout: PageLayout, pageWidthPt: Float, pageHeightPt: Float) {
        val frame = Frame(pageWidthPt, pageHeightPt)
        val shapes = StringBuilder()
        var shapeId = FIRST_SHAPE_ID
        if (layout.positioned) {
            layout.blocks.forEach { block ->
                val lines = when (block) {
                    is ParagraphBlock -> block.lines.map { if (block.kind == BlockKind.BULLET) "• $it" else it }
                    is TableBlock -> block.rows.map { row -> row.joinToString("\t") }
                }
                val bold = block is ParagraphBlock &&
                    (block.kind == BlockKind.HEADING_1 || block.kind == BlockKind.HEADING_2)
                shapes.append(textBox(shapeId++, frame, block.box, lines, block.fontSize, bold, wrap = false))
            }
        } else if (!layout.isEmpty) {
            // Too dense to place: one box over the page, shrunk to fit by PowerPoint.
            val lines = layout.blocks.filterIsInstance<ParagraphBlock>().flatMap { it.lines }
            val page = LayoutBox(MARGIN_PT, MARGIN_PT, pageWidthPt - MARGIN_PT, pageHeightPt - MARGIN_PT)
            shapes.append(textBox(shapeId, frame, page, lines, DENSE_FONT_PT, bold = false, wrap = true))
        }
        writeSlide(shapes.toString(), image = null)
    }

    /** [jpeg] is copied in at once; the caller may delete it as soon as this returns. */
    fun addImageSlide(pageWidthPt: Float, pageHeightPt: Float, jpeg: File) {
        images++
        val media = "image$images.jpg"
        zip.copyEntry("ppt/media/$media", jpeg)

        val frame = Frame(pageWidthPt, pageHeightPt)
        val picture = "<p:pic><p:nvPicPr><p:cNvPr id=\"$FIRST_SHAPE_ID\" name=\"Page image\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"rIdImage\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr>${xfrm(frame.x(0f), frame.y(0f), frame.length(pageWidthPt), frame.length(pageHeightPt))}" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
        writeSlide(picture, image = media)
    }

    /** Closes every part and the zip. The writer is unusable afterwards. */
    fun finish() {
        writePresentation()
        writeMasterParts()
        zip.writeEntry("_rels/.rels", packageRelationships("ppt/presentation.xml"))
        writeContentTypes()
        zip.close()
    }

    /** The presentation part and its relationships: master, theme, properties and every slide. */
    private fun writePresentation() {
        val slideIds = (1..slides).joinToString("") {
            "<p:sldId id=\"${FIRST_SLIDE_ID + it - 1}\" r:id=\"rIdSlide$it\"/>"
        }
        zip.writeEntry(
            "ppt/presentation.xml",
            XML_HEADER + "<p:presentation $NAMESPACES saveSubsetFonts=\"1\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"$MASTER_ID\" r:id=\"rIdMaster\"/></p:sldMasterIdLst>" +
                (if (slides > 0) "<p:sldIdLst>$slideIds</p:sldIdLst>" else "") +
                "<p:sldSz cx=\"$slideWidth\" cy=\"$slideHeight\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
                "</p:presentation>"
        )
        zip.writeEntry(
            "ppt/_rels/presentation.xml.rels",
            relationships(
                listOf(
                    relationship("rIdMaster", REL_MASTER, "slideMasters/slideMaster1.xml"),
                    relationship("rIdTheme", REL_THEME, "theme/theme1.xml"),
                    relationship("rIdProps", REL_PRES_PROPS, "presProps.xml"),
                    relationship("rIdTables", REL_TABLE_STYLES, "tableStyles.xml")
                ) + (1..slides).map { relationship("rIdSlide$it", REL_SLIDE, "slides/slide$it.xml") }
            )
        )
    }

    /** The fixed parts every slide hangs off: properties, table styles, master, layout, theme. */
    private fun writeMasterParts() {
        zip.writeEntry("ppt/presProps.xml", XML_HEADER + "<p:presentationPr $NAMESPACES/>")
        zip.writeEntry(
            "ppt/tableStyles.xml",
            XML_HEADER + "<a:tblStyleLst xmlns:a=\"$NS_DRAWING\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>"
        )
        zip.writeEntry("ppt/slideMasters/slideMaster1.xml", MASTER)
        zip.writeEntry(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            relationships(
                listOf(
                    relationship("rId1", REL_LAYOUT, "../slideLayouts/slideLayout1.xml"),
                    relationship("rId2", REL_THEME, "../theme/theme1.xml")
                )
            )
        )
        zip.writeEntry("ppt/slideLayouts/slideLayout1.xml", LAYOUT)
        zip.writeEntry(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            relationships(listOf(relationship("rId1", REL_MASTER, "../slideMasters/slideMaster1.xml")))
        )
        zip.writeEntry("ppt/theme/theme1.xml", THEME)
    }

    private fun writeContentTypes() {
        zip.writeEntry(
            "[Content_Types].xml",
            contentTypes(
                defaults = mapOf("rels" to TYPE_RELATIONSHIPS, "xml" to "application/xml", "jpg" to "image/jpeg"),
                overrides = mapOf(
                    "/ppt/presentation.xml" to TYPE_PRESENTATION,
                    "/ppt/presProps.xml" to TYPE_PRES_PROPS,
                    "/ppt/tableStyles.xml" to TYPE_TABLE_STYLES,
                    "/ppt/slideMasters/slideMaster1.xml" to TYPE_MASTER,
                    "/ppt/slideLayouts/slideLayout1.xml" to TYPE_LAYOUT,
                    "/ppt/theme/theme1.xml" to TYPE_THEME
                ) + (1..slides).associate { "/ppt/slides/slide$it.xml" to TYPE_SLIDE }
            )
        )
    }

    private fun writeSlide(shapes: String, image: String?) {
        slides++
        zip.writeEntry(
            "ppt/slides/slide$slides.xml",
            XML_HEADER + "<p:sld $NAMESPACES><p:cSld><p:spTree>$GROUP_HEADER$shapes</p:spTree></p:cSld>" +
                "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
        )
        val rels = listOf(relationship("rIdLayout", REL_LAYOUT, "../slideLayouts/slideLayout1.xml")) +
            listOfNotNull(image?.let { relationship("rIdImage", REL_IMAGE, "../media/$it") })
        zip.writeEntry("ppt/slides/_rels/slide$slides.xml.rels", relationships(rels))
    }

    private fun textBox(
        id: Int,
        frame: Frame,
        box: LayoutBox,
        lines: List<String>,
        fontSizePt: Float,
        bold: Boolean,
        wrap: Boolean
    ): String {
        val size = (fontSizePt.coerceAtLeast(1f) * frame.scale * HUNDREDTHS).roundToInt()
            .coerceIn(MIN_FONT_HUNDREDTHS, MAX_FONT_HUNDREDTHS)
        // A little slack: the substitute font is rarely narrower than the original.
        val width = frame.length(box.width * BOX_SLACK).coerceAtLeast(1)
        val height = frame.length(box.height).coerceAtLeast(1)
        val bodyWrap = if (wrap) "square" else "none"
        val autofit = if (wrap) "<a:normAutofit/>" else "<a:noAutofit/>"
        val boldAttr = if (bold) " b=\"1\"" else ""
        val paragraphs = lines.joinToString("") { line ->
            "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"$size\"$boldAttr dirty=\"0\"/>" +
                "<a:t>${xmlText(line)}</a:t></a:r></a:p>"
        }.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\" sz=\"$size\"/></a:p>" }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/>" +
            "</p:nvSpPr><p:spPr>${xfrm(frame.x(box.left), frame.y(box.top), width, height)}" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"$bodyWrap\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\">" +
            "$autofit</a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
    }

    private fun xfrm(x: Long, y: Long, cx: Long, cy: Long) =
        "<a:xfrm><a:off x=\"$x\" y=\"$y\"/>" +
            "<a:ext cx=\"${cx.coerceAtLeast(1)}\" cy=\"${cy.coerceAtLeast(1)}\"/></a:xfrm>"

    /** Page points → slide EMUs for one page: uniform scale to fit, centred. */
    private inner class Frame(pageWidthPt: Float, pageHeightPt: Float) {
        private val widthEmu = pageWidthPt.coerceAtLeast(1f) * EMU_PER_POINT
        private val heightEmu = pageHeightPt.coerceAtLeast(1f) * EMU_PER_POINT

        /** Slide points per page point. */
        val scale: Float = min(slideWidth / widthEmu, slideHeight / heightEmu)
        private val offsetX = ((slideWidth - widthEmu * scale) / 2f).coerceAtLeast(0f)
        private val offsetY = ((slideHeight - heightEmu * scale) / 2f).coerceAtLeast(0f)

        fun x(pt: Float): Long = (offsetX + pt * EMU_PER_POINT * scale).roundToLong().coerceAtLeast(0)
        fun y(pt: Float): Long = (offsetY + pt * EMU_PER_POINT * scale).roundToLong().coerceAtLeast(0)
        fun length(pt: Float): Long = (pt * EMU_PER_POINT * scale).roundToLong()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MIN_SLIDE_EMU = 914_400f
        const val MAX_SLIDE_EMU = 51_206_400f
        const val FIRST_SHAPE_ID = 2
        const val FIRST_SLIDE_ID = 256
        const val MASTER_ID = 2_147_483_648L
        const val LAYOUT_ID = 2_147_483_649L
        const val MARGIN_PT = 24f
        const val DENSE_FONT_PT = 10f
        const val HUNDREDTHS = 100f
        const val MIN_FONT_HUNDREDTHS = 100
        const val MAX_FONT_HUNDREDTHS = 400_000
        const val BOX_SLACK = 1.08f

        const val GROUP_HEADER = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>" +
            "<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

        val MASTER = XML_HEADER + "<p:sldMaster $NAMESPACES><p:cSld><p:bg><p:bgRef idx=\"1001\">" +
            "<a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GROUP_HEADER</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
            "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" " +
            "folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"$LAYOUT_ID\" r:id=\"rId1\"/>" +
            "</p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"

        val LAYOUT = XML_HEADER + "<p:sldLayout $NAMESPACES type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">" +
            "<p:spTree>$GROUP_HEADER</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sldLayout>"

        private fun color(name: String, hex: String) = "<a:$name><a:srgbClr val=\"$hex\"/></a:$name>"

        private fun three(element: String) = element + element + element

        val THEME = XML_HEADER + "<a:theme xmlns:a=\"$NS_DRAWING\" name=\"Plain\"><a:themeElements>" +
            "<a:clrScheme name=\"Plain\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
            "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            color("dk2", "44546A") + color("lt2", "E7E6E6") + color("accent1", "4472C4") +
            color("accent2", "ED7D31") + color("accent3", "A5A5A5") + color("accent4", "FFC000") +
            color("accent5", "5B9BD5") + color("accent6", "70AD47") + color("hlink", "0563C1") +
            color("folHlink", "954F72") + "</a:clrScheme>" +
            "<a:fontScheme name=\"Plain\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>" +
            "<a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/>" +
            "<a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Plain\"><a:fillStyleLst>" +
            three("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>") + "</a:fillStyleLst><a:lnStyleLst>" +
            three("<a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>") +
            "</a:lnStyleLst><a:effectStyleLst>" + three("<a:effectStyle><a:effectLst/></a:effectStyle>") +
            "</a:effectStyleLst><a:bgFillStyleLst>" +
            three("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>") +
            "</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/>" +
            "</a:theme>"
    }
}
