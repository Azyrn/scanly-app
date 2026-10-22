package com.skeler.scanely.core.pdftools.pdf2office

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.MIME_XLSX
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PDF → .xlsx. Only tables belong in a spreadsheet, so each page with tables gets its own sheet
 * ("Page 3"), tables stacked with a blank row between them. A document with no tables at all
 * still gives the user their text, one line per row, rather than an empty workbook.
 *
 * Until the first table turns up, every line is staged in a scratch file: the fallback sheet can
 * only be written once the whole document is known to have no tables, and the lines of a long
 * document shouldn't wait for that in memory.
 */
@Singleton
class PdfToExcelJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val source: OfficePageSource,
    private val workspace: ToolWorkspace
) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        produceOfficeFile(workspace, input, ".xlsx", MIME_XLSX, "pdf-to-excel") { document, output, scratch ->
            val tally = PageTally()
            val textSheet = context.getString(R.string.pdf2office_excel_sheet_text)
            val staged = File(scratch, "lines.txt")
            var usedLines = false
            output.outputStream().use { out ->
                val writer = XlsxWriter(out)
                val stagedLines = staged.bufferedWriter().use { lines ->
                    readPages(input, document, writer, lines, tally)
                }
                coroutineContext.ensureActive()
                if (writer.sheetCount == 0 && stagedLines > 0) {
                    staged.bufferedReader().use { reader ->
                        writer.addSheet(textSheet, reader.lineSequence().map { listOf(it) })
                    }
                    usedLines = true
                }
                writer.finish(textSheet)
            }
            val notes = tally.notes(simplified = null, scannedNote = R.plurals.pdf2office_excel_scanned_skipped)
            if (usedLines) listOf(ToolNote(R.string.pdf2office_excel_no_tables)) + notes else notes
        }

    /** Every page into [writer] or the staged [lines]; returns how many lines were staged. */
    private suspend fun readPages(
        input: ToolJobInput,
        document: ToolDocument,
        writer: XlsxWriter,
        lines: BufferedWriter,
        tally: PageTally
    ): Int {
        var stagedLines = 0
        for (index in 0 until document.pageCount) {
            coroutineContext.ensureActive()
            input.onProgress(index.toFloat() / document.pageCount)
            when (val page = source.read(document, index)) {
                is OfficePage.Text -> stagedLines += addTextPage(page, index, writer, lines, tally)
                OfficePage.Scanned -> tally.scanned++
                OfficePage.Unreadable -> tally.unreadable++
            }
        }
        return stagedLines
    }

    /**
     * A page with tables becomes its own sheet; until the first sheet exists, a page without
     * tables has its lines staged. Returns how many lines it staged.
     */
    private fun addTextPage(
        page: OfficePage.Text,
        index: Int,
        writer: XlsxWriter,
        lines: BufferedWriter,
        tally: PageTally
    ): Int {
        if (page.dense) tally.dense++
        val tables = page.layout.tables
        if (tables.isNotEmpty()) {
            val name = context.getString(R.string.pdf2office_excel_sheet_page, index + 1)
            writer.addSheet(name, rowsOf(tables))
            return 0
        }
        if (writer.sheetCount != 0) return 0
        var staged = 0
        page.layout.textLines.forEach { line ->
            lines.write(line.replace('\n', ' ').replace('\r', ' '))
            lines.newLine()
            staged++
        }
        return staged
    }

    /** Tables stacked top to bottom, a blank row between each. */
    private fun rowsOf(tables: List<TableBlock>): Sequence<List<String>> =
        tables.asSequence().flatMapIndexed { tableIndex, table ->
            val gap = if (tableIndex > 0) sequenceOf(emptyList<String>()) else emptySequence()
            gap + table.rows.asSequence()
        }
}
