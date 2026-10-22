package com.skeler.scanely.core.pdftools.text

import android.content.Context
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdftools.MIME_TEXT
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "ExtractTextJob"

/**
 * Copies a PDF's text layer into one UTF-8 .txt file. Only the text the PDF already carries —
 * no OCR — so it is instant and offline; pages that are pictures of text are counted and the note
 * points the user at the OCR path instead of handing back a silently incomplete file.
 *
 * Pages are read and written one at a time, so a thousand-page book never sits in memory whole.
 */
@Singleton
class ExtractTextJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageReader: PdfPageReader,
    private val workspace: ToolWorkspace
) {

    suspend fun extract(
        document: ToolDocument,
        pageSeparators: Boolean,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        workspace.requireSpace(document.pageCount.toLong() * TEXT_BYTES_PER_PAGE_ESTIMATE)

        val base = document.displayName.substringBeforeLast('.').ifBlank { document.displayName }
        val fileName = ToolWorkspace.safeFileName(base) + TEXT_EXTENSION
        val output = File(run, fileName)
        var delivered = false
        try {
            var emptyPages = 0
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (index in 0 until document.pageCount) {
                    coroutineContext.ensureActive()
                    val text = pageReader.plainText(document.file, index, document.password)
                        ?.let(::normalizeLineBreaks)
                        ?.trim()
                        .orEmpty()
                    if (text.isEmpty()) emptyPages++
                    writePage(writer, index, text, pageSeparators)
                    onProgress((index + 1f) / document.pageCount)
                }
            }
            coroutineContext.ensureActive()

            val toolOutput = ToolOutput(output, fileName, MIME_TEXT, output.length())
            delivered = true
            val notes = if (emptyPages > 0) {
                listOf(ToolNote(R.plurals.pdf_tool_extract_text_empty_pages, emptyPages, plural = true))
            } else {
                emptyList()
            }
            return ToolResult(listOf(toolOutput), document.sizeBytes, notes)
        } catch (e: IOException) {
            Log.w(TAG, "writing the text file failed", e)
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed(e.message), e)
        } finally {
            if (!delivered) output.delete()
        }
    }

    /**
     * A blank line between pages keeps paragraphs from running together across a page break; the
     * optional marker is for readers who need to cite a page. A page with no text still gets its
     * marker, so the numbering in the file never skips.
     */
    private suspend fun writePage(writer: Writer, index: Int, text: String, separator: Boolean) =
        withContext(Dispatchers.IO) {
            if (separator) {
                writer.write(context.getString(R.string.pdf_tool_extract_text_page_separator, index + 1))
                writer.write(LINE_BREAK)
            }
            if (text.isNotEmpty()) {
                writer.write(text)
                writer.write(LINE_BREAK)
            }
            writer.write(LINE_BREAK)
        }

    /** pdfium ends lines with CRLF; a plain LF reads the same everywhere and is what editors expect. */
    private fun normalizeLineBreaks(text: String): String = text.replace("\r\n", LINE_BREAK).replace('\r', '\n')

    private companion object {
        const val TEXT_EXTENSION = ".txt"
        const val LINE_BREAK = "\n"

        /** A dense page of prose is 3–5 KB of UTF-8; this leaves room for non-Latin scripts. */
        const val TEXT_BYTES_PER_PAGE_ESTIMATE = 16L * 1024
    }
}
