package com.skeler.scanely.core.pdftools

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dagger.MapKey
import java.io.File

/** What a tool picks, and so what the shared flow hands its job. */
enum class ToolInput(val mimeTypes: Array<String>, val multiple: Boolean, val isPdf: Boolean) {
    PDF(arrayOf(MIME_PDF), multiple = false, isPdf = true),
    PDFS(arrayOf(MIME_PDF), multiple = true, isPdf = true),
    IMAGES(arrayOf("image/*"), multiple = true, isPdf = false),
    WORD(arrayOf(MIME_DOCX), multiple = false, isPdf = false),
    POWERPOINT(arrayOf(MIME_PPTX), multiple = false, isPdf = false),
    EXCEL(arrayOf(MIME_XLSX), multiple = false, isPdf = false)
}

const val MIME_PDF = "application/pdf"
const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val MIME_JPEG = "image/jpeg"
const val MIME_PNG = "image/png"
const val MIME_WEBP = "image/webp"
const val MIME_GIF = "image/gif"
const val MIME_TEXT = "text/plain"

/**
 * Everything a job gets. PDF inputs arrive in [documents], already probed and opened (with the
 * password the user typed, if any); every other input arrives in [files] as a plain copy. Both
 * lists are in the order the user arranged them. Outputs go in [run], which the flow deletes when
 * the user leaves — a job never writes anywhere else.
 *
 * [onProgress] takes 0..1, or null for "working, no estimate". Jobs call it between units of work
 * (pages, files) and must also call `coroutineContext.ensureActive()` there, so Cancel lands
 * promptly; anything left in [run] by a cancelled or failed job must be deleted by the job.
 */
data class ToolJobInput(
    val documents: List<ToolDocument>,
    val files: List<ImportedFile>,
    val run: File,
    val onProgress: (Float?) -> Unit
)

/**
 * A line the result screen shows under the outputs — "2 pages had no text", "charts were left
 * out". A [plural] note is shown with [count]; otherwise [res] is a plain string.
 */
data class ToolNote(@StringRes @PluralsRes val res: Int, val count: Int = 0, val plural: Boolean = false)

/** Binds a ToolSpec into the map the grid and the flow read. */
@MapKey
annotation class PdfToolKey(val value: PdfTool)
