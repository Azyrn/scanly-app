package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.MIME_PDF
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
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
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DocxToPdfJob"

/**
 * Word to PDF, entirely on the phone: our own .docx reader and a page layout drawn with the
 * platform's PdfDocument. Text, styles, lists, tables, pictures and page breaks come through;
 * what can't be drawn faithfully (charts, text boxes, equations, headers) is left out and the
 * result says so, rather than being guessed at or failing the whole document.
 */
@Singleton
class DocxToPdfJob @Inject constructor(private val workspace: ToolWorkspace) {

    private val reader = DocxReader()

    suspend fun convert(input: ToolJobInput): ToolResult {
        val source = input.files.firstOrNull() ?: throw ToolException(ToolFailure.Failed("no document picked"))
        return convert(source, input.run, input.onProgress)
    }

    suspend fun convert(source: ImportedFile, run: File, onProgress: (Float?) -> Unit): ToolResult =
        withContext(Dispatchers.Default) {
            // Text pages are small, but pictures are re-embedded: a few times the zip covers both.
            workspace.requireSpace(source.sizeBytes * SPACE_FACTOR + SPACE_FLOOR_BYTES)
            val output = outputFile(source, run)
            var delivered = false
            try {
                onProgress(null)
                val notes = render(source.file, output, onProgress)
                currentCoroutineContext().ensureActive()
                if (!output.isFile || output.length() == 0L) throw ToolException(ToolFailure.Failed("no output"))
                val result = ToolResult(
                    outputs = listOf(ToolOutput(output, output.name, MIME_PDF, output.length())),
                    inputBytes = source.sizeBytes,
                    notes = toolNotes(notes)
                )
                delivered = true
                result
            } catch (e: IOException) {
                Log.w(TAG, "Word to PDF failed writing (${e.javaClass.simpleName})")
                val failure = if (e.isOutOfSpace()) {
                    ToolFailure.LowStorage
                } else {
                    ToolFailure.Failed(e.javaClass.simpleName)
                }
                throw ToolException(failure, e)
            } finally {
                if (!delivered) output.delete()
            }
        }

    /**
     * Reading failures are [ToolFailure.Damaged] (thrown by the reader); anything else that goes
     * wrong while laying out is an engine failure, never a crash. Memory is the one realistic
     * case — a document of huge pictures on a small phone.
     */
    private suspend fun render(input: File, output: File, onProgress: (Float?) -> Unit): DocxNotes {
        val context = currentCoroutineContext()
        val zip = openZip(input)
        return try {
            zip.use {
                val document = reader.read(zip) { context.ensureActive() }
                FileOutputStream(output).use { stream ->
                    DocxLayout(zip, document.notes).render(document, stream, onProgress)
                }
                logSummary(document)
                document.notes
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Word to PDF ran out of memory")
            throw ToolException(ToolFailure.Failed("out of memory"), e)
        } catch (e: Exception) {
            throw layoutFailure(e)
        }
    }

    /** Failures that already mean something (and cancellation) pass through unchanged. */
    private fun layoutFailure(e: Exception): Exception {
        if (e is ToolException || e is CancellationException || e is IOException) return e
        Log.w(TAG, "Word to PDF layout failed (${e.javaClass.simpleName})")
        return ToolException(ToolFailure.Failed(e.javaClass.simpleName), e)
    }

    /** Not a zip at all (an old binary .doc renamed, an encrypted file) reads as damaged. */
    private fun openZip(input: File): ZipFile = try {
        ZipFile(input)
    } catch (e: IOException) {
        Log.w(TAG, "Word to PDF: not a zip (${e.javaClass.simpleName})")
        throw ToolException(ToolFailure.Damaged, e)
    }

    private fun logSummary(document: DocxDocument) {
        val blocks = document.sections.sumOf { it.blocks.size }
        Log.w(TAG, "Converted ${document.sections.size} sections, $blocks blocks")
    }

    /** "<name>.pdf" beside the input; never the input itself, whatever the picked file was called. */
    private fun outputFile(source: ImportedFile, run: File): File {
        val name = source.displayName.substringBeforeLast('.').ifBlank { source.displayName }
        val base = ToolWorkspace.safeFileName(name)
        val plain = File(run, base + PDF_EXTENSION)
        val clashes = plain.absolutePath == source.file.absolutePath
        return if (clashes) File(run, base + SUFFIX_CLASH + PDF_EXTENSION) else plain
    }

    private fun toolNotes(notes: DocxNotes): List<ToolNote> {
        val counted = listOf(
            notes.charts to R.plurals.pdf_tool_word_to_pdf_note_charts,
            notes.diagrams to R.plurals.pdf_tool_word_to_pdf_note_diagrams,
            notes.shapes to R.plurals.pdf_tool_word_to_pdf_note_shapes,
            notes.equations to R.plurals.pdf_tool_word_to_pdf_note_equations,
            notes.objects to R.plurals.pdf_tool_word_to_pdf_note_objects,
            notes.unreadableImages to R.plurals.pdf_tool_word_to_pdf_note_images,
            notes.clippedRows to R.plurals.pdf_tool_word_to_pdf_note_rows,
            (if (notes.pageLimitReached) DocxLayout.MAX_PAGES else 0) to R.plurals.pdf_tool_word_to_pdf_note_page_limit
        )
        val flagged = listOf(
            notes.fontsReplaced to R.string.pdf_tool_word_to_pdf_note_fonts,
            notes.headersFooters to R.string.pdf_tool_word_to_pdf_note_headers,
            notes.footnotes to R.string.pdf_tool_word_to_pdf_note_footnotes,
            notes.columns to R.string.pdf_tool_word_to_pdf_note_columns
        )
        return counted.filter { it.first > 0 }.map { (count, res) -> ToolNote(res, count, plural = true) } +
            flagged.filter { it.first }.map { (_, res) -> ToolNote(res) }
    }

    private companion object {
        const val PDF_EXTENSION = ".pdf"
        const val SUFFIX_CLASH = "_converted"
        const val SPACE_FACTOR = 4L
        const val SPACE_FLOOR_BYTES = 4L * 1024 * 1024
    }
}
