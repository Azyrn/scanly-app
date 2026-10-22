package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.pptx.Anchor
import com.skeler.scanely.core.pdftools.office2pdf.pptx.Fill
import com.skeler.scanely.core.pdftools.office2pdf.pptx.FontKind
import com.skeler.scanely.core.pdftools.office2pdf.pptx.Geometry
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PictureElement
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PptxFormatException
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PptxReader
import com.skeler.scanely.core.pdftools.office2pdf.pptx.ShapeElement
import com.skeler.scanely.core.pdftools.office2pdf.pptx.TableElement
import com.skeler.scanely.core.pdftools.office2pdf.pptx.TextAlign
import com.skeler.scanely.core.pdftools.office2pdf.pptx.XNode
import com.skeler.scanely.core.pdftools.office2pdf.pptx.autoNumber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Text
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The .pptx reader on a plain JVM: inheritance from layout and master, theme colours and fonts,
 * group transforms, tables, and what it counts instead of drawing. The device parser doesn't exist
 * here, so a DOM-backed one stands in; everything after the parse is the shipping code.
 */
class PptxReaderTest {

    private val dir: File = Files.createTempDirectory("pptx-reader-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun presentationHasSlideSizeOrderAndHiddenFlags() = withDeck { reader ->
        val deck = reader.readPresentation()

        assertEquals(720f, deck.widthPt, 0.01f)
        assertEquals(540f, deck.heightPt, 0.01f)
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide3.xml"),
            deck.slides.map { it.part }
        )
        assertEquals(listOf(false, false, true), deck.slides.map { it.hidden })
    }

    @Test
    fun placeholdersInheritPositionAndStyleFromLayoutAndMaster() = withDeck { reader ->
        reader.readPresentation()
        val slide = reader.readSlide("ppt/slides/slide1.xml")
        val shapes = slide.elements.filterIsInstance<ShapeElement>()

        // Background comes from the master; the master's decoration bar is drawn first.
        assertEquals(Fill.Solid(0xFFFAFAFA.toInt()), slide.background)
        val bar = slide.elements.first() as ShapeElement
        assertEquals(Fill.Solid(0xFFCC5500.toInt()), bar.fill)
        assertNull(bar.text)

        val title = shapes.single { it.text?.paragraphs?.first()?.runs?.first()?.text == "Quarterly Review" }
        // Position from the layout, which overrides the master's.
        assertEquals(50f, title.frame.x, 0.01f)
        assertEquals(40f, title.frame.y, 0.01f)
        val titleRun = title.text!!.paragraphs.single().runs.single()
        assertEquals(44f, titleRun.size, 0.01f)
        assertEquals(FontKind.SERIF, titleRun.font) // +mj-lt → Georgia
        assertEquals(TextAlign.CENTER, title.text!!.paragraphs.single().align)

        val body = shapes.single { it.text?.paragraphs?.first()?.runs?.first()?.text == "First point" }
        // Layout body has no xfrm, so the master's position applies.
        assertEquals(36f, body.frame.x, 0.01f)
        assertEquals(130f, body.frame.y, 0.01f)
        val paragraphs = body.text!!.paragraphs
        assertEquals(listOf("•", "•"), paragraphs.map { it.bullet })
        assertEquals(28f, paragraphs[1].runs.single().size, 0.01f)
        assertEquals(FontKind.SANS, paragraphs[1].runs.single().font)
        assertEquals(18f, paragraphs[0].marginLeft, 0.01f)
        assertEquals(-18f, paragraphs[0].indent, 0.01f)
    }

    @Test
    fun plainShapesPicturesAndColourModifiers() = withDeck { reader ->
        reader.readPresentation()
        val slide = reader.readSlide("ppt/slides/slide1.xml")

        val boxed = slide.elements.filterIsInstance<ShapeElement>()
            .single { it.text?.paragraphs?.first()?.runs?.first()?.text == "Boxed" }
        assertEquals(Fill.Solid(0xFFFF0000.toInt()), boxed.fill)
        assertEquals(Geometry.ROUND_RECT, boxed.geometry)
        assertEquals(Anchor.CENTER, boxed.text!!.anchor)
        assertEquals(0xFF000000.toInt(), boxed.stroke!!.color)
        assertEquals(2f, boxed.stroke!!.width, 0.01f)

        // accent1 336699 at lumMod 50% is half the lightness: 1A334D.
        val dark = slide.elements.filterIsInstance<ShapeElement>().single { it.frame.x == 400f }
        assertColorNear(0xFF1A334D.toInt(), (dark.fill as Fill.Solid).color)
        assertEquals(30f, dark.frame.rotation, 0.01f)

        val picture = slide.elements.filterIsInstance<PictureElement>().single()
        assertEquals("ppt/media/image1.png", picture.part)
        assertEquals(0.1f, picture.crop!!.left, 0.001f)
        assertTrue(picture.frame.flipH)
    }

    @Test
    fun groupChildrenAreMappedOntoTheSlide() = withDeck { reader ->
        reader.readPresentation()
        val slide = reader.readSlide("ppt/slides/slide2.xml")

        val child = slide.elements.filterIsInstance<ShapeElement>().single { it.fill == Fill.Solid(0xFF00FF00.toInt()) }
        // Group at (100,100) 200×200 over a 100×100 child space: (10,10) 20×20 → (120,120) 40×40.
        assertEquals(120f, child.frame.x, 0.01f)
        assertEquals(120f, child.frame.y, 0.01f)
        assertEquals(40f, child.frame.width, 0.01f)
        assertEquals(40f, child.frame.height, 0.01f)
    }

    @Test
    fun tablesAreReadAndChartsCounted() = withDeck { reader ->
        reader.readPresentation()
        val slide = reader.readSlide("ppt/slides/slide2.xml")

        val table = slide.elements.filterIsInstance<TableElement>().single()
        assertEquals(2, table.columns.size)
        assertEquals(2, table.rows.size)
        val header = table.rows[0].cells[0]
        assertEquals("Cell A1", header.text!!.paragraphs.single().runs.single().text)
        assertTrue(header.text!!.paragraphs.single().runs.single().bold)
        assertEquals(Fill.Solid(0xFF336699.toInt()), header.fill)
        assertEquals("Cell B2", table.rows[1].cells[1].text!!.paragraphs.single().runs.single().text)

        assertEquals(1, reader.issues.charts)
        assertEquals(1, reader.issues.diagrams)
        assertTrue(reader.issues.shapesSimplified) // the custom-geometry star
    }

    @Test
    fun unknownFontsAreFlaggedAsReplaced() = withDeck { reader ->
        reader.readPresentation()
        reader.readSlide("ppt/slides/slide1.xml")
        assertFalse(reader.issues.fontsReplaced)
        reader.readSlide("ppt/slides/slide2.xml")
        assertTrue(reader.issues.fontsReplaced)
    }

    @Test
    fun notAZipIsAFormatError() {
        val file = File(dir, "random.pptx").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        assertFormatError { PptxReader(file, ::domParse).use { it.readPresentation() } }
    }

    @Test
    fun zipWithoutPresentationIsAFormatError() {
        val file = zip("empty.pptx", mapOf("hello.txt" to "hi".toByteArray()))
        assertFormatError { PptxReader(file, ::domParse).use { it.readPresentation() } }
    }

    @Test
    fun malformedSlideIsAFormatError() {
        val entries = deckEntries().toMutableMap()
        entries["ppt/slides/slide1.xml"] = "<p:sld xmlns:p=\"x\"><p:cSld>".toByteArray()
        val file = zip("broken.pptx", entries)
        PptxReader(file, ::domParse).use { reader ->
            assertEquals(3, reader.readPresentation().slides.size)
            assertFormatError { reader.readSlide("ppt/slides/slide1.xml") }
            assertNotNull(reader.readSlide("ppt/slides/slide2.xml"))
        }
    }

    @Test
    fun autoNumbersFormatLikePowerPoint() {
        assertEquals("3.", autoNumber("arabicPeriod", 3))
        assertEquals("c)", autoNumber("alphaLcParenR", 3))
        assertEquals("(AB)", autoNumber("alphaUcParenBoth", 28))
        assertEquals("IV.", autoNumber("romanUcPeriod", 4))
    }

    // --- helpers ---

    private fun withDeck(block: (PptxReader) -> Unit) {
        val file = zip("deck.pptx", deckEntries())
        PptxReader(file, ::domParse).use(block)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(24, 16, 8, 0)) {
            val e = expected shr shift and 0xFF
            val a = actual shr shift and 0xFF
            assertTrue("channel at $shift: expected $e, got $a", kotlin.math.abs(e - a) <= 1)
        }
    }

    private fun assertFormatError(block: () -> Unit) {
        try {
            block()
            fail("expected PptxFormatException")
        } catch (expected: PptxFormatException) {
            // expected
        }
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun deckEntries(): Map<String, ByteArray> =
        PptxTestDeck.entries(imagePng = ByteArrayOutputStream().apply { write(ByteArray(64)) }.toByteArray())
}

