package com.skeler.scanely.core.pdftools.pdf2office

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.ocr.paddle.PaddleLineReader
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdftools.MIME_DOCX
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.pdf2office.exact.AssetTextMeasurer
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactDocxWriter
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PDF → .docx that looks like the PDF: every page keeps its size, its text sits where it was in the
 * source font, size, colour and weight as editable paragraphs, and everything that isn't text
 * (rules, fills, figures, photos) is a picture behind it. See [ExactDocxWriter].
 *
 * Falls back to the older flowing conversion (paragraphs, headings and tables inferred from the
 * text) only when pdfium's extended calls are unavailable on the device.
 */
@Singleton
class PdfToWordJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace,
    private val reader: PaddleLineReader
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".docx", MIME_DOCX, "pdf-to-word") { document, output, scratch ->
            val geometries = source.geometries(document)
            ExactPageSource.open(context, document.file, document.password)?.use { exact ->
                return@produceOfficeFile exact(exact, document, input, output, scratch)
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
        output.outputStream().use { out ->
            val writer = ExactDocxWriter(out, AssetTextMeasurer(context.assets))
            for (index in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                input.onProgress(index.toFloat() / document.pageCount)
                val page = exactPage(source, reader, document, pages, index, scratch, tally) ?: continue
                writer.addPage(page.widthPt, page.heightPt, page.layout, page.background, page.pictures)
            }
            coroutineContext.ensureActive()
            writer.finish()
        }
        return tally.notes()
    }

    private suspend fun flowing(
        document: ToolDocument,
        input: ToolJobInput,
        output: File,
        scratch: File,
        geometries: List<PageGeometry>
    ): List<ToolNote> {
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
        return tally.notes(R.string.pdf2office_simplified_word, R.plurals.pdf2office_scanned_as_images)
    }
}
