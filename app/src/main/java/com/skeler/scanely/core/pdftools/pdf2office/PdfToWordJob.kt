package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.MIME_DOCX
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PDF → .docx: each page's text as paragraphs, headings, list items and tables, with a page break
 * between PDF pages. A page with no text is rendered and placed as a picture, so a scanned page is
 * carried over rather than silently dropped. The result is always flagged as simplified — a PDF
 * has no paragraphs, only positioned glyphs, so structure is inferred, never read.
 */
@Singleton
class PdfToWordJob @Inject constructor(
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".docx", MIME_DOCX, "pdf-to-word") { document, output, scratch ->
            val geometries = source.geometries(document)
            val tally = PageTally()
            output.outputStream().use { out ->
                val first = geometries.first()
                val writer = DocxWriter(out, first.widthPt, first.heightPt)
                for (index in 0 until document.pageCount) {
                    coroutineContext.ensureActive()
                    input.onProgress(index.toFloat() / document.pageCount)
                    when (val page = source.read(document, index)) {
                        is OfficePage.Text -> {
                            writer.addPage(page.layout)
                            if (page.dense) tally.dense++
                        }
                        // An unreadable text layer may still render; a picture beats a gap.
                        OfficePage.Scanned, OfficePage.Unreadable -> {
                            val jpeg = File(scratch, "page$index.jpg")
                            val geometry = geometries.getOrElse(index) { first }
                            val rendered = jpeg.outputStream().buffered().use {
                                source.renderJpeg(document, index, geometry, it)
                            }
                            if (rendered != null) {
                                writer.addImagePage(jpeg, rendered.widthPx, rendered.heightPx)
                                tally.scanned++
                            } else {
                                jpeg.delete()
                                tally.unreadable++
                            }
                        }
                    }
                }
                coroutineContext.ensureActive()
                writer.finish()
            }
            tally.notes(R.string.pdf2office_simplified_word, R.plurals.pdf2office_scanned_as_images)
        }
}