/** DOM stand-in for the device pull parser, producing the same tree shape. */
private fun domParse(input: InputStream): XNode {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val document = try {
        factory.newDocumentBuilder().parse(input)
    } catch (e: SAXException) {
        throw PptxFormatException("malformed XML", e)
    } catch (e: IOException) {
        throw PptxFormatException("unreadable XML", e)
    }
    return convert(document.documentElement)
}

private fun convert(element: Element): XNode {
    val attrs = HashMap<String, String>()
    val list = element.attributes
    for (i in 0 until list.length) {
        val a = list.item(i)
        if (a.nodeName.startsWith("xmlns")) continue
        attrs[XNode.attrKey(a.namespaceURI, a.localName ?: XNode.localName(a.nodeName))] = a.nodeValue
    }
    val node = XNode(element.localName ?: XNode.localName(element.tagName), attrs)
    val children = element.childNodes
    for (i in 0 until children.length) {
        when (val c = children.item(i)) {
            is Element -> node.children.add(convert(c))
            is Text -> if (XNode.keepsText(node.name)) node.text += c.data
        }
    }
    return node
}

/**
 * A small but complete deck: theme, master (title/body placeholders, a decoration bar, text
 * styles), one layout overriding the title position, and three slides — placeholders, plain
 * shapes and a picture; a table, chart, SmartArt, group and custom shape; and a hidden slide.
 * The same deck is built in the instrumented test, which renders it.
 */
