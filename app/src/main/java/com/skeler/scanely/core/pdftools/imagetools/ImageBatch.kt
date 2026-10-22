package com.skeler.scanely.core.pdftools.imagetools

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.PluralsRes
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImageBatch"

/** One image's answer: the file it produced, and whether that file is the untouched original. */
data class ImageOutcome(val output: ToolOutput, val keptOriginal: Boolean = false)

/** Thrown by a step whose input isn't a readable image; the batch skips it unless nothing else worked. */
class UnreadableImageException : Exception("unreadable image")

/**
 * The loop shared by the four image tools: one image at a time (never two full bitmaps alive from
 * different files), progress and cancellation between images, a skipped-file count instead of a
 * failed run when only some images are unreadable, and every output deleted if the run doesn't
 * finish.
 */
@Singleton
class ImageBatch @Inject constructor(
    private val codec: ImageCodec,
    private val workspace: ToolWorkspace
) {

    /**
     * Runs [step] over every input image. [keptOriginalNote] is the plural shown when steps
     * returned originals; [showSizeChange] passes through to the result.
     */
    suspend fun run(
        input: ToolJobInput,
        label: String,
        showSizeChange: Boolean = false,
        @PluralsRes keptOriginalNote: Int? = null,
        step: suspend ImageBatch.(ImportedFile) -> ImageOutcome
    ): ToolResult = withContext(Dispatchers.Default) {
        val files = input.files
        val outputs = ArrayList<ToolOutput>(files.size)
        var skipped = 0
        var keptOriginal = 0
        var inputBytes = 0L
        var delivered = false
        try {
            files.forEachIndexed { index, file ->
                ensureActive()
                input.onProgress(index.toFloat() / files.size)
                val outcome = runStep(file, index, label, step)
                if (outcome == null) {
                    skipped++
                } else {
                    outputs += outcome.output
                    inputBytes += file.sizeBytes
                    if (outcome.keptOriginal) keptOriginal++
                }
            }
            ensureActive()
            if (outputs.isEmpty()) throw ToolException(ToolFailure.Unreadable)
            input.onProgress(1f)
            if (skipped > 0) Log.w(TAG, "$label skipped $skipped of ${files.size} images")

            val notes = notesFor(keptOriginal, keptOriginalNote, skipped)
            delivered = true
            ToolResult(outputs, inputBytes, notes, showSizeChange)
        } finally {
            if (!delivered) outputs.forEach { it.file.delete() }
        }
    }

    /** One image through [step]; null when it isn't readable, so the batch can skip it. */
    private suspend fun runStep(
        file: ImportedFile,
        index: Int,
        label: String,
        step: suspend ImageBatch.(ImportedFile) -> ImageOutcome
    ): ImageOutcome? = try {
        this.step(file)
    } catch (e: UnreadableImageException) {
        Log.w(TAG, "$label could not read image ${index + 1}", e)
        null
    } catch (e: OutOfMemoryError) {
        // Even sampled, some images (huge panoramas) don't fit; treat them as unreadable.
        Log.w(TAG, "$label ran out of memory on image ${index + 1}", e)
        null
    }

    private fun notesFor(keptOriginal: Int, @PluralsRes keptOriginalNote: Int?, skipped: Int): List<ToolNote> =
        buildList {
            if (keptOriginal > 0 && keptOriginalNote != null) add(ToolNote(keptOriginalNote, keptOriginal, true))
            if (skipped > 0) add(ToolNote(R.plurals.pdf_tool_img_skipped, skipped, plural = true))
        }

    /** Decodes [file] upright or reports it unreadable; see [ImageCodec.decode]. */
    fun decode(file: ImportedFile, sampleFor: (ImageSize) -> Int = { 1 }): DecodedImage =
        codec.decode(file.file, sampleFor) ?: throw UnreadableImageException()

    /**
     * Encodes [bitmap] into a new file in [run] named `<base><suffix>.<ext>`, recycling the bitmap
     * either way. [estimateBytes] is checked against free space first. A full disk becomes
     * [ToolFailure.LowStorage]; a partial file never survives.
     */
    fun write(
        bitmap: Bitmap,
        source: ImportedFile,
        run: File,
        suffix: String,
        format: EncodeFormat,
        quality: Int
    ): ToolOutput {
        try {
            workspace.requireSpace(estimateBytes(bitmap, format, source.sizeBytes))
            return produce(run, outputName(run, source.displayName, suffix, format.extension), format.mimeType) {
                codec.encode(bitmap, format, quality, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A byte-for-byte copy of the input, for when re-encoding couldn't beat it. */
    fun copyOriginal(source: ImportedFile, run: File, suffix: String, format: ImageFormat): ToolOutput {
        workspace.requireSpace(source.sizeBytes)
        val extension = source.displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH && it != source.displayName.lowercase() }
            ?: format.extension
        return produce(run, outputName(run, source.displayName, suffix, extension), mimeOf(format)) {
            source.file.copyTo(it, overwrite = true)
        }
    }

    fun sniff(file: ImportedFile): ImageFormat = codec.sniff(file.file)

    fun flattenOnWhite(bitmap: Bitmap): Bitmap = codec.flattenOnWhite(bitmap)

    private fun produce(run: File, name: String, mimeType: String, writer: (File) -> Unit): ToolOutput {
        val target = File(run, name)
        var done = false
        try {
            writer(target)
            if (!target.isFile || target.length() == 0L) throw ToolException(ToolFailure.Failed("empty image output"))
            done = true
            return ToolOutput(target, name, mimeType, target.length())
        } catch (e: IOException) {
            Log.w(TAG, "Writing an image output failed", e)
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed(e.message), e)
        } finally {
            if (!done) target.delete()
        }
    }

    /** Lossy output is rarely above a byte a pixel; lossless can approach the raw pixels. */
    private fun estimateBytes(bitmap: Bitmap, format: EncodeFormat, inputBytes: Long): Long {
        val pixels = bitmap.width.toLong() * bitmap.height
        val perPixel = when (format) {
            EncodeFormat.JPEG, EncodeFormat.WEBP_LOSSY -> 1L
            EncodeFormat.GIF -> 2L
            EncodeFormat.PNG, EncodeFormat.WEBP_LOSSLESS -> LOSSLESS_BYTES_PER_PIXEL
        }
        return maxOf(inputBytes, pixels * perPixel)
    }

    private fun outputName(run: File, displayName: String, suffix: String, extension: String): String {
        // Sanitised before the suffix goes on, so the length cap can never cut off the extension.
        val base = ToolWorkspace.safeFileName(displayName.substringBeforeLast('.').ifBlank { displayName })
        var name = "$base$suffix.$extension"
        var copy = 2
        // Two picks named alike ("scan.png", "scan.jpg") must not overwrite each other's output.
        while (File(run, name).exists()) name = "$base$suffix (${copy++}).$extension"
        return name
    }

    private fun mimeOf(format: ImageFormat): String = when (format) {
        ImageFormat.JPEG -> EncodeFormat.JPEG.mimeType
        ImageFormat.PNG -> EncodeFormat.PNG.mimeType
        ImageFormat.WEBP -> EncodeFormat.WEBP_LOSSY.mimeType
        ImageFormat.GIF -> EncodeFormat.GIF.mimeType
        ImageFormat.BMP -> MIME_BMP
        ImageFormat.HEIF -> MIME_HEIC
        ImageFormat.OTHER -> MIME_OCTET
    }

    private companion object {
        const val LOSSLESS_BYTES_PER_PIXEL = 4L
        const val MAX_EXTENSION_LENGTH = 5
        const val MIME_BMP = "image/bmp"
        const val MIME_HEIC = "image/heic"
        const val MIME_OCTET = "application/octet-stream"
    }
}
