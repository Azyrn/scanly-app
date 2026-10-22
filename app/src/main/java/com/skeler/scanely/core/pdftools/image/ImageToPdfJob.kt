package com.skeler.scanely.core.pdftools.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.MIME_GIF
import com.skeler.scanely.core.pdftools.MIME_PDF
import com.skeler.scanely.core.pdftools.MIME_PNG
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ImageToPdfJob"

enum class ImagePageSize { FIT, A4, LETTER }

enum class ImagePageOrientation { AUTO, PORTRAIT, LANDSCAPE }

enum class ImagePageMargin(val points: Float) { NONE(0f), SMALL(18f), BIG(36f) }

data class ImageToPdfSettings(
    val pageSize: ImagePageSize = ImagePageSize.A4,
    val orientation: ImagePageOrientation = ImagePageOrientation.AUTO,
    val margin: ImagePageMargin = ImagePageMargin.NONE
)

/**
 * Pictures → one PDF, one image per page, in the user's order.
 *
 * JPEGs — nearly every photo — are embedded as their own bytes, so a scan doesn't lose quality to
 * a second compression and the PDF is barely bigger than the photos. Their EXIF rotation is
 * applied by how the page draws the image, never by re-encoding. Everything else is decoded,
 * downsampled to [MAX_EDGE_PX], and re-encoded: losslessly when it has transparency or came from
 * a PNG/GIF (screenshots, diagrams), as JPEG otherwise.
 *
 * PDFBox keeps the document's streams in a scratch file in the run directory, so only the image
 * being added is ever in memory, however many there are.
 */
