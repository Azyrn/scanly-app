package com.skeler.scanely.core.pdftools

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PptxToPdfJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * PowerPoint to PDF end to end: a deck built here, rendered by our renderer through Android's
 * PdfDocument, read back with pdfium. The checks are what a user would notice — the right pages,
 * the text really in the PDF, the notes about what was left out — plus the cleanup guarantees.
 */
@RunWith(AndroidJUnit4::class)
class PowerPointToPdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Outside the workspace root on purpose: ToolWorkspace clears its root when constructed.
    private val dir = File(context.cacheDir, "ppt2pdf-test").apply { mkdirs() }

    private val workspace = ToolWorkspace(context)
    private val pages = PdfPageReader(context)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun deckBecomesOnePagePerVisibleSlideWithItsText() = runBlocking {
        val run = workspace.newRun()
        val result = job().convert(deck("Quarterly deck.pptx"), run) {}

        val output = result.outputs.single()
        assertEquals("Quarterly deck.pdf", output.fileName)
        assertEquals(MIME_PDF, output.mimeType)
        // Three slides, one hidden.
        assertEquals(2, pages.pageCount(output.file))
        val size = pages.pageGeometries(output.file)!!.first()
        assertEquals(720f, size.widthPt, 1f)
        assertEquals(540f, size.heightPt, 1f)

        val first = pages.plainText(output.file, 0).orEmpty()
        assertTrue(first, first.contains("Quarterly Review"))
        assertTrue(first, first.contains("First point"))
        assertTrue(first, first.contains("Second point"))
        assertTrue(first, first.contains("Boxed"))
        val second = pages.plainText(output.file, 1).orEmpty()
        assertTrue(second, second.contains("Cell A1"))
        assertTrue(second, second.contains("Cell B2"))
        assertFalse(first.contains("Secret") || second.contains("Secret"))

        assertTrue(result.notes.contains(ToolNote(R.plurals.pdf_tool_ppt2pdf_charts, 1, plural = true)))
        assertTrue(result.notes.contains(ToolNote(R.plurals.pdf_tool_ppt2pdf_diagrams, 1, plural = true)))
        assertTrue(result.notes.contains(ToolNote(R.plurals.pdf_tool_ppt2pdf_hidden, 1, plural = true)))
        assertTrue(result.notes.contains(ToolNote(R.string.pdf_tool_ppt2pdf_shapes)))
        assertTrue(result.notes.contains(ToolNote(R.string.pdf_tool_ppt2pdf_fonts)))
        // The picture decoded, so no "couldn't be shown" note.
        assertFalse(result.notes.any { it.res == R.plurals.pdf_tool_ppt2pdf_images })
        assertEquals(listOf(output.file), run.listFiles()!!.toList())
    }

    @Test
    fun longDeckIsWrittenInChunksAndJoinedInOrder() = runBlocking {
        val run = workspace.newRun()
        val progress = ArrayList<Float>()
        val result = job().convert(deck("long.pptx", extraSlides = 45), run) { it?.let(progress::add) }

        val output = result.outputs.single()
        assertEquals(47, pages.pageCount(output.file))
        assertTrue(pages.plainText(output.file, 2).orEmpty().contains("Extra 1"))
        assertTrue(pages.plainText(output.file, 46).orEmpty().contains("Extra 45"))
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last(), 0.001f)
        // Chunk files are gone; only the result is left.
        assertEquals(listOf(output.file), run.listFiles()!!.toList())
    }

    @Test
    fun randomBytesAreDamagedAndLeaveNothing() = runBlocking {
        val file = File(dir, "random.pptx").apply { writeBytes(Random(7).nextBytes(20_000)) }
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { job().convert(imported(file), run) {} })
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    @Test
    fun zipThatIsNotAPresentationIsDamaged() = runBlocking {
        val file = zip("notes.pptx", mapOf("word/document.xml" to "<w:document/>".toByteArray()))
        val run = workspace.newRun()

        assertEquals(ToolFailure.Damaged, failureOf { job().convert(imported(file), run) {} })
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    @Test
    fun lowStorageFailsAndLeavesNothing() = runBlocking {
        val full = ToolWorkspace(context) { 0L }
        val run = full.newRun()

        val failure = failureOf { PptxToPdfJob(full, QpdfEngine()).convert(deck("d.pptx"), run) {} }
        assertEquals(ToolFailure.LowStorage, failure)
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    @Test
    fun cancelledRunLeavesNothing() = runBlocking {
        val run = workspace.newRun()
        val input = deck("cancel.pptx", extraSlides = 60)
        val handle = launch(Dispatchers.IO) {
            val self = coroutineContext[Job]!!
            runCatching { job().convert(input, run) { p -> if ((p ?: 0f) > 0.4f) self.cancel() } }
        }
        handle.join()

        assertTrue(handle.isCancelled)
        assertTrue(run.listFiles().isNullOrEmpty())
    }

    // --- helpers ---

    private fun job() = PptxToPdfJob(workspace, QpdfEngine())

    private fun imported(file: File, name: String = file.name) = ImportedFile(file, name, file.length())

    private fun deck(name: String, extraSlides: Int = 0): ImportedFile =
        imported(zip(name, PptxDeck.entries(png(), extraSlides)), name)

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
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

    private suspend fun failureOf(block: suspend () -> Unit): ToolFailure {
        try {
            block()
        } catch (e: ToolException) {
            return e.failure
        }
        fail("expected a ToolException")
        error("unreachable")
    }
}

/**
 * A small but complete deck: theme, master (title/body placeholders, a decoration bar, text
 * styles), one layout overriding the title position, and three slides — placeholders, plain
 * shapes and a picture; a table, chart, SmartArt, group and custom shape; and a hidden slide.
 * The same deck as the JVM reader test (PptxReaderTest), here rendered for real.
 */
private object PptxDeck {
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
