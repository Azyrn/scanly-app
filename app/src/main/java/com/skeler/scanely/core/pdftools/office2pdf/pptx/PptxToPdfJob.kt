package com.skeler.scanely.core.pdftools.office2pdf.pptx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "PptxToPdfJob"

/** A deck longer than this is converted up to here, with a note — not refused outright. */
internal const val MAX_SLIDES = 1000

/**
 * Android's PdfDocument keeps every finished page (and every picture on it) in memory until it is
 * written, so long or picture-heavy decks are written in chunks and joined by qpdf at the end.
 */
private const val SLIDES_PER_CHUNK = 20
private const val CHUNK_IMAGE_BYTES = 64L * 1024 * 1024

/** Longest side of any decoded picture; a full-slide photo needs no more at print density. */
private const val MAX_IMAGE_PX = 2048

private const val RENDER_SHARE = 0.95f
private const val CHUNK_OVERHEAD_BYTES = 4L * 1024 * 1024
private const val PDF_EXTENSION = ".pdf"

/**
 * PowerPoint to PDF, on the phone, with our own renderer: one page per visible slide at the deck's
 * own page size. What the renderer can't draw — charts, SmartArt, embedded objects, video — is
 * left out and said so in the result's notes, never a reason to fail the whole deck.
 */
@Singleton
class PptxToPdfJob @Inject constructor(
    private val workspace: ToolWorkspace,
    private val qpdf: QpdfEngine
) {

    suspend fun convert(input: ImportedFile, run: File, onProgress: (Float?) -> Unit): ToolResult =
        withContext(Dispatchers.IO) {
            workspace.requireSpace(input.sizeBytes * 2)
            val output = File(run, outputName(input.displayName))
            val parts = ArrayList<File>()
            var delivered = false
            try {
                val notes = convertDeck(input.file, output, run, parts, onProgress)
                ensureActive()
                if (!output.isFile || output.length() == 0L) {
                    throw ToolException(ToolFailure.Failed("ppt2pdf wrote no output"))
                }
                onProgress(1f)
                delivered = true
                ToolResult(
                    outputs = listOf(ToolOutput(output, output.name, MIME_PDF, output.length())),
                    inputBytes = input.sizeBytes,
                    notes = notes
                )
            } finally {
                parts.forEach { it.delete() }
                if (!delivered) output.delete()
            }
        }

    /** Reads the deck, renders its visible slides into [output] and returns the result's notes. */
    private suspend fun convertDeck(
        file: File,
        output: File,
        run: File,
        parts: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): List<ToolNote> = openReader(file).use { r ->
        val presentation = readPresentation(r)
        if (presentation.slides.isEmpty()) throw ToolException(ToolFailure.Damaged)

        // A deck whose every slide is hidden still converts: the user asked for these slides.
        val visible = presentation.slides.filterNot { it.hidden }.ifEmpty { presentation.slides }
        val hidden = presentation.slides.size - visible.size
        val slides = visible.take(MAX_SLIDES)
        val unreadable = render(r, presentation, slides, run, parts, onProgress)

        join(parts, output)
        notesFor(r.issues, hidden, unreadable, truncated = visible.size > MAX_SLIDES)
    }

    private fun openReader(file: File): PptxReader = try {
        PptxReader(file)
    } catch (e: PptxFormatException) {
        throw damaged("open", e)
    }

    private fun readPresentation(reader: PptxReader): PptxPresentation = try {
        reader.readPresentation()
    } catch (e: PptxFormatException) {
        throw damaged("presentation", e)
    } catch (e: IOException) {
        throw damaged("presentation", e)
    }

    /** Renders [slides] into chunk files appended to [parts]; returns how many slides couldn't be read. */
    private suspend fun render(
        reader: PptxReader,
        presentation: PptxPresentation,
        slides: List<SlideRef>,
        run: File,
        parts: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): Int {
        val images = ChunkImages(reader)
        val renderer = PptxRenderer(images, reader.issues)
        val pdf = ChunkedPdf(presentation, run, parts, images)
        var unreadable = 0
        try {
            for ((index, ref) in slides.withIndex()) {
                coroutineContext.ensureActive()
                val slide = readSlideOrNull(reader, ref, index)
                if (slide == null) {
                    unreadable++
                } else {
                    pdf.addPage { canvas -> renderer.draw(canvas, slide, presentation.widthPt, presentation.heightPt) }
                }
                onProgress((index + 1f) / slides.size * RENDER_SHARE)
            }
            pdf.finish()
        } finally {
            pdf.close()
            images.clear()
        }
        if (pdf.pages == 0) throw ToolException(ToolFailure.Damaged)
        return unreadable
    }

    /** One unreadable slide is skipped and counted, not a reason to fail the deck. */
    private fun readSlideOrNull(reader: PptxReader, ref: SlideRef, index: Int): PptxSlide? = try {
        reader.readSlide(ref.part)
    } catch (e: PptxFormatException) {
        Log.w(TAG, "slide ${index + 1} unreadable: ${e.message}")
        null
    } catch (e: IOException) {
        Log.w(TAG, "slide ${index + 1} unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * The PDF being written, one chunk at a time: a chunk is flushed to its own file in [parts]
     * once it holds [SLIDES_PER_CHUNK] pages or [CHUNK_IMAGE_BYTES] of decoded pictures.
     */
    private inner class ChunkedPdf(
        presentation: PptxPresentation,
        private val run: File,
        private val parts: MutableList<File>,
        private val images: ChunkImages
    ) {
        private val pageWidth = presentation.widthPt.roundToInt()
        private val pageHeight = presentation.heightPt.roundToInt()
        private var document: PdfDocument? = null
        private var inChunk = 0

        var pages = 0
            private set

        fun addPage(draw: (Canvas) -> Unit) {
            val doc = document ?: PdfDocument().also { document = it }
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pages + 1).create()
            val page = doc.startPage(info)
            try {
                draw(page.canvas)
            } finally {
                doc.finishPage(page)
            }
            pages++
            inChunk++
            if (inChunk >= SLIDES_PER_CHUNK || images.bytes >= CHUNK_IMAGE_BYTES) {
                document = null
                parts += flush(doc, File(run, "ppt2pdf-part-${parts.size}.pdf"), images.bytes)
                images.clear()
                inChunk = 0
            }
        }

        /** Writes the last, partly filled chunk. */
        fun finish() {
            document?.let { doc ->
                document = null
                parts += flush(doc, File(run, "ppt2pdf-part-${parts.size}.pdf"), images.bytes)
            }
        }

        /** Releases a chunk that was never written (the conversion failed or was cancelled). */
        fun close() {
            document?.close()
        }
    }

    private fun flush(document: PdfDocument, file: File, imageBytes: Long): File {
        var written = false
        try {
            workspace.requireSpace(imageBytes / 2 + CHUNK_OVERHEAD_BYTES)
            FileOutputStream(file).use { document.writeTo(it) }
            written = true
            return file
        } catch (e: IOException) {
            Log.w(TAG, "writing a chunk failed: ${e.javaClass.simpleName}")
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed("write"), e)
        } finally {
            if (!written) file.delete()
            document.close()
        }
    }

    private suspend fun join(parts: List<File>, output: File) {
        if (parts.size == 1) {
            if (!parts[0].renameTo(output)) copyPart(parts[0], output)
            return
        }
        workspace.requireSpace(parts.sumOf { it.length() })
        mergeError(qpdf.merge(parts, output))?.let { throw it }
    }

    /** A rename can fail across mount points; a copy then does the same job. */
    private fun copyPart(part: File, output: File) {
        try {
            part.copyTo(output, overwrite = true)
        } catch (e: IOException) {
            val failure = if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed("copy")
            throw ToolException(failure, e)
        }
    }

    private fun mergeError(result: QpdfResult): ToolException? = when (result) {
        QpdfResult.Ok -> null
        QpdfResult.WrongPassword -> ToolException(ToolFailure.Failed("merge asked for a password"))
        is QpdfResult.Failed -> mergeFailure(result.message)
        is QpdfResult.BadUsage -> mergeFailure(result.message)
    }

    private fun mergeFailure(message: String): ToolException {
        Log.w(TAG, "joining chunks failed: $message")
        return if (IOException(message).isOutOfSpace()) {
            ToolException(ToolFailure.LowStorage)
        } else {
            ToolException(ToolFailure.Failed(message))
        }
    }

    private fun damaged(stage: String, e: Exception): ToolException {
        Log.w(TAG, "$stage: not a readable presentation (${e.javaClass.simpleName})")
        return ToolException(ToolFailure.Damaged, e)
    }

    private fun notesFor(issues: PptxIssues, hidden: Int, unreadable: Int, truncated: Boolean): List<ToolNote> =
        buildList {
            fun plural(res: Int, count: Int) {
                if (count > 0) add(ToolNote(res, count, plural = true))
            }
            if (truncated) plural(R.plurals.pdf_tool_ppt2pdf_truncated, MAX_SLIDES)
            plural(R.plurals.pdf_tool_ppt2pdf_unreadable, unreadable)
            plural(R.plurals.pdf_tool_ppt2pdf_hidden, hidden)
            plural(R.plurals.pdf_tool_ppt2pdf_charts, issues.charts)
            plural(R.plurals.pdf_tool_ppt2pdf_diagrams, issues.diagrams)
            plural(R.plurals.pdf_tool_ppt2pdf_objects, issues.embeddedObjects)
            plural(R.plurals.pdf_tool_ppt2pdf_media, issues.media)
            plural(R.plurals.pdf_tool_ppt2pdf_images, issues.missingImages)
            if (issues.shapesSimplified) add(ToolNote(R.string.pdf_tool_ppt2pdf_shapes))
            if (issues.fontsReplaced) add(ToolNote(R.string.pdf_tool_ppt2pdf_fonts))
        }

    private fun outputName(displayName: String): String {
        val base = displayName.substringBeforeLast('.').ifBlank { displayName }
        return ToolWorkspace.safeFileName(base) + PDF_EXTENSION
    }
}