@Singleton
class ImageToPdfJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspace: ToolWorkspace
) {

    suspend fun run(
        images: List<ImportedFile>,
        settings: ImageToPdfSettings,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        require(images.isNotEmpty()) { "no images" }
        PDFBoxResourceLoader.init(context)
        val inputBytes = images.sumOf { it.sizeBytes }
        // The scratch file and the saved PDF each hold roughly every image once.
        workspace.requireSpace(inputBytes * 2)

        val name = PdfToJpgJob.baseName(images.first().displayName) + SUFFIX
        val output = File(run, name)
        val scratch = File(run, "pdfbox-${System.nanoTime()}").apply { mkdirs() }
        var delivered = false
        try {
            val unreadable = buildPdf(images, settings, output, scratch, onProgress)
            coroutineContext.ensureActive()
            if (!output.isFile || output.length() == 0L) {
                throw ToolException(ToolFailure.Failed("save wrote no output"))
            }
            onProgress(1f)
            val notes = if (unreadable > 0) {
                listOf(ToolNote(R.plurals.pdf_tool_img2pdf_unreadable, unreadable, plural = true))
            } else {
                emptyList()
            }
            delivered = true
            ToolResult(listOf(ToolOutput(output, name, MIME_PDF, output.length())), inputBytes, notes)
        } catch (e: IOException) {
            Log.w(TAG, "Building the PDF failed", e)
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed(e.message), e)
        } finally {
            scratch.deleteRecursively()
            if (!delivered) output.delete()
        }
    }

    /**
     * Adds one page per image into a scratch-backed document and saves it to [output]; returns how
     * many images couldn't be read. Fails as Damaged when none could.
     */
    private suspend fun buildPdf(
        images: List<ImportedFile>,
        settings: ImageToPdfSettings,
        output: File,
        scratch: File,
        onProgress: (Float?) -> Unit
    ): Int {
        var unreadable = 0
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)).use { pdf ->
            images.forEachIndexed { index, image ->
                coroutineContext.ensureActive()
                onProgress(index.toFloat() / images.size)
                val placed = loadImage(pdf, image)
                if (placed == null) {
                    unreadable++
                } else {
                    addPage(pdf, placed, settings)
                }
            }
            coroutineContext.ensureActive()
            if (unreadable == images.size) throw ToolException(ToolFailure.Damaged)
            onProgress(null)
            pdf.save(output)
        }
        return unreadable
    }

    /** Null for a file that isn't an image this phone can decode; the rest of the run goes on. */
    private fun loadImage(pdf: PDDocument, image: ImportedFile): PlacedImage? {
        val jpeg = runCatching { JpegHeader.read(image.file) }.getOrNull()
        if (jpeg != null && jpeg.precision == JPEG_PRECISION && jpeg.components in PASS_THROUGH_COMPONENTS) {
            return passThroughJpeg(pdf, image.file, jpeg)
        }
        return reencode(pdf, image.file)
    }

    /** Embeds the JPEG's own bytes; the page applies its EXIF rotation. */
    private fun passThroughJpeg(pdf: PDDocument, file: File, jpeg: JpegHeader): PlacedImage {
        val colorSpace = if (jpeg.components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
        val xObject = file.inputStream().buffered().use { input ->
            PDImageXObject(pdf, input, COSName.DCT_DECODE, jpeg.width, jpeg.height, JPEG_PRECISION, colorSpace)
        }
        return PlacedImage(xObject, jpeg.width, jpeg.height, exifOrientation(file))
    }

    /** Decodes, downsamples and re-encodes; null when the phone can't decode [file]. */
    private fun reencode(pdf: PDDocument, file: File): PlacedImage? {
        val decoded = decode(file) ?: return null
        val bitmap = decoded.bitmap
        return try {
            val lossless = bitmap.hasAlpha() || decoded.mimeType == MIME_PNG || decoded.mimeType == MIME_GIF
            val xObject = if (lossless) {
                LosslessFactory.createFromImage(pdf, bitmap)
            } else {
                JPEGFactory.createFromImage(pdf, bitmap, REENCODE_QUALITY)
            }
            // Page size follows the original pixels, so downsampling doesn't shrink a fitted page.
            PlacedImage(xObject, decoded.naturalWidth, decoded.naturalHeight, decoded.orientation)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(file: File): Decoded? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeModern(file) else decodeLegacy(file)
    } catch (e: IOException) {
        Log.w(TAG, "An image couldn't be decoded", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "An image was too large to decode", e)
        null
    }

    /** ImageDecoder reads HEIC/AVIF too, and applies EXIF rotation itself. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(file: File): Decoded {
        var naturalWidth = 0
        var naturalHeight = 0
        var mimeType: String? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            // PDFBox reads pixels back from the bitmap, which a hardware bitmap can't do.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            naturalWidth = info.size.width
            naturalHeight = info.size.height
            mimeType = info.mimeType
            val longest = max(naturalWidth, naturalHeight)
            if (longest > MAX_EDGE_PX) {
                val scale = MAX_EDGE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (naturalWidth * scale).roundToInt().coerceAtLeast(1),
                    (naturalHeight * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
        return Decoded(bitmap, naturalWidth, naturalHeight, mimeType, ExifInterface.ORIENTATION_NORMAL)
    }

    private fun decodeLegacy(file: File): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        val longest = max(bitmap.width, bitmap.height)
        if (longest > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / longest
            val scaled = bitmap.scale(
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                filter = true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        // BitmapFactory ignores EXIF, so the page applies it, as for pass-through JPEGs.
        return Decoded(bitmap, bounds.outWidth, bounds.outHeight, bounds.outMimeType, exifOrientation(file))
    }

    private fun exifOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun addPage(pdf: PDDocument, image: PlacedImage, settings: ImageToPdfSettings) {
        val layout = layoutPage(image.width, image.height, image.orientation, settings)
        val page = PDPage(PDRectangle(layout.pageWidth, layout.pageHeight))
        pdf.addPage(page)
        PDPageContentStream(pdf, page).use { content ->
            content.drawImage(image.xObject, orientationMatrix(image.orientation, layout))
        }
    }

    private class PlacedImage(val xObject: PDImageXObject, val width: Int, val height: Int, val orientation: Int)

    private class Decoded(
        val bitmap: Bitmap,
        val naturalWidth: Int,
        val naturalHeight: Int,
        val mimeType: String?,
        val orientation: Int
    )

    /** Where the upright image lands on its page, in points. */
    internal data class PageLayout(
        val pageWidth: Float,
        val pageHeight: Float,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    /** The SOF fields that decide whether a JPEG can go into a PDF as its own bytes. */
    internal data class JpegHeader(val width: Int, val height: Int, val components: Int, val precision: Int) {
        companion object {
            /** Null for anything that isn't a JPEG with a frame header before its scan data. */
            fun read(file: File): JpegHeader? {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    return try {
                        val isJpeg = input.readUnsignedByte() == MARKER && input.readUnsignedByte() == SOI
                        if (isJpeg) frame(input) else null
                    } catch (_: EOFException) {
                        // A file that ends before its frame header isn't a usable JPEG.
                        null
                    }
                }
            }

            private fun frame(input: DataInputStream): JpegHeader? {
                while (true) {
                    val marker = nextMarker(input)
                    if (marker == null || marker == SOS || marker == EOI) return null
                    if (marker in STANDALONE_MARKERS) continue
                    val length = input.readUnsignedShort()
                    if (length < 2) return null
                    if (marker in SOF_MARKERS) return startOfFrame(input)
                    input.skipFully(length - 2)
                }
            }

            /** The next marker code, skipping fill bytes; null when the stream isn't at a marker. */
            private fun nextMarker(input: DataInputStream): Int? {
                if (input.readUnsignedByte() != MARKER) return null
                var marker = input.readUnsignedByte()
                while (marker == MARKER) marker = input.readUnsignedByte()
                return marker
            }

            /** Reads an SOF segment's fields, just past its length; null for a zero-sized frame. */
            private fun startOfFrame(input: DataInputStream): JpegHeader? {
                val precision = input.readUnsignedByte()
                val height = input.readUnsignedShort()
                val width = input.readUnsignedShort()
                val components = input.readUnsignedByte()
                return if (width == 0 || height == 0) null else JpegHeader(width, height, components, precision)
            }

            private fun DataInputStream.skipFully(count: Int) {
                var left = count
                while (left > 0) {
                    val skipped = skipBytes(left)
                    if (skipped <= 0) throw EOFException()
                    left -= skipped
                }
            }

            private const val MARKER = 0xFF
            private const val SOI = 0xD8
            private const val SOS = 0xDA
            private const val EOI = 0xD9
            private val STANDALONE_MARKERS = (0xD0..0xD7).toSet() + 0x01
            private val SOF_MARKERS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)
        }
    }

    internal companion object {
        /**
         * Page and image placement for an image of [width]×[height] stored pixels. EXIF
         * orientations 5–8 turn the picture a quarter, so its upright shape is the stored one
         * transposed — that upright shape is what Auto orientation and fitting look at.
         */
        fun layoutPage(width: Int, height: Int, orientation: Int, settings: ImageToPdfSettings): PageLayout {
            val quarterTurn = orientation in QUARTER_TURN_ORIENTATIONS
            val uprightWidth = (if (quarterTurn) height else width).coerceAtLeast(1).toFloat()
            val uprightHeight = (if (quarterTurn) width else height).coerceAtLeast(1).toFloat()
            return if (settings.pageSize == ImagePageSize.FIT) {
                fitLayout(uprightWidth, uprightHeight, settings.margin.points)
            } else {
                paperLayout(uprightWidth, uprightHeight, settings)
            }
        }

        /** A page cut to the picture's own shape, plus margins. */
        private fun fitLayout(uprightWidth: Float, uprightHeight: Float, margin: Float): PageLayout {
            // Nothing in a picture says how big it really is; 96 dpi treats pixels as a screen
            // does, and the PDF page-size ceiling keeps a huge panorama a valid page.
            var scale = POINTS_PER_PIXEL
            val longest = max(uprightWidth, uprightHeight) * scale + 2 * margin
            if (longest > MAX_PAGE_POINTS) scale *= (MAX_PAGE_POINTS - 2 * margin) / (longest - 2 * margin)
            val w = uprightWidth * scale
            val h = uprightHeight * scale
            return PageLayout(w + 2 * margin, h + 2 * margin, margin, margin, w, h)
        }

        /** The picture fitted and centred on an A4 or Letter page. */
        private fun paperLayout(uprightWidth: Float, uprightHeight: Float, settings: ImageToPdfSettings): PageLayout {
            val margin = settings.margin.points
            val paper = if (settings.pageSize == ImagePageSize.A4) PDRectangle.A4 else PDRectangle.LETTER
            val landscape = when (settings.orientation) {
                ImagePageOrientation.AUTO -> uprightWidth > uprightHeight
                ImagePageOrientation.PORTRAIT -> false
                ImagePageOrientation.LANDSCAPE -> true
            }
            val short = min(paper.width, paper.height)
            val long = max(paper.width, paper.height)
            val pageWidth = if (landscape) long else short
            val pageHeight = if (landscape) short else long
            val boxWidth = (pageWidth - 2 * margin).coerceAtLeast(1f)
            val boxHeight = (pageHeight - 2 * margin).coerceAtLeast(1f)
            val scale = min(boxWidth / uprightWidth, boxHeight / uprightHeight)
            val w = uprightWidth * scale
            val h = uprightHeight * scale
            return PageLayout(pageWidth, pageHeight, (pageWidth - w) / 2, (pageHeight - h) / 2, w, h)
        }

        /**
         * Maps the image's unit square onto [layout]'s box so the picture appears upright. For
         * each EXIF orientation, (u, v) is where stored point (a, b) — a rightwards, b upwards in
         * the unit square — ends up in the upright box; the matrix is that map scaled into points.
         */
        fun orientationMatrix(orientation: Int, layout: PageLayout): Matrix {
            // Rows: u = ua·a + ub·b + uc, v = va·a + vb·b + vc.
            val (u, v) = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, 1f) to floatArrayOf(0f, 1f, 0f)
                ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, 1f) to floatArrayOf(0f, -1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, -1f, 1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, -1f, 1f) to floatArrayOf(-1f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 1f)
                ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(1f, 0f, 0f)
                ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, -1f, 1f) to floatArrayOf(1f, 0f, 0f)
                else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
            }
            val w = layout.width
            val h = layout.height
            return Matrix(
                w * u[0],
                h * v[0],
                w * u[1],
                h * v[1],
                layout.x + w * u[2],
                layout.y + h * v[2]
            )
        }

        private val QUARTER_TURN_ORIENTATIONS = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )
    }
}

private const val SUFFIX = "_images.pdf"
private const val MAX_EDGE_PX = 4000
private const val REENCODE_QUALITY = 0.9f
private const val JPEG_PRECISION = 8
private val PASS_THROUGH_COMPONENTS = setOf(1, 3)

/** 72 points per inch over 96 pixels per inch. */
private const val POINTS_PER_PIXEL = 0.75f

/** The largest page PDF readers accept: 200 inches. */
private const val MAX_PAGE_POINTS = 14_400f
