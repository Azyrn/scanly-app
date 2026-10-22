package com.skeler.scanely.core.pdftools.imagetools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.skeler.scanely.core.pdftools.MIME_GIF
import com.skeler.scanely.core.pdftools.MIME_JPEG
import com.skeler.scanely.core.pdftools.MIME_PNG
import com.skeler.scanely.core.pdftools.MIME_WEBP
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImageCodec"

/** What a file's first bytes say it is; the display name's extension is only a hint. */
enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    BMP("bmp"),
    HEIF("heic"),
    OTHER("img")
}

/** What the image tools write. WebP splits in two because lossy and lossless are different promises. */
enum class EncodeFormat(val mimeType: String, val extension: String) {
    JPEG(MIME_JPEG, "jpg"),
    PNG(MIME_PNG, "png"),
    WEBP_LOSSY(MIME_WEBP, "webp"),
    WEBP_LOSSLESS(MIME_WEBP, "webp"),
    GIF(MIME_GIF, "gif")
}

/** Width and height as the user sees the image, i.e. after EXIF orientation. */
data class ImageSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * [bitmap] is upright and software-backed; [fullSize] is the upright size of the file before any
 * sampling, which is what a resize computes its target from.
 */
class DecodedImage(val bitmap: Bitmap, val fullSize: ImageSize)

/**
 * Decoding and encoding for the image tools, with the two things that go wrong on phones handled
 * in one place: photos stored sideways with an EXIF orientation tag, and camera images too large
 * to hold in memory at full size.
 *
 * Every output is a fresh encode of pixels through [Bitmap.compress], which writes no metadata at
 * all — so EXIF location (GPS), camera serials and capture times never reach an output, even
 * though the input copies carry them.
 */
@Singleton
class ImageCodec @Inject constructor() {

