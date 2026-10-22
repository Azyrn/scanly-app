package com.skeler.scanely.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Builds upload payloads; the server alone determines billable page counts. */
@Singleton
internal class PayloadFactory @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    class PayloadException(message: String) : Exception(message)

    sealed interface Payload {
        class File(
            val bytes: ByteArray,
            val mimeType: String,
            val fileName: String
        ) : Payload

        data class LocalText(val text: String) : Payload
    }

    fun isPlainText(uri: Uri): Boolean =
        context.contentResolver.getType(uri) == TEXT_MIME_TYPE

    fun create(uri: Uri, maxUploadBytes: Long = MAX_UPLOAD_BYTES): Payload {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val uploadLimit = maxUploadBytes.coerceIn(0, MAX_UPLOAD_BYTES)

        if (mimeType == TEXT_MIME_TYPE) {
            val bytes = loadFileBytes(uri) ?: throw PayloadException("Failed to read text file")
            return Payload.LocalText(bytes.toString(Charsets.UTF_8))
        }

        if (mimeType.startsWith("image/")) {
            val bitmap = loadBitmapFromUri(uri) ?: throw PayloadException("Failed to load image")
            return try {
                // Downscaling limits upload size without changing image page cost.
                val bytes = encodeJpeg(bitmap)
                if (bytes.size > uploadLimit) throw PayloadException("This image exceeds your plan's upload limit.")
                Payload.File(bytes, "image/jpeg", "scan.jpg")
            } finally {
                bitmap.recycle()
            }
        }

        if (mimeType == PDF_MIME_TYPE || mimeType in OFFICE_MIME_TYPES) {
            val bytes = loadFileBytes(uri, uploadLimit) ?: throw PayloadException("Failed to read document")
            return Payload.File(bytes, mimeType, if (mimeType == PDF_MIME_TYPE) "document.pdf" else "document")
        }

        throw PayloadException(
            "Unsupported file type: $mimeType\n\n" +
                "Supported:\n" +
                "• PDF files (.pdf)\n" +
                "• Word (.doc, .docx)\n" +
                "• PowerPoint (.ppt, .pptx)\n" +
                "• OpenDocument (.odt, .odp)\n" +
                "• Text files (.txt)\n" +
                "• Images (.jpg, .png, .webp)\n\n" +
                "For Excel files, please convert to PDF first."
        )
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val scaled = downscale(bitmap)
        val output = ByteArrayOutputStream()
        return try {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw PayloadException("Failed to encode image")
            }
            output.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_IMAGE_DIMENSION) return bitmap
        val ratio = MAX_IMAGE_DIMENSION.toFloat() / maxDim
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }

    private fun loadFileBytes(uri: Uri, maxBytes: Long = MAX_UPLOAD_BYTES): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                // Read at most one byte past the limit, including for providers with no size metadata.
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
                if (count < 0) break
                total += count
                if (total > maxBytes) throw PayloadException("This file exceeds the upload size limit.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (e: PayloadException) {
        throw e
    } catch (e: Exception) {
        aiDebug { "read file failed: ${e.message}" }
        null
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_IMAGE_DIMENSION * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        aiDebug { "load bitmap failed: ${e.message}" }
        null
    }

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val TEXT_MIME_TYPE = "text/plain"

        val OFFICE_MIME_TYPES = setOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.presentation"
        )

        // Client memory ceiling, not an entitlement. Per-plan limits still come from /v1/me.
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 1536
        private const val JPEG_QUALITY = 85
    }
}
