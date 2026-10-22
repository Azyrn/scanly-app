package com.skeler.scanely.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A picked document, or the reason it cannot be used, so the caller can say which. */
sealed interface PickedDocument {
    data class Ready(val base64: String, val pages: Int) : PickedDocument
    data class TooManyPages(val pages: Int, val limit: Int) : PickedDocument
    data object TooLarge : PickedDocument
}

/**
 * Turns a file the user picked into something the designer can take: an illustration into JPEG
 * bytes small enough to travel and be stored, and a document into the bytes the model reads.
 */
@Singleton
class DesignInputs @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pdfReader: PdfPageReader
) {
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Selected file"
        } catch (_: Exception) { "Selected file" }
    }

    /**
     * A picked image as base64 JPEG, or null if it could not be read.
     *
     * Downscaled and re-encoded rather than sent as picked. A modern phone camera produces several
     * megabytes, the server refuses anything over 500 KB, and the bytes are stored with the design
     * so a revision can recompile it — a portrait on a page needs none of that resolution. JPEG
     * because the compile service accepts exactly one format, so the filename it writes is fixed
     * and the model can be told the path.
     */
    suspend fun photo(uri: Uri): String? = withContext(Dispatchers.IO) {
        encodeJpeg(uri, MAX_PHOTO_BYTES, PHOTO_EDGE_PX)
    }

    /**
     * The document edge is larger than the photo one because the two are read differently: a
     * portrait only has to look right, while this is the copy small print is transcribed from, and
     * detail lost here is detail the model cannot recover. It costs no tokens — an image is billed
     * at a flat rate whatever its size.
     */
    private fun encodeJpeg(uri: Uri, maxBytes: Int, maxEdge: Int): String? {
        val bitmap = decodeScaled(uri, maxEdge) ?: return null
        try {
            var quality = INITIAL_QUALITY
            var bytes = compress(bitmap, quality)
            while (bytes.size > maxBytes && quality > MIN_QUALITY) {
                quality -= QUALITY_STEP
                bytes = compress(bitmap, quality)
            }
            if (bytes.size > maxBytes) return null
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A picked document as base64, ready for the model to read directly.
     *
     * A PDF travels as itself; an image is downscaled and re-encoded as JPEG the way [photo] is.
     * Sending the bytes rather than text pulled out with pdfium is what makes "rebuild this" mean
     * the page the user is looking at — the layout is most of what they are asking to keep, and a
     * photographed page has no text layer to read in the first place.
     */
    suspend fun document(uri: Uri): PickedDocument? = withContext(Dispatchers.IO) {
        try {
            val pdf = context.contentResolver.openInputStream(uri)?.use { stream ->
                val magic = ByteArray(5)
                var count = 0
                while (count < magic.size) {
                    val read = stream.read(magic, count, magic.size - count)
                    if (read < 0) break
                    count += read
                }
                magic.contentEquals("%PDF-".toByteArray())
            } == true
            if (pdf) pickedPdf(uri) else pickedImage(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DocumentTooLarge) {
            PickedDocument.TooLarge
        } catch (_: Exception) { null }
    }

    private suspend fun pickedPdf(uri: Uri): PickedDocument? {
        val file = copyToCache(uri) ?: return null
        try {
            val pages = pdfReader.pageCount(file) ?: return null
            if (pages > MAX_PAGES) return PickedDocument.TooManyPages(pages, MAX_PAGES)
            val bytes = file.readBytes()
            if (bytes.size > MAX_DOCUMENT_BYTES) return PickedDocument.TooLarge
            return PickedDocument.Ready(Base64.encodeToString(bytes, Base64.NO_WRAP), pages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            file.delete()
        }
    }

    private fun pickedImage(uri: Uri): PickedDocument? {
        // Always re-encoded: one format whatever was picked (HEIC, WebP, PNG), the long edge bounded, the
        // orientation baked into the pixels, and none of the file's metadata (EXIF, GPS) carried along.
        val encoded = encodeJpeg(uri, MAX_DOCUMENT_BYTES, DOCUMENT_EDGE_PX) ?: return null
        return PickedDocument.Ready(encoded, pages = 1)
    }

    /** Two passes: bounds only, then a subsampled decode, so a large image is never fully in memory. */
    private fun decodeScaled(uri: Uri, maxEdge: Int): Bitmap? {
        val sample = sampleSizeFor(uri, maxEdge) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
        return decoded?.let { orient(uri, scaleToMaxEdge(it, maxEdge)) }
    }

    /** Camera JPEGs usually store orientation as metadata; re-encoding must bake it into pixels. */
    private fun orient(uri: Uri, bitmap: Bitmap): Bitmap {
        val matrix = orientationMatrix(orientationOf(uri)) ?: return bitmap
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** Missing or invalid EXIF metadata leaves the decoded pixels unchanged. */
    private fun orientationOf(uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull()

    /** Null when the image cannot be measured. */
    private fun sampleSizeFor(uri: Uri, maxEdge: Int): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxEdge * 2) sample *= 2
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap

        val scale = maxEdge.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** pdfium reads a file, not a stream, and a picked Uri may be a provider that has neither. */
    private fun copyToCache(uri: Uri): File? {
        val file = File.createTempFile("design-source-", ".pdf", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    copyBounded(input, output)
                }
            } ?: run {
                file.delete()
                return null
            }
            file
        } catch (e: DocumentTooLarge) {
            file.delete()
            throw e
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_DOCUMENT_BYTES) throw DocumentTooLarge()
            output.write(buffer, 0, count)
        }
    }

    private class DocumentTooLarge : Exception()

    private companion object {
        /** Mirrors MAX_PHOTO_BYTES in the Worker's typst.ts, so an upload fails here, not there. */
        const val MAX_PHOTO_BYTES = 500 * 1024

        /** Mirrors MAX_DOCUMENT_BYTES and MAX_DOCUMENT_PAGES in the Worker's gemini.ts. */
        const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
        const val MAX_PAGES = 10

        const val PHOTO_EDGE_PX = 1200
        const val DOCUMENT_EDGE_PX = 2048
        const val INITIAL_QUALITY = 85
        const val MIN_QUALITY = 50
        const val QUALITY_STEP = 10
    }
}

private fun orientationMatrix(orientation: Int?): Matrix? {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(HALF_TURN_DEGREES)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(QUARTER_TURN_DEGREES)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-QUARTER_TURN_DEGREES)
        else -> return null
    }
    return matrix
}

private const val QUARTER_TURN_DEGREES = 90f
private const val HALF_TURN_DEGREES = 180f

private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
