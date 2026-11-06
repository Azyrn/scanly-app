package com.skeler.scanely.core.pdftools.pdf2office

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.MIME_XLSX
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
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
        produceOfficeFile(workspace, input, ".xlsx", MIME_XLSX, "pdf-to-excel") { document, output, _ ->
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
            val notes = tally.notes(simplified = null, scannedNote = R.plurals.pdf2office_excel_scanned_skipped)
            if (hasText && !hasTables) listOf(ToolNote(R.string.pdf2office_excel_no_tables)) + notes else notes
        }
}
