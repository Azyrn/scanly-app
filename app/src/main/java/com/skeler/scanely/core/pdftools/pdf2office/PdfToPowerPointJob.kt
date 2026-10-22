package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.MIME_PPTX
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PDF → .pptx, one slide per page. Text pages become editable text boxes at the PDF's positions;
 * only a page with no text gets its render as the slide picture, because a picture behind real
 * text boxes would show every line twice the moment the user edits one.
 */
@Singleton
class PdfToPowerPointJob @Inject constructor(
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".pptx", MIME_PPTX, "pdf-to-powerpoint") { document, output, scratch ->
            val geometries = source.geometries(document)
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
                                // A blank slide keeps slide numbers matching page numbers.
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
            tally.notes(R.string.pdf2office_simplified_powerpoint, R.plurals.pdf2office_scanned_as_images)
        }

    private companion object {
        val EMPTY_PAGE = PageLayout(emptyList(), positioned = true)
    }
}
