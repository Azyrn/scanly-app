package com.skeler.scanely.core.pdftools

import android.graphics.BitmapFactory
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The check every tool result passes before the user is offered it: a file that does not open, has
 * no pages, or (for a conversion that draws pages) is blank on every page is never saved or shared.
 *
 * Cheap on purpose — it runs after every job. PDFs are opened with pdfium (the reader the app and
 * most viewers use); Office files are opened as the zip they are, and their main part and some
 * content must be there; images must decode their bounds. A blank-page check renders tiny
 * thumbnails and stops at the first page with anything on it.
 */
@Singleton
class OutputValidator @Inject constructor(private val pageReader: PdfPageReader) {

    /** Null when every output is fine, else why the first one is not (for logs and reports only). */
    suspend fun problem(result: ToolResult, checkBlank: Boolean): String? = withContext(Dispatchers.IO) {
        if (result.outputs.isEmpty()) return@withContext "no outputs"
        for (output in result.outputs) {
            coroutineContext.ensureActive()
            val reason = problem(output, checkBlank)
            if (reason != null) return@withContext reason
        }
        null
    }

    private suspend fun problem(output: ToolOutput, checkBlank: Boolean): String? {
        val file = output.file
        if (!file.isFile || file.length() == 0L) return "empty file"
        return when {
            output.mimeType == MIME_PDF -> pdfProblem(file, checkBlank)
            output.mimeType in OFFICE_MAIN_PARTS -> officeProblem(file, output.mimeType)
            output.mimeType.startsWith("image/") -> imageProblem(file)
            else -> null
        }
    }

    private suspend fun pdfProblem(file: File, checkBlank: Boolean): String? {
        val pages = pageReader.pageCount(file) ?: return "pdf does not open"
        if (pages <= 0) return "pdf has no pages"
        if (!checkBlank) return null
        for (index in 0 until minOf(pages, MAX_BLANK_CHECK_PAGES)) {
            coroutineContext.ensureActive()
            val bitmap = pageReader.renderPage(file, index, THUMB_WIDTH_PX) ?: return "pdf page $index does not render"
            val blank = try {
                isBlank(bitmap)
            } finally {
                bitmap.recycle()
            }
            if (!blank) return null
        }
        return if (pages > MAX_BLANK_CHECK_PAGES) null else "every page is blank"
    }

    private fun isBlank(bitmap: android.graphics.Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.all { pixel ->
            val r = pixel shr RED_SHIFT and CHANNEL
            val g = pixel shr GREEN_SHIFT and CHANNEL
            val b = pixel and CHANNEL
            r >= NEAR_WHITE && g >= NEAR_WHITE && b >= NEAR_WHITE
        }
    }

    private fun officeProblem(file: File, mimeType: String): String? = try {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val main = OFFICE_MAIN_PARTS.getValue(mimeType)
            when {
                CONTENT_TYPES !in names -> "no [Content_Types].xml"
                main !in names -> "no $main"
                names.none { name -> hasContent(name, mimeType) } -> "no content parts"
                else -> null
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        "office file does not open: ${e.javaClass.simpleName}"
    }

    private fun hasContent(name: String, mimeType: String): Boolean =
        OFFICE_CONTENT.getValue(mimeType).any { name.startsWith(it) }

    private fun imageProblem(file: File): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) null else "image does not decode"
    }

    private companion object {
        const val CONTENT_TYPES = "[Content_Types].xml"
        val OFFICE_MAIN_PARTS = mapOf(
            MIME_DOCX to "word/document.xml",
            MIME_PPTX to "ppt/presentation.xml",
            MIME_XLSX to "xl/workbook.xml"
        )

        /** At least one of these must exist: a document body, a slide, a sheet. */
        val OFFICE_CONTENT = mapOf(
            MIME_DOCX to listOf("word/document.xml"),
            MIME_PPTX to listOf("ppt/slides/slide"),
            MIME_XLSX to listOf("xl/worksheets/sheet")
        )
        const val THUMB_WIDTH_PX = 200
        const val MAX_BLANK_CHECK_PAGES = 400
        const val NEAR_WHITE = 245
        const val CHANNEL = 0xFF
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}
