package com.skeler.scanely.core.pdftools.pdf2office

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val NS_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val NS_WORD_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
private const val NS_PICTURE = "http://schemas.openxmlformats.org/drawingml/2006/picture"
private const val TYPE_DOCUMENT = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
private const val TYPE_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
private const val REL_STYLES = "$NS_OFFICE_RELS/styles"

/**
 * A .docx written as it goes: `word/document.xml` is streamed straight into the zip one PDF page
 * at a time, so a 500-page document never sits in memory as one string. Scanned-page images
 * can't share that zip entry, so the caller renders them to files and they are copied in by
 * [finish], after the document part closes.
 *
 * The page size and margins of the whole document come from the first PDF page; Word holds one
 * flowing document, not fixed pages, so nothing is placed by coordinates.
 */
internal class DocxWriter(out: OutputStream, pageWidthPt: Float, pageHeightPt: Float) {

    private val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER_BYTES))
    private val writer: Writer = OutputStreamWriter(zip, Charsets.UTF_8)
    private val pageWidthTwips = (pageWidthPt.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT) * TWIPS_PER_POINT).roundToInt()
    private val pageHeightTwips = (pageHeightPt.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT) * TWIPS_PER_POINT).roundToInt()
    private val contentWidthPt = pageWidthTwips / TWIPS_PER_POINT - 2 * MARGIN_PT
    private val contentHeightPt = pageHeightTwips / TWIPS_PER_POINT - 2 * MARGIN_PT

    private val images = mutableListOf<File>()
    private var pages = 0

    init {
        zip.putNextEntry(ZipEntry("word/document.xml"))
        writer.write(XML_HEADER)
        writer.write(
            "<w:document xmlns:w=\"$NS_WORD\" xmlns:r=\"$NS_OFFICE_RELS\" xmlns:wp=\"$NS_WORD_DRAWING\" " +
                "xmlns:a=\"$NS_DRAWING\" xmlns:pic=\"$NS_PICTURE\"><w:body>"
        )
    }

    fun addPage(layout: PageLayout) {
        startPage()
        layout.blocks.forEach { block ->
            when (block) {
                is ParagraphBlock -> writer.write(paragraph(block))
                is TableBlock -> writer.write(table(block))
            }
        }
    }

    /** [jpeg] must stay on disk until [finish] has copied it in. */
    fun addImagePage(jpeg: File, widthPx: Int, heightPx: Int) {
        startPage()
        images += jpeg
        val id = images.size
        val scale = min(contentWidthPt / widthPx.coerceAtLeast(1), contentHeightPt / heightPx.coerceAtLeast(1))
        val cx = (widthPx * scale * EMU_PER_POINT).roundToLong().coerceAtLeast(1)
        val cy = (heightPx * scale * EMU_PER_POINT).roundToLong().coerceAtLeast(1)
        writer.write(
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
                "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:docPr id=\"$id\" name=\"Page image $id\"/>" +
                "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                "<a:graphic><a:graphicData uri=\"$NS_PICTURE\"><pic:pic><pic:nvPicPr>" +
                "<pic:cNvPr id=\"$id\" name=\"image$id.jpg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"${imageRelId(id)}\"/><a:stretch><a:fillRect/></a:stretch>" +
                "</pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData>" +
                "</a:graphic></wp:inline></w:drawing></w:r></w:p>"
        )
    }

    /** Closes every part and the zip. The writer is unusable afterwards. */
    fun finish() {
        // Word refuses a body with no paragraph at all.
        if (pages == 0) writer.write("<w:p/>")
        writer.write(
            "<w:sectPr><w:pgSz w:w=\"$pageWidthTwips\" w:h=\"$pageHeightTwips\"/>" +
                "<w:pgMar w:top=\"$MARGIN_TWIPS\" w:right=\"$MARGIN_TWIPS\" w:bottom=\"$MARGIN_TWIPS\" " +
                "w:left=\"$MARGIN_TWIPS\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>" +
                "</w:body></w:document>"
        )
        writer.flush()
        zip.closeEntry()

        images.forEachIndexed { index, file -> zip.copyEntry("word/media/image${index + 1}.jpg", file) }
        zip.writeEntry("word/styles.xml", STYLES)
        zip.writeEntry(
            "word/_rels/document.xml.rels",
            relationships(
                listOf(relationship("rIdStyles", REL_STYLES, "styles.xml")) +
                    images.indices.map { relationship(imageRelId(it + 1), REL_IMAGE, "media/image${it + 1}.jpg") }
            )
        )
        zip.writeEntry("_rels/.rels", packageRelationships("word/document.xml"))
        zip.writeEntry(
            "[Content_Types].xml",
            contentTypes(
                defaults = mapOf("rels" to TYPE_RELATIONSHIPS, "xml" to "application/xml", "jpg" to "image/jpeg"),
                overrides = mapOf("/word/document.xml" to TYPE_DOCUMENT, "/word/styles.xml" to TYPE_STYLES)
            )
        )
        // Closing the writer flushes and closes the zip under it.
        writer.close()
    }

    private fun startPage() {
        if (pages > 0) writer.write("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        pages++
    }

    private fun paragraph(block: ParagraphBlock): String {
        val properties = when (block.kind) {
            BlockKind.HEADING_1 -> "<w:pStyle w:val=\"Heading1\"/>"
            BlockKind.HEADING_2 -> "<w:pStyle w:val=\"Heading2\"/>"
            BlockKind.BULLET, BlockKind.NUMBERED ->
                "<w:pStyle w:val=\"ListParagraph\"/>" +
                    "<w:ind w:left=\"$LIST_INDENT_TWIPS\" w:hanging=\"$LIST_HANGING_TWIPS\"/>"
            BlockKind.BODY -> ""
        }
        val text = if (block.kind == BlockKind.BULLET) "•\t" + block.text else block.text
        val pPr = if (properties.isEmpty()) "" else "<w:pPr>$properties</w:pPr>"
        return "<w:p>$pPr${run(text, block.fontSize)}</w:p>"
    }

    private fun table(block: TableBlock): String = buildString {
        val columns = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val columnTwips = ((contentWidthPt * TWIPS_PER_POINT) / columns).roundToInt().coerceAtLeast(1)
        append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
        append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" ")
        append("w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        repeat(columns) { append("<w:gridCol w:w=\"$columnTwips\"/>") }
        append("</w:tblGrid>")
        block.rows.forEach { row ->
            append("<w:tr>")
            for (column in 0 until columns) {
                append("<w:tc><w:tcPr><w:tcW w:w=\"$columnTwips\" w:type=\"dxa\"/></w:tcPr><w:p>")
                val text = row.getOrElse(column) { "" }
                if (text.isNotEmpty()) append(run(text, block.fontSize))
                append("</w:p></w:tc>")
            }
            append("</w:tr>")
        }
        // Two tables with nothing between them merge into one in Word.
        append("</w:tbl><w:p/>")
    }

    /** A size of 0 (plain-line pages carry no geometry) leaves the style's size in charge. */
    private fun run(text: String, fontSizePt: Float): String {
        val size = if (fontSizePt > 0f) {
            val halfPoints = (fontSizePt * 2).roundToInt().coerceIn(MIN_HALF_POINTS, MAX_HALF_POINTS)
            "<w:rPr><w:sz w:val=\"$halfPoints\"/><w:szCs w:val=\"$halfPoints\"/></w:rPr>"
        } else {
            ""
        }
        val parts = text.split('\t')
        return parts.mapIndexed { index, part ->
            val tab = if (index > 0) "<w:tab/>" else ""
            "$tab<w:t xml:space=\"preserve\">${xmlText(part)}</w:t>"
        }.joinToString("", prefix = "<w:r>$size", postfix = "</w:r>")
    }

    private fun imageRelId(index: Int) = "rIdImage$index"

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val TWIPS_PER_POINT = 20f
        const val MARGIN_PT = 36f
        const val MARGIN_TWIPS = 720
        const val MIN_PAGE_PT = 144f
        const val MAX_PAGE_PT = 1584f
        const val LIST_INDENT_TWIPS = 720
        const val LIST_HANGING_TWIPS = 360
        const val MIN_HALF_POINTS = 8
        const val MAX_HALF_POINTS = 192

        val STYLES = XML_HEADER +
            "<w:styles xmlns:w=\"$NS_WORD\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" " +
            "w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>" +
            "<w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>" +
            "<w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>" +
            "<w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/>" +
            "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/>" +
            "<w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>" +
            "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/>" +
            "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/>" +
            "<w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr>" +
            "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/>" +
            "<w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/>" +
            "<w:ind w:left=\"720\"/></w:pPr></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/>" +
            "<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/>" +
            "<w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/>" +
            "<w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>" +
            "<w:basedOn w:val=\"TableNormal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" " +
            "w:lineRule=\"auto\"/></w:pPr><w:tblPr><w:tblBorders>" +
            "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
            "</w:tblBorders></w:tblPr></w:style>" +
            "</w:styles>"
    }
}
