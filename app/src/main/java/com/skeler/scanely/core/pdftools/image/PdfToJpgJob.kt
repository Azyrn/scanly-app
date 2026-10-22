package com.skeler.scanely.core.pdftools.image

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import com.skeler.scanely.core.pdftools.MIME_JPEG
import com.skeler.scanely.core.pdftools.MIME_PNG
import com.skeler.scanely.core.pdftools.ToolDocument
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
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "PdfToJpgJob"

/** Render resolution; 150 dpi reads well on a phone, 300 dpi is print quality. */
enum class JpgQuality(val dpi: Int) { NORMAL(150), HIGH(300) }

/**
 * PDF → pictures, two ways: render each page as a JPEG, or pull out the images embedded in the
 * file at their original quality. Both write one file per page/image into the run directory and
 * delete everything they wrote if they fail or are cancelled.
 */
@Singleton
class PdfToJpgJob @Inject constructor(
    private val pageReader: PdfPageReader,
    private val workspace: ToolWorkspace
) {

    /**
     * Pages are rendered one at a time and each bitmap is recycled before the next, so memory
     * stays at one page whatever the document's length. The pixel cap is what keeps a poster-sized
     * page from asking for a bitmap bigger than the heap.
     */
    suspend fun pagesToJpg(
        document: ToolDocument,
        quality: JpgQuality,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        val geometries = pageReader.pageGeometries(document.file, document.password)
            ?: throw ToolException(ToolFailure.Damaged)
        coroutineContext.ensureActive()

        val base = baseName(document.displayName)
        val digits = maxOf(MIN_INDEX_DIGITS, geometries.size.toString().length)
        val outputs = mutableListOf<ToolOutput>()
        var delivered = false
        try {
            geometries.forEachIndexed { index, geometry ->
                coroutineContext.ensureActive()
                onProgress(index.toFloat() / geometries.size)

                val (width, height) = renderSize(geometry.widthPt, geometry.heightPt, quality.dpi)
                workspace.requireSpace(width.toLong() * height / JPEG_BYTES_PER_PIXEL_DIVISOR)
                val bitmap = pageReader.renderPage(document.file, index, width, document.password)
                    ?: throw ToolException(ToolFailure.Failed("render failed on page ${index + 1}"))
                val name = "${base}_page-${(index + 1).toString().padStart(digits, '0')}.jpg"
                val file = File(run, name)
                try {
                    writeJpeg(bitmap, file)
                } finally {
                    bitmap.recycle()
                }
                outputs += ToolOutput(file, name, MIME_JPEG, file.length())
            }
            coroutineContext.ensureActive()
            onProgress(1f)
            delivered = true
            return ToolResult(outputs, document.sizeBytes)
        } finally {
            if (!delivered) outputs.forEach { it.file.delete() }
        }
    }

    /**
     * Saves every image the document embeds, each once even when it repeats on every page. JPEGs
     * come out byte-identical; plain RGB and grey images come out as PNG; anything else (CMYK,
     * indexed, JPEG 2000, fax) is counted in a note rather than converted badly.
     *
     * qpdf does the reading in page chunks, which is where cancellation and progress land: the
     * native call itself can't be interrupted.
     */
    suspend fun extractImages(
        document: ToolDocument,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        val base = baseName(document.displayName)
        val scratch = File(run, "extract-${System.nanoTime()}").apply { mkdirs() }
        val outputs = mutableListOf<ToolOutput>()
        val seen = LongArrayList()
        var skipped = 0
        var delivered = false
        try {
            var first = 0
            while (first < document.pageCount) {
                coroutineContext.ensureActive()
                onProgress(first.toFloat() / document.pageCount)
                // Extracted images are at most the document's own bytes, plus the PNG wrapping.
                workspace.requireSpace(document.sizeBytes * 2)

                val end = minOf(first + PAGES_PER_CHUNK, document.pageCount)
                val chunk = extractChunk(document, scratch, first, end, seen)
                skipped += chunk.skipped
                for (image in chunk.images) {
                    coroutineContext.ensureActive()
                    outputs += save(image, run, base, outputs.size + 1)
                }
                first = end
            }
            coroutineContext.ensureActive()
            if (outputs.isEmpty()) {
                throw ToolException(ToolFailure.NothingFound)
            }
            onProgress(1f)
            val notes = if (skipped > 0) {
                listOf(ToolNote(R.plurals.pdf_tool_jpg_images_skipped, skipped, plural = true))
            } else {
                emptyList()
            }
            delivered = true
            return ToolResult(outputs, document.sizeBytes, notes)
        } finally {
            scratch.deleteRecursively()
            if (!delivered) outputs.forEach { it.file.delete() }
        }
    }

    private suspend fun extractChunk(
        document: ToolDocument,
        scratch: File,
        first: Int,
        end: Int,
        seen: LongArrayList
    ): ExtractedChunk = withContext(Dispatchers.IO) {
        val json = Qpdf.nativeExtractImages(
            document.file.absolutePath,
            document.password,
            scratch.absolutePath,
            first,
            end,
            seen.toArray(),
            MAX_IMAGE_PIXELS
        ) ?: throw engineFailure(Qpdf.nativeLastError())
        try {
            parseChunk(JSONObject(json), seen)
        } catch (e: JSONException) {
            throw ToolException(ToolFailure.Failed("unreadable extract answer"), e)
        }
    }

    private fun parseChunk(json: JSONObject, seen: LongArrayList): ExtractedChunk {
        val ids = json.getJSONArray("ids")
        for (i in 0 until ids.length()) seen += ids.getLong(i)
        val images = json.getJSONArray("images")
        val list = (0 until images.length()).map { i ->
            val image = images.getJSONObject(i)
            ExtractedImage(
                file = File(image.getString("path")),
                isJpeg = image.getString("kind") == "jpeg",
                width = image.optInt("w"),
                height = image.optInt("h"),
                components = image.optInt("comps")
            )
        }
        return ExtractedChunk(list, json.getInt("skipped"))
    }

    /** Moves a JPEG into place as-is, or wraps raw samples into a PNG, freeing the scratch copy. */
    private suspend fun save(image: ExtractedImage, run: File, base: String, number: Int): ToolOutput =
        withContext(Dispatchers.IO) {
            val stem = "${base}_image-${number.toString().padStart(MIN_INDEX_DIGITS, '0')}"
            if (image.isJpeg) {
                val name = "$stem.jpg"
                val target = File(run, name)
                if (!image.file.renameTo(target)) {
                    image.file.copyTo(target, overwrite = true)
                    image.file.delete()
                }
                ToolOutput(target, name, MIME_JPEG, target.length())
            } else {
                val name = "$stem.png"
                val target = File(run, name)
                try {
                    writePng(image, target)
                } finally {
                    image.file.delete()
                }
                ToolOutput(target, name, MIME_PNG, target.length())
            }
        }

    /** Row by row, so the raw samples are never held alongside the bitmap. */
    private fun writePng(image: ExtractedImage, target: File) {
        val width = image.width
        val height = image.height
        val components = image.components
        val supported = components == 1 || components == RGB_COMPONENTS
        if (width <= 0 || height <= 0 || !supported) {
            throw ToolException(ToolFailure.Failed("bad raw image header"))
        }
        val bitmap = try {
            createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw ToolException(ToolFailure.Failed("image too large for memory"), e)
        }
        try {
            val rowBytes = ByteArray(width * components)
            val rowPixels = IntArray(width)
            image.file.inputStream().buffered().use { input ->
                for (y in 0 until height) {
                    readFully(input, rowBytes)
                    unpackRow(rowBytes, rowPixels, components)
                    bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
                }
            }
            writeCompressed(bitmap, target, Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED)
        } finally {
            bitmap.recycle()
        }
    }

    /** Turns one row of gray or RGB samples into opaque ARGB pixels. */
    private fun unpackRow(rowBytes: ByteArray, rowPixels: IntArray, components: Int) {
        for (x in rowPixels.indices) {
            rowPixels[x] = if (components == 1) {
                val g = rowBytes[x].toInt() and BYTE_MASK
                OPAQUE or (g shl RED_SHIFT) or (g shl GREEN_SHIFT) or g
            } else {
                val o = x * RGB_COMPONENTS
                OPAQUE or
                    ((rowBytes[o].toInt() and BYTE_MASK) shl RED_SHIFT) or
                    ((rowBytes[o + 1].toInt() and BYTE_MASK) shl GREEN_SHIFT) or
                    (rowBytes[o + 2].toInt() and BYTE_MASK)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw ToolException(ToolFailure.Failed("raw image shorter than declared"))
            offset += read
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) =
        writeCompressed(bitmap, file, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)

    private fun writeCompressed(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat, quality: Int) {
        val ok = try {
            file.outputStream().buffered().use { bitmap.compress(format, quality, it) }
        } catch (e: IOException) {
            file.delete()
            Log.w(TAG, "Writing an image failed", e)
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed(e.message), e)
        }
        if (!ok) {
            file.delete()
            throw ToolException(ToolFailure.Failed("image encoder refused the bitmap"))
        }
    }

    /** qpdf names the failing file and object, never content; a full disk is the user's to fix. */
    private fun engineFailure(message: String): ToolException {
        Log.w(TAG, "extract failed: $message")
        return if (IOException(message).isOutOfSpace()) {
            ToolException(ToolFailure.LowStorage)
        } else {
            ToolException(ToolFailure.Failed(message))
        }
    }

    private class ExtractedImage(
        val file: File,
        val isJpeg: Boolean,
        val width: Int,
        val height: Int,
        val components: Int
    )

    private class ExtractedChunk(val images: List<ExtractedImage>, val skipped: Int)

    /** Grows without boxing; a document can reference thousands of images. */
    private class LongArrayList {
        private var values = LongArray(INITIAL_IDS)
        private var size = 0

        operator fun plusAssign(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): LongArray = values.copyOf(size)
    }

    internal companion object {
        /**
         * Width and height in pixels for a page at [dpi], capped by edge and by area. Width alone
         * is capped at [MAX_EDGE_PX]; the area cap then shrinks both sides for tall pages, whose
         * height the width cap doesn't bound.
         */
        fun renderSize(widthPt: Float, heightPt: Float, dpi: Int): Pair<Int, Int> {
            val w = widthPt.coerceAtLeast(1f)
            val h = heightPt.coerceAtLeast(1f)
            var width = (w * dpi / POINTS_PER_INCH).coerceAtMost(MAX_EDGE_PX.toFloat())
            var height = width * h / w
            if (width * height > MAX_RENDER_PIXELS) {
                val scale = sqrt(MAX_RENDER_PIXELS / (width * height))
                width *= scale
                height *= scale
            }
            return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
        }

        fun baseName(displayName: String): String =
            ToolWorkspace.safeFileName(displayName.substringBeforeLast('.').ifBlank { displayName })
    }
}

private const val POINTS_PER_INCH = 72f
private const val MAX_EDGE_PX = 4000

/** 16 MP: a 64 MB ARGB bitmap, the most one page or one extracted image may take. */
private const val MAX_RENDER_PIXELS = 16_000_000f
private const val MAX_IMAGE_PIXELS = 16_000_000L

private const val JPEG_QUALITY = 90
private const val PNG_QUALITY_IGNORED = 100

/** A quality-90 JPEG of a page rarely tops a quarter byte per pixel; half a byte leaves headroom. */
private const val JPEG_BYTES_PER_PIXEL_DIVISOR = 2
private const val MIN_INDEX_DIGITS = 3
private const val PAGES_PER_CHUNK = 20
private const val INITIAL_IDS = 64

private const val RGB_COMPONENTS = 3
private const val BYTE_MASK = 0xFF
private const val OPAQUE = -0x1000000
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
