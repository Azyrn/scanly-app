package com.skeler.scanely.core.pdftools.pdf2office

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.ocr.paddle.PaddleLineReader
import com.skeler.scanely.core.pdftools.MIME_PPTX
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.pdf2office.exact.AssetTextMeasurer
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PDF → .pptx, one slide per page, that looks like the PDF: the page drawn without its text and
 * pictures is the bottom layer, pictures keep their own resolution, and every line of text is an
 * editable text box at its PDF position in the source font, size and colour (see [PptxWriter.addExactSlide]).
 * A scan keeps its picture with its text read by the bundled OCR behind it.
 *
 * Falls back to the older text-box conversion only when pdfium's extended calls are unavailable.
 */
@Singleton
class PdfToPowerPointJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace,
    private val reader: PaddleLineReader
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".pptx", MIME_PPTX, "pdf-to-powerpoint") { document, output, scratch ->
            val geometries = source.geometries(document)
            ExactPageSource.open(context, document.file, document.password)?.use { pages ->
                return@produceOfficeFile exact(pages, document, input, output, scratch)
            }
            flowing(document, input, output, scratch, geometries)
        }

    private suspend fun exact(
        pages: ExactPageSource,
        document: ToolDocument,
        input: ToolJobInput,
        output: File,
        scratch: File
    ): List<ToolNote> {
        val tally = ExactTally()
        val measurer = AssetTextMeasurer(context.assets)
        output.outputStream().use { out ->
            var writer: PptxWriter? = null
            for (index in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                input.onProgress(index.toFloat() / document.pageCount)
                val page = exactPage(source, reader, document, pages, index, scratch, tally)
                // The first slide sets the deck's size; an unreadable page is a blank slide, so slide
                // numbers keep matching page numbers.
                val deck = writer ?: PptxWriter(
                    out,
                    page?.widthPt ?: DEFAULT_WIDTH_PT,
                    page?.heightPt ?: DEFAULT_HEIGHT_PT
                )
                    .also { writer = it }
                if (page == null) {
                    deck.addTextSlide(EMPTY_PAGE, DEFAULT_WIDTH_PT, DEFAULT_HEIGHT_PT)
                } else {
                    deck.addExactSlide(
                        page.layout,
                        page.widthPt,
                        page.heightPt,
                        page.background,
                        page.pictures,
                        measurer
                    )
                }
            }
            coroutineContext.ensureActive()
            (writer ?: PptxWriter(out, DEFAULT_WIDTH_PT, DEFAULT_HEIGHT_PT)).finish()
        }
        return tally.notes()
    }

    private suspend fun flowing(
        document: com.skeler.scanely.core.pdftools.ToolDocument,
        input: ToolJobInput,
        output: File,
        scratch: File,
        geometries: List<com.skeler.scanely.core.pdfeditor.engine.PageGeometry>
    ): List<ToolNote> {
        val tally = PageTally()
        output.outputStream().use { out ->
            val first = geometries.first()
            val writer = PptxWriter(out, first.widthPt, first.heightPt)
            for (index in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                input.onProgress(index.toFloat() / document.pageCount)
                val geometry = geometries.getOrElse(index) { first }
                when (val page = source.read(document, index)) {
                    is OfficePage.Text -> {
                        writer.addTextSlide(page.layout, geometry.widthPt, geometry.heightPt)
                        if (page.dense) tally.dense++
                    }
                    OfficePage.Scanned, OfficePage.Unreadable -> {
                        val jpeg = File(scratch, "page$index.jpg")
                        val rendered = jpeg.outputStream().buffered().use {
                            source.renderJpeg(document, index, geometry, it)
                        }
                        if (rendered != null) {
                            writer.addImageSlide(geometry.widthPt, geometry.heightPt, jpeg)
                            tally.scanned++
                        } else {
                            writer.addTextSlide(EMPTY_PAGE, geometry.widthPt, geometry.heightPt)
                            tally.unreadable++
                        }
                        jpeg.delete()
                    }
                }
            }
            coroutineContext.ensureActive()
            writer.finish()
        }
        return tally.notes(R.string.pdf2office_simplified_powerpoint, R.plurals.pdf2office_scanned_as_images)
    }

    private companion object {
        val EMPTY_PAGE = PageLayout(emptyList(), positioned = true)
        const val DEFAULT_WIDTH_PT = 595f
        const val DEFAULT_HEIGHT_PT = 842f
    }
}