    fun sniff(file: File): ImageFormat {
        val head = ByteArray(SNIFF_BYTES)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read < MIN_SNIFF_BYTES) return ImageFormat.OTHER
        return SIGNATURES.firstOrNull { (_, magics) -> magics.all { it.matches(head) } }?.first ?: ImageFormat.OTHER
    }

    /** [bytes] expected at [offset] of a file's head. */
    private class Magic(val offset: Int, vararg val bytes: Int) {
        constructor(offset: Int, text: String) : this(offset, *text.map { it.code }.toIntArray())

        fun matches(head: ByteArray): Boolean = bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    }

    /**
     * Decodes [file] upright. [sampleFor] gets the full upright size and returns the power-of-two
     * subsampling the caller can live with (1 for full size); the decoder may go further when the
     * image would not fit [MAX_DECODE_PIXELS]. Returns null when the file isn't an image this
     * phone can read.
     *
     * API 28+ goes through ImageDecoder, which already applies EXIF orientation and reads HEIF;
     * BitmapFactory never applies it, so that path rotates by hand.
     */
    fun decode(file: File, sampleFor: (ImageSize) -> Int = { 1 }): DecodedImage? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return decodeWithImageDecoder(file, sampleFor)
            } catch (e: IOException) {
                Log.w(TAG, "ImageDecoder refused the image, trying BitmapFactory", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "ImageDecoder refused the image, trying BitmapFactory", e)
            }
        }
        return decodeWithBitmapFactory(file, sampleFor)
    }

    private fun decodeWithImageDecoder(file: File, sampleFor: (ImageSize) -> Int): DecodedImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        var full: ImageSize? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val size = ImageSize(info.size.width, info.size.height)
            full = size
            // Software so the pixels can be read back (GIF) and re-encoded without a GPU copy.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(budgetedSample(size, sampleFor(size)))
        }
        val size = full ?: return null.also { bitmap.recycle() }
        return DecodedImage(bitmap, upright(size, bitmap))
    }

    private fun decodeWithBitmapFactory(file: File, sampleFor: (ImageSize) -> Int): DecodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = exifOrientation(file)
        val swaps = orientation in SWAPPING_ORIENTATIONS
        val size = if (swaps) {
            ImageSize(bounds.outHeight, bounds.outWidth)
        } else {
            ImageSize(bounds.outWidth, bounds.outHeight)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = budgetedSample(size, sampleFor(size))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.path, options) ?: return null
        return DecodedImage(rotateUpright(raw, orientation), size)
    }

    private fun exifOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** [raw] turned upright for its EXIF [orientation]; the original is recycled when a copy is made. */
    private fun rotateUpright(raw: Bitmap, orientation: Int): Bitmap {
        val matrix = orientationMatrix(orientation) ?: return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /**
     * Writes [bitmap] to [target]. [quality] is 0..100 and only matters to the lossy formats.
     * Throws IOException when the encoder or the disk refuses; the caller deletes the file.
     */
    fun encode(bitmap: Bitmap, format: EncodeFormat, quality: Int, target: File) {
        target.outputStream().buffered().use { out ->
            if (format == EncodeFormat.GIF) {
                val width = bitmap.width
                GifEncoder.encode(width, bitmap.height, out) { y, row ->
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                }
            } else {
                val (compressFormat, effectiveQuality) = compressFormat(format, quality)
                if (!bitmap.compress(compressFormat, effectiveQuality, out)) {
                    throw IOException("Bitmap.compress refused ${format.name}")
                }
            }
        }
    }

    /**
     * JPEG has no alpha, and Android's JPEG encoder drops it rather than compositing — transparent
     * areas would come out black. Returns [bitmap] itself when it has no alpha; otherwise a new
     * bitmap on white, and recycles [bitmap].
     */
    fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return flat
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(format: EncodeFormat, quality: Int): Pair<Bitmap.CompressFormat, Int> = when (format) {
        EncodeFormat.JPEG -> Bitmap.CompressFormat.JPEG to quality
        // PNG ignores quality; it is always lossless.
        EncodeFormat.PNG -> Bitmap.CompressFormat.PNG to MAX_QUALITY
        // WEBP_LOSSY/WEBP_LOSSLESS arrived in API 30; before that WEBP is lossy below 100, lossless at 100.
        EncodeFormat.WEBP_LOSSY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY to quality
        } else {
            Bitmap.CompressFormat.WEBP to quality.coerceAtMost(MAX_QUALITY - 1)
        }
        EncodeFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS to MAX_QUALITY
        } else {
            Bitmap.CompressFormat.WEBP to MAX_QUALITY
        }
        EncodeFormat.GIF -> error("GIF is encoded by GifEncoder")
    }

    /** The decoder's reported size may or may not be upright; the decoded bitmap's shape decides. */
    private fun upright(size: ImageSize, bitmap: Bitmap): ImageSize {
        val sizeLandscape = size.width > size.height
        val bitmapLandscape = bitmap.width > bitmap.height
        return if (size.width != size.height && sizeLandscape != bitmapLandscape) {
            ImageSize(size.height, size.width)
        } else {
            size
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(HALF_TURN)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(QUARTER_TURN)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(QUARTER_TURN)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-QUARTER_TURN)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-QUARTER_TURN)
            else -> return null
        }
        return matrix
    }

    companion object {
        /**
         * The most pixels decoded at once: ~96 MB as ARGB, so a flatten or a scaled copy still fits
         * beside it. A 48 MP photo is halved once; anything a normal camera bins to stays whole.
         */
        const val MAX_DECODE_PIXELS = 24_000_000L

        const val MAX_QUALITY = 100

        /** Largest power-of-two sample that keeps [size] at least [minWidth] x [minHeight]. */
        fun sampleToFit(size: ImageSize, minWidth: Int, minHeight: Int): Int {
            var sample = 1
            while (size.width / (sample * 2) >= minWidth && size.height / (sample * 2) >= minHeight) sample *= 2
            return sample
        }

        internal fun budgetedSample(size: ImageSize, requested: Int): Int {
            var sample = Integer.highestOneBit(requested.coerceAtLeast(1))
            while ((size.width / sample).toLong() * (size.height / sample) > MAX_DECODE_PIXELS) sample *= 2
            return sample
        }

        private const val SNIFF_BYTES = 12
        private const val MIN_SNIFF_BYTES = 12
        private const val WEBP_TAG_OFFSET = 8
        private const val FTYP_OFFSET = 4

        /** In match order: RIFF alone isn't WebP, so WebP needs both of its marks. */
        private val SIGNATURES: List<Pair<ImageFormat, List<Magic>>> = listOf(
            ImageFormat.JPEG to listOf(Magic(0, 0xFF, 0xD8, 0xFF)),
            ImageFormat.PNG to listOf(Magic(0, 0x89, 'P'.code, 'N'.code, 'G'.code)),
            ImageFormat.GIF to listOf(Magic(0, "GIF8")),
            ImageFormat.BMP to listOf(Magic(0, "BM")),
            ImageFormat.WEBP to listOf(Magic(0, "RIFF"), Magic(WEBP_TAG_OFFSET, "WEBP")),
            ImageFormat.HEIF to listOf(Magic(FTYP_OFFSET, "ftyp"))
        )
        private const val QUARTER_TURN = 90f
        private const val HALF_TURN = 180f

        private val SWAPPING_ORIENTATIONS = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )
    }
}