internal object PptxTestDeck {
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private const val EMU = 12_700
    private const val GROUP_HEAD = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr/>"

    fun entries(imagePng: ByteArray, extraSlides: Int = 0): Map<String, ByteArray> {
        val slides = listOf(slide1(), slide2(), slide3()) + List(extraSlides) { simpleSlide("Extra ${it + 1}") }
        val map = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "_rels/.rels" to rels("rId1" to ("officeDocument" to "ppt/presentation.xml")),
            "ppt/presentation.xml" to presentation(slides.size),
            "ppt/_rels/presentation.xml.rels" to rels(
                *(slides.indices.map { "rId${it + 10}" to ("slide" to "slides/slide${it + 1}.xml") } +
                    listOf("rId1" to ("slideMaster" to "slideMasters/slideMaster1.xml"))).toTypedArray()
            ),
            "ppt/theme/theme1.xml" to theme(),
            "ppt/slideMasters/slideMaster1.xml" to master(),
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(
                "rId1" to ("slideLayout" to "../slideLayouts/slideLayout1.xml"),
                "rId2" to ("theme" to "../theme/theme1.xml")
            ),
            "ppt/slideLayouts/slideLayout1.xml" to layout(),
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(
                "rId1" to ("slideMaster" to "../slideMasters/slideMaster1.xml")
            )
        )
        slides.forEachIndexed { i, xml ->
            map["ppt/slides/slide${i + 1}.xml"] = xml
            map["ppt/slides/_rels/slide${i + 1}.xml.rels"] = rels(
                "rId1" to ("slideLayout" to "../slideLayouts/slideLayout1.xml"),
                "rId2" to ("image" to "../media/image1.png"),
                "rId3" to ("chart" to "../charts/chart1.xml")
            )
        }
        val bytes = LinkedHashMap<String, ByteArray>()
        map.forEach { (k, v) -> bytes[k] = v.toByteArray() }
        bytes["ppt/media/image1.png"] = imagePng
        return bytes
    }

    private fun rels(vararg items: Pair<String, Pair<String, String>>): String =
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            items.joinToString("") { (id, rel) ->
                "<Relationship Id=\"$id\" Type=\"$REL${rel.first}\" Target=\"${rel.second}\"/>"
            } + "</Relationships>"

    private fun presentation(count: Int) = "<p:presentation $NS>" +
        "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
        "<p:sldIdLst>" + (0 until count).joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 10}\"/>" } +
        "</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
        "<p:defaultTextStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>" +
        "<a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:defaultTextStyle></p:presentation>"

    private fun theme() = "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"T\">" +
        "<a:themeElements><a:clrScheme name=\"c\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
        "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F1F1F\"/></a:dk2><a:lt2><a:srgbClr val=\"EEEEEE\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"336699\"/></a:accent1><a:accent2><a:srgbClr val=\"CC5500\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"999999\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
        "</a:clrScheme><a:fontScheme name=\"f\"><a:majorFont><a:latin typeface=\"Georgia\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Arial\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"m\"><a:fillStyleLst/><a:lnStyleLst><a:ln w=\"6350\"/><a:ln w=\"12700\"/>" +
        "<a:ln w=\"19050\"/></a:lnStyleLst></a:fmtScheme></a:themeElements></a:theme>"

    private fun master() = "<p:sldMaster $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FAFAFA\"/>" +
        "</a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$GROUP_HEAD" +
        sp(2, ph = "<p:ph type=\"title\"/>", spPr = xfrm(36, 20, 648, 90), body = textBody("Master title")) +
        sp(3, ph = "<p:ph type=\"body\" idx=\"1\"/>", spPr = xfrm(36, 130, 648, 360), body = textBody("Master text")) +
        sp(4, spPr = xfrm(0, 520, 720, 20) + RECT + "<a:solidFill><a:schemeClr val=\"accent2\"/></a:solidFill>") +
        "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" " +
        "accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" " +
        "hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles>" +
        "<p:titleStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"4400\"><a:solidFill><a:schemeClr val=\"tx1\"/>" +
        "</a:solidFill><a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>" +
        "<p:bodyStyle><a:lvl1pPr marL=\"228600\" indent=\"-228600\"><a:buFont typeface=\"Arial\"/>" +
        "<a:buChar char=\"•\"/><a:defRPr sz=\"2800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>" +
        "<a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle><p:otherStyle/></p:txStyles>" +
        "</p:sldMaster>"

    private fun layout() = "<p:sldLayout $NS type=\"title\"><p:cSld><p:spTree>$GROUP_HEAD" +
        sp(2, ph = "<p:ph type=\"title\"/>", spPr = xfrm(50, 40, 620, 80)) +
        sp(3, ph = "<p:ph idx=\"1\"/>", spPr = "") +
        "</p:spTree></p:cSld></p:sldLayout>"

    private fun slide1() = "<p:sld $NS><p:cSld><p:spTree>$GROUP_HEAD" +
        sp(2, ph = "<p:ph type=\"title\"/>", spPr = "", body = textBody("Quarterly Review")) +
        sp(3, ph = "<p:ph idx=\"1\"/>", spPr = "", body = textBody("First point", "Second point")) +
        sp(
            4,
            spPr = xfrm(60, 300, 200, 100) + "<a:prstGeom prst=\"roundRect\"><a:avLst/></a:prstGeom>" +
                "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill>" +
                "<a:ln w=\"25400\"><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill></a:ln>",
            body = textBody("Boxed", anchor = "ctr")
        ) +
        sp(
            5,
            spPr = xfrm(400, 300, 100, 100, rot = 30) + RECT +
                "<a:solidFill><a:schemeClr val=\"accent1\"><a:lumMod val=\"50000\"/></a:schemeClr></a:solidFill>"
        ) +
        "<p:pic><p:nvPicPr><p:cNvPr id=\"6\" name=\"pic\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>" +
        "<p:blipFill><a:blip r:embed=\"rId2\"/><a:srcRect l=\"10000\"/><a:stretch><a:fillRect/></a:stretch>" +
        "</p:blipFill><p:spPr>" + xfrm(540, 60, 120, 90, flipH = true) + RECT + "</p:spPr></p:pic>" +
        "</p:spTree></p:cSld></p:sld>"

    private fun slide2() = "<p:sld $NS><p:cSld><p:spTree>$GROUP_HEAD" +
        "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"2\" name=\"t\"/><p:cNvGraphicFramePr/><p:nvPr/>" +
        "</p:nvGraphicFramePr><p:xfrm><a:off x=\"${36 * EMU}\" y=\"${36 * EMU}\"/>" +
        "<a:ext cx=\"${400 * EMU}\" cy=\"${80 * EMU}\"/></p:xfrm><a:graphic>" +
        "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>" +
        "<a:tblPr firstRow=\"1\" bandRow=\"1\"><a:tableStyleId>{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" +
        "</a:tableStyleId></a:tblPr><a:tblGrid><a:gridCol w=\"${200 * EMU}\"/><a:gridCol w=\"${200 * EMU}\"/>" +
        "</a:tblGrid>" + row("Cell A1", "Cell B1") + row("Cell A2", "Cell B2") +
        "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>" +
        "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"3\" name=\"c\"/><p:cNvGraphicFramePr/><p:nvPr/>" +
        "</p:nvGraphicFramePr><p:xfrm><a:off x=\"0\" y=\"${200 * EMU}\"/><a:ext cx=\"${300 * EMU}\" " +
        "cy=\"${200 * EMU}\"/></p:xfrm><a:graphic>" +
        "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">" +
        "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rId3\"/>" +
        "</a:graphicData></a:graphic></p:graphicFrame>" +
        "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"d\"/><p:cNvGraphicFramePr/><p:nvPr/>" +
        "</p:nvGraphicFramePr><p:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${10 * EMU}\" cy=\"${10 * EMU}\"/>" +
        "</p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/diagram\"/>" +
        "</a:graphic></p:graphicFrame>" +
        "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"5\" name=\"g\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr>" +
        "<a:xfrm><a:off x=\"${100 * EMU}\" y=\"${100 * EMU}\"/><a:ext cx=\"${200 * EMU}\" cy=\"${200 * EMU}\"/>" +
        "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"${100 * EMU}\" cy=\"${100 * EMU}\"/></a:xfrm></p:grpSpPr>" +
        sp(6, spPr = xfrm(10, 10, 20, 20) + RECT + "<a:solidFill><a:srgbClr val=\"00FF00\"/></a:solidFill>") +
        "</p:grpSp>" +
        sp(
            7,
            spPr = xfrm(500, 300, 100, 100) + "<a:custGeom><a:pathLst/></a:custGeom>" +
                "<a:solidFill><a:srgbClr val=\"0000FF\"/></a:solidFill>",
            body = textBody("Fancy", font = "Comic Papyrus Display")
        ) +
        "</p:spTree></p:cSld></p:sld>"

    private fun slide3() = "<p:sld $NS show=\"0\"><p:cSld><p:spTree>$GROUP_HEAD" +
        sp(2, spPr = xfrm(60, 60, 300, 60), body = textBody("Secret")) +
        "</p:spTree></p:cSld></p:sld>"

    private fun simpleSlide(text: String) = "<p:sld $NS><p:cSld><p:spTree>$GROUP_HEAD" +
        sp(2, spPr = xfrm(60, 60, 400, 60), body = textBody(text)) +
        "</p:spTree></p:cSld></p:sld>"

    private const val RECT = "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"

    private fun row(vararg cells: String) = "<a:tr h=\"${30 * EMU}\">" + cells.joinToString("") {
        "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>$it</a:t></a:r></a:p>" +
            "</a:txBody><a:tcPr/></a:tc>"
    } + "</a:tr>"

    private fun xfrm(x: Int, y: Int, w: Int, h: Int, rot: Int = 0, flipH: Boolean = false): String =
        "<a:xfrm" + (if (rot != 0) " rot=\"${rot * 60_000}\"" else "") + (if (flipH) " flipH=\"1\"" else "") +
            "><a:off x=\"${x * EMU}\" y=\"${y * EMU}\"/><a:ext cx=\"${w * EMU}\" cy=\"${h * EMU}\"/></a:xfrm>"

    private fun sp(id: Int, ph: String = "", spPr: String, body: String = ""): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"s$id\"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr>" +
            "<p:spPr>$spPr</p:spPr>$body</p:sp>"

    private fun textBody(vararg paragraphs: String, anchor: String? = null, font: String? = null): String =
        "<p:txBody><a:bodyPr" + (anchor?.let { " anchor=\"$it\"" } ?: "") + "/><a:lstStyle/>" +
            paragraphs.joinToString("") { text ->
                "<a:p><a:r><a:rPr lang=\"en-US\">" + (font?.let { "<a:latin typeface=\"$it\"/>" } ?: "") +
                    "</a:rPr><a:t>$text</a:t></a:r></a:p>"
            } + "</p:txBody>"
}