/**
 * Decoded pictures for the chunk being drawn. A logo on the master appears on every slide, so one
 * decode serves them all; the bitmaps live until the chunk is written, because the PDF document
 * still references them until then.
 */
private class ChunkImages(private val reader: PptxReader) : ImageSource {
    private val bitmaps = HashMap<String, Bitmap?>()
    private val bounds = HashMap<String, IntArray?>()
    private val failed = HashSet<String>()

    var bytes = 0L
        private set

    override fun bitmap(part: String, widthPx: Int, heightPx: Int): Bitmap? {
        val size = bounds.getOrPut(part) { measure(part) } ?: return miss(part)
        val sample = sampleSize(size[0], size[1], widthPx, heightPx)
        val key = "$part#$sample"
        if (bitmaps.containsKey(key)) return bitmaps[key]
        val bitmap = decode(part, sample)
        bitmaps[key] = bitmap
        if (bitmap == null) return miss(part)
        bytes += bitmap.allocationByteCount
        return bitmap
    }

    fun clear() {
        bitmaps.values.forEach { it?.recycle() }
        bitmaps.clear()
        bytes = 0L
    }

    private fun miss(part: String): Bitmap? {
        if (failed.add(part)) reader.issues.missingImages++
        return null
    }

    private fun measure(part: String): IntArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            // A bounds-only decode always returns null by design; only a missing part is a miss.
            val stream = reader.openMedia(part) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            return null
        }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            intArrayOf(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun decode(part: String, sample: Int): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        reader.openMedia(part)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: IOException) {
        null
    } catch (_: OutOfMemoryError) {
        Log.w(TAG, "picture too large to decode")
        null
    }

    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (
            max(width, height) / sample > MAX_IMAGE_PX ||
            (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight)
        ) {
            sample *= 2
        }
        return sample
    }
}
