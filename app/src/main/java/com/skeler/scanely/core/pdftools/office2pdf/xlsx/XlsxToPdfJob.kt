package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import android.graphics.pdf.PdfDocument
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.MIME_PDF
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

private const val TAG = "XlsxToPdfJob"

/**
 * Excel to PDF, entirely on the phone: our own .xlsx reader and a sheet layout drawn with
 * [PdfDocument]. It prints what Excel would print for values, styles, merges and column widths;
 * what it can't draw (charts, pictures, conditional colours) is left out and said so in the notes,
 * never guessed at.
 */
@Singleton
class XlsxToPdfJob @Inject constructor(private val workspace: ToolWorkspace) {

    suspend fun convert(source: ImportedFile, run: File, onProgress: (Float?) -> Unit): ToolResult =
        withContext(Dispatchers.Default) {
            // Vector pages are small; a few times the zipped workbook covers even dense sheets.
            workspace.requireSpace(source.sizeBytes * SPACE_FACTOR + SPACE_FLOOR_BYTES)
            val base = source.displayName.substringBeforeLast('.').ifBlank { source.displayName }
            val fileName = ToolWorkspace.safeFileName(base) + PDF_EXTENSION
            val output = File(run, fileName)
            var delivered = false
            try {
                val notes = render(source.file, output, onProgress)
                currentCoroutineContext().ensureActive()
                val result = ToolResult(
                    outputs = listOf(ToolOutput(output, fileName, MIME_PDF, output.length())),
                    inputBytes = source.sizeBytes,
                    notes = toolNotes(notes)
                )
                delivered = true
                result
            } catch (e: ZipException) {
                throw damaged("not a zip", e)
            } catch (e: XlsxFormatException) {
                throw damaged("malformed workbook", e)
            } catch (e: XlsxTooLargeException) {
                Log.w(TAG, "Workbook over the size limits")
                throw ToolException(ToolFailure.Failed(e.message), e)
            } catch (e: IOException) {
                if (e.isOutOfSpace()) throw ToolException(ToolFailure.LowStorage, e)
                throw damaged("unreadable workbook", e)
            } finally {
                if (!delivered) output.delete()
            }
        }

    private suspend fun render(input: File, output: File, onProgress: (Float?) -> Unit): XlsxNotes {
        val context = currentCoroutineContext()
        return XlsxReader.open(input, checkCancel = { context.ensureActive() }).use { reader ->
            if (reader.sheets.isEmpty()) throw XlsxFormatException("no visible worksheet")
            val pdf = PdfDocument()
            try {
                val painter = XlsxPagePainter()
                val sheets = reader.sheets
                var pages = 0
                for ((index, ref) in sheets.withIndex()) {
                    if (pages >= MAX_PAGES) {
                        reader.notes.pageCap = MAX_PAGES
                        break
                    }
                    context.ensureActive()
                    val sheet = reader.readSheet(ref)
                    pages += writeSheet(pdf, painter, sheet, pages, reader.notes, context) { done ->
                        onProgress((index + done) / sheets.size)
                    }
                }
                Log.w(TAG, "Wrote $pages pages from ${sheets.size} sheets")
                FileOutputStream(output).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            reader.notes
        }
    }

    /** Returns the pages written; an empty sheet still gets one page with its name, so it isn't lost. */
    private fun writeSheet(
        pdf: PdfDocument,
        painter: XlsxPagePainter,
        sheet: XlsxSheet,
        pagesSoFar: Int,
        notes: XlsxNotes,
        context: CoroutineContext,
        onProgress: (Float) -> Unit
    ): Int {
        val layout = XlsxLayout.layout(sheet, painter::measureWrapped)
        if (layout == null) {
            val frame = PageFrame(sheet.setup.orientation ?: PageOrientation.PORTRAIT)
            val page = pdf.startPage(pageInfo(frame, pagesSoFar))
            painter.drawHeader(page.canvas, frame, sheet.name)
            pdf.finishPage(page)
            return 1
        }
        val room = MAX_PAGES - pagesSoFar
        if (layout.slices.size > room) notes.pageCap = MAX_PAGES
        val slices = layout.slices.take(room)
        slices.forEachIndexed { index, slice ->
            context.ensureActive()
            val page = pdf.startPage(pageInfo(layout.frame, pagesSoFar + index))
            painter.drawHeader(page.canvas, layout.frame, sheet.name)
            painter.draw(page.canvas, layout, slice)
            pdf.finishPage(page)
            onProgress((index + 1f) / slices.size)
        }
        return slices.size
    }

    private fun pageInfo(frame: PageFrame, index: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(frame.width.toInt(), frame.height.toInt(), index + 1).create()

    private fun damaged(reason: String, cause: Throwable): ToolException {
        Log.w(TAG, "Excel to PDF: $reason (${cause.javaClass.simpleName})")
        return ToolException(ToolFailure.Damaged, cause)
    }

    private fun toolNotes(notes: XlsxNotes): List<ToolNote> = buildList {
        fun plural(res: Int, count: Int) {
            if (count > 0) add(ToolNote(res, count, plural = true))
        }
        plural(R.plurals.excel2pdf_note_hidden_sheets, notes.hiddenSheets)
        plural(R.plurals.excel2pdf_note_charts, notes.charts)
        plural(R.plurals.excel2pdf_note_pictures, notes.pictures)
        if (notes.cachedFormulas > 0) add(ToolNote(R.string.excel2pdf_note_formulas_cached))
        plural(R.plurals.excel2pdf_note_formulas_missing, notes.missingFormulas)
        if (notes.conditionalFormats > 0) add(ToolNote(R.string.excel2pdf_note_conditional))
        if (notes.pivotTables > 0) add(ToolNote(R.string.excel2pdf_note_pivots))
        notes.cutAtRow?.let { plural(R.plurals.excel2pdf_note_cut_rows, it) }
        notes.pageCap?.let { plural(R.plurals.excel2pdf_note_page_cap, it) }
    }

    companion object {
        /** Pages per PDF; past this the rest is left out and noted. */
        const val MAX_PAGES = 2_000
        private const val PDF_EXTENSION = ".pdf"
        private const val SPACE_FACTOR = 4
        private const val SPACE_FLOOR_BYTES = 8L * 1024 * 1024
    }
}
