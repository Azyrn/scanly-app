package com.skeler.scanely.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Joins the photos of one AI scan into PDFs, one photo per page, so a multi-photo scan is one
 * request bounded by the plan's per-document page limit rather than one request per photo.
 *
 * Each photo is prepared exactly as a single-photo scan uploads it — longest edge
 * [MAX_IMAGE_DIMENSION], JPEG at [JPEG_QUALITY] — and its JPEG bytes are embedded unchanged, so
 * the model sees the same pixels either way. Photos go to disk as they are encoded, so only one
 * bitmap is ever in memory however many were picked.
 */
@Singleton
class PhotoBundler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    class BundleException(message: String) : Exception(message)

    /**
     * Returns content URIs of PDFs holding [photos] in order; more than one only when the photos
     * exceed [maxPages] or [maxBytes] for one upload. Throws [BundleException] when a photo
     * can't be read, rather than scanning a set with a page silently missing.
     */
    suspend fun bundle(photos: List<Uri>, maxPages: Int, maxBytes: Long): List<Uri> = withContext(Dispatchers.IO) {
        require(photos.isNotEmpty()) { "no photos" }
        PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("could not create $dir")

        val encoded = photos.mapIndexed { index, uri ->
            coroutineContext.ensureActive()
            encode(uri, File(dir, "photo-$index.jpg"))
                ?: throw BundleException("photo ${index + 1} of ${photos.size} could not be decoded")
        }

        val budget = (maxBytes * BYTE_HEADROOM).toLong()
        chunkRanges(encoded.map { it.file.length() }, maxPages, budget).mapIndexed { index, range ->
            coroutineContext.ensureActive()
            val pdf = File(dir, "scan-${index + 1}.pdf")
            writePdf(encoded.subList(range.first, range.last + 1), pdf, dir)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        }.also {
            encoded.forEach { it.file.delete() }
        }
    }

    private class EncodedPhoto(val file: File, val width: Int, val height: Int)

    /** Null when the phone can't decode [uri]. */
    private fun encode(uri: Uri, out: File): EncodedPhoto? {
        val bitmap = decode(uri) ?: return null
        return try {
            out.outputStream().buffered().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) return null
            }
            EncodedPhoto(out, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF rotation and reads HEIC/AVIF where the phone supports them.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_DIMENSION) {
                    val ratio = MAX_IMAGE_DIMENSION.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            decodeLegacy(uri)
        }
    } catch (e: IOException) {
        aiDebug { "photo decode failed: ${e.message}" }
        null
    } catch (e: OutOfMemoryError) {
        aiDebug { "photo too large to decode: ${e.message}" }
        null
    }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return scaleDown(decoded)
    }

    private fun scaleDown(decoded: Bitmap): Bitmap {
        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_IMAGE_DIMENSION) return decoded
        val ratio = MAX_IMAGE_DIMENSION.toFloat() / longest
        val scaled = decoded.scale(
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1)
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun writePdf(photos: List<EncodedPhoto>, out: File, scratchParent: File) {
        val scratch = File(scratchParent, "pdfbox-${System.nanoTime()}").apply { mkdirs() }
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)).use { pdf ->
                photos.forEach { photo ->
                    val image = photo.file.inputStream().buffered().use { input ->
                        PDImageXObject(
                            pdf,
                            input,
                            COSName.DCT_DECODE,
                            photo.width,
                            photo.height,
                            8,
                            PDDeviceRGB.INSTANCE
                        )
                    }
                    // Page size at PAGE_DPI, so an upright photo lands near A4 rather than poster size.
                    val width = photo.width * POINTS_PER_INCH / PAGE_DPI
                    val height = photo.height * POINTS_PER_INCH / PAGE_DPI
                    val page = PDPage(PDRectangle(width, height))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, width, height) }
                }
                pdf.save(out)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    companion object {
        private const val DIR = "ai-bundles"

        /** Same preparation as a single-photo scan; see PayloadFactory. */
        private const val MAX_IMAGE_DIMENSION = 1536
        private const val JPEG_QUALITY = 85
        private const val POINTS_PER_INCH = 72f
        private const val PAGE_DPI = 150f

        /** Room for the PDF's own structure on top of the JPEG bytes. */
        private const val BYTE_HEADROOM = 0.95

        /**
         * Splits photos of [sizes] bytes, in order, into consecutive runs of at most [maxPages]
         * photos whose sizes sum to at most [maxBytes]. A photo bigger than [maxBytes] on its own
         * still gets a run of its own, for the server to refuse with its real reason.
         */
        internal fun chunkRanges(sizes: List<Long>, maxPages: Int, maxBytes: Long): List<IntRange> {
            val pageCap = maxPages.coerceAtLeast(1)
            val ranges = mutableListOf<IntRange>()
            var start = 0
            var bytes = 0L
            sizes.forEachIndexed { index, size ->
                val count = index - start
                if (count > 0 && (count >= pageCap || bytes + size > maxBytes)) {
                    ranges += start until index
                    start = index
                    bytes = 0L
                }
                bytes += size
            }
            if (sizes.isNotEmpty()) ranges += start until sizes.size
            return ranges
        }
    }
}
