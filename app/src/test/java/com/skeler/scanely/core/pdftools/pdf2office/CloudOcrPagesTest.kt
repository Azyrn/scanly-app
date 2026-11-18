package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.pdf2office.exact.Block
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPage
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.OcrBlock
import com.skeler.scanely.core.pdftools.pdf2office.exact.OfficeFamily
import com.skeler.scanely.core.pdftools.pdf2office.exact.Segment
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextStyle
import com.skeler.scanely.core.pdftools.pdf2office.exact.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [CloudOcrPages]: pages go out in document order, only pages with an uncertain word are read again,
 * all of them in one request, and a failed read leaves every page as the PDF had it.
 */
class CloudOcrPagesTest {

    private val style = TextStyle(OfficeFamily.TIMES, arabic = true, bold = false, italic = false, sizePt = 10f, argb = 0)
    private val document = ToolDocument(File("in.pdf"), "in.pdf", 0L, 4, "", isEncrypted = false)

    @Test
    fun uncertainPagesAreReadInOneRequestAndCorrectedInOrder() = runBlocking {
        val requests = ArrayList<List<Int>>()
        val reader = CloudPageReader { _, pages, _ ->
            requests += pages
            pages.associateWith { listOf(OcrBlock(0f, 0f, 1f, 1f, "مذكرة")) }
        }
        val written = ArrayList<String>()
        val pages = CloudOcrPages(reader, document, File(".")) { written += text(it) }

        pages.add(0, page("plain", uncertain = false))
        assertEquals(listOf("plain"), written) // nothing uncertain yet: written at once
        pages.add(1, page("مذكاة", uncertain = true))
        pages.add(2, page("plain2", uncertain = false))
        pages.add(3, page("مذكاة", uncertain = true))
        assertEquals(listOf("plain"), written) // held back from the first uncertain page on
        pages.finish()

        assertEquals(listOf(listOf(1, 3)), requests)
        assertEquals(listOf("plain", "مذكرة", "plain2", "مذكرة"), written)
    }

    @Test
    fun aFailedReadWritesThePagesAsTheyWere() = runBlocking {
        val written = ArrayList<String>()
        val pages = CloudOcrPages({ _, _, _ -> emptyMap() }, document, File(".")) { written += text(it) }
        pages.add(0, page("مذكاة", uncertain = true))
        pages.add(1, page("plain", uncertain = false))
        pages.finish()
        assertEquals(listOf("مذكاة", "plain"), written)
    }

    @Test
    fun aDocumentWithNothingUncertainSendsNothing() = runBlocking {
        var asked = false
        val pages = CloudOcrPages({ _, _, _ -> emptyMap<Int, List<OcrBlock>>().also { asked = true } }, document, File(".")) {}
        pages.add(0, page("plain", uncertain = false))
        pages.finish()
        assertEquals(false, asked)
    }

    private fun page(word: String, uncertain: Boolean): ExactPage {
        val token = Token(word, style, 100f, 150f, false, 0f, uncertain = uncertain)
        val line = Segment(listOf(token), 700f, 10f, rtl = true, angleDeg = 0)
        return ExactPage(612f, 792f, ExactPageLayout(612f, 792f, listOf(Block(listOf(line), 12f, 0f))), null, emptyList())
    }

    private fun text(page: ExactPage) = page.layout.blocks.single().lines.single().tokens.single().text
}
