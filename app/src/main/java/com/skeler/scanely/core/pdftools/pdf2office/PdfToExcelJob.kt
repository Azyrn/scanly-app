package com.skeler.scanely.core.pdftools.pdf2office

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.MIME_XLSX
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageSource
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactSheets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class PdfToExcelJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".xlsx", MIME_XLSX, "pdf-to-excel") { document, output, scratch ->
            ExactPageSource.open(context, document.file, document.password, scratch)?.use { pages ->
                return@produceOfficeFile exact(pages, document, input, output)
            }
            flowing(document, input, output)
        }

    /** The flowing layout's tables (text alignment alone): for a PDF pdfium cannot open for the exact path. */
    private suspend fun flowing(document: ToolDocument, input: ToolJobInput, output: File): List<ToolNote> {
        val tally = PageTally()
        val textSheet = context.getString(R.string.pdf2office_excel_sheet_text)
        var hasTables = false
        var hasText = false
        output.outputStream().use { out ->
            val writer = XlsxWriter(out)
            for (index in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                input.onProgress(index.toFloat() / document.pageCount)
                when (val page = source.read(document, index)) {
                    is OfficePage.Text -> {
                        hasText = true
                        if (page.dense) tally.dense++
                        hasTables = hasTables || page.layout.tables.isNotEmpty()
                        val name = context.getString(R.string.pdf2office_excel_sheet_page, index + 1)
                        writer.addPage(name, page.layout)
                    }
                    OfficePage.Scanned -> tally.scanned++
                    OfficePage.Unreadable -> tally.unreadable++
                }
            }
            coroutineContext.ensureActive()
            writer.finish(textSheet)
        }
        return excelNotes(tally, noTables = hasText && !hasTables)
    }

    /**
     * Each page through the exact layout, as PDF → Word reads it: ruled tables, wrapped cells as one
     * cell, and tables that run on over pages joined on the sheet where they start.
     */
    private suspend fun exact(
        pages: ExactPageSource,
        document: ToolDocument,
        input: ToolJobInput,
        output: File
    ): List<ToolNote> {
        val tally = PageTally()
        var hasTables = false
        var hasText = false
        output.outputStream().use { out ->
            val writer = XlsxWriter(out)
            val sheets = ExactSheets(writer)
            for (index in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                input.onProgress(index.toFloat() / document.pageCount)
                val layout = pages.layout(index)?.let { confirmed(pages, document, index, it) }
                when {
                    layout == null -> tally.unreadable++
                    layout.isEmpty -> tally.scanned++
                    else -> {
                        hasText = true
                        hasTables = hasTables || layout.tables.isNotEmpty()
                        sheets.add(context.getString(R.string.pdf2office_excel_sheet_page, index + 1), layout)
                    }
                }
            }
            coroutineContext.ensureActive()
            sheets.finish()
            writer.finish(context.getString(R.string.pdf2office_excel_sheet_text))
        }
        return excelNotes(tally, noTables = hasText && !hasTables)
    }

    /** [layout] again with the layout model's table regions, when the text alone left a table open. */
    private suspend fun confirmed(
        pages: ExactPageSource,
        document: ToolDocument,
        index: Int,
        layout: ExactPageLayout
    ): ExactPageLayout {
        if (!layout.unconfirmedTables) return layout
        val regions = source.tableRegions(document, index) ?: return layout
        return pages.layout(index, regions) ?: layout
    }
}

/** The page tally's notes, led by "no tables found" when the PDF had text but no table in it. */
private fun excelNotes(tally: PageTally, noTables: Boolean): List<ToolNote> {
    val notes = tally.notes(simplified = null, scannedNote = R.plurals.pdf2office_excel_scanned_skipped)
    return if (noTables) listOf(ToolNote(R.string.pdf2office_excel_no_tables)) + notes else notes
}
