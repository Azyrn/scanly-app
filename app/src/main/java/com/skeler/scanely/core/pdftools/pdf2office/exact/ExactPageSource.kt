package com.skeler.scanely.core.pdftools.pdf2office.exact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.skeler.scanely.core.pdfeditor.engine.PdfiumImage
import com.skeler.scanely.core.pdfeditor.engine.PdfiumLayer
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ExactPageSource"

/**
 * Reads PDF pages for the exact-layout writers: the text as [ExactPageLayout], and a JPEG of the
 * page drawn without its text. A page with no text at all (a scan) comes back with an empty layout
 * and its full render as the background, so it still looks like the page.
 *
 * Holds one pdfium document for the whole conversion. Not thread-safe; close it when done.
 */
class ExactPageSource private constructor(private val layer: PdfiumLayer) : AutoCloseable {

    /** Null when pdfium can't load the page at all. */
    fun read(index: Int, scratch: File): ExactPage? {
        val page = layer.readPage(index) ?: return null
        val layout = ExactLayout.read(page)
        val pictures = ArrayList<PagePicture>()
        val extracted = ArrayList<PdfiumImage>()
        layer.images(index).forEachIndexed { n, image ->
            extract(index, n, image, page.heightPt, scratch)?.let {
                pictures += it
                extracted += image
            }
        }
        val background = renderBackground(index, page.widthPt, page.heightPt, extracted, scratch)
        return ExactPage(page.widthPt, page.heightPt, layout, background, pictures)
    }

    /** Page [index]'s text again with the layout model's table regions ([ExactLayout.read]); null when unreadable. */
    fun layout(index: Int, modelTables: List<LayoutBox>): ExactPageLayout? =
        layer.readPage(index)?.let { ExactLayout.read(it, modelTables) }

    /**
     * Writes one image to a file of its own, so Word shows it as a picture the user can select and
     * move, at its original resolution. A JPEG that can be copied byte for byte is; anything else
     * is drawn with its mask and placement and saved as PNG when it has transparency or few colours
     * (line art, scans), JPEG otherwise. Null leaves the image in the background render instead.
     */
    private fun extract(index: Int, n: Int, image: PdfiumImage, pageHeightPt: Float, scratch: File): PagePicture? {
        val width = image.right - image.left
        val height = image.top - image.bottom
        if (width < MIN_PICTURE_PT || height < MIN_PICTURE_PT) return null
        val name = "image${index}_$n"
        fun picture(file: File, png: Boolean) = PagePicture(
            file,
            png,
            image.left,
            pageHeightPt - image.top,
            width,
            height
        )
        val copied = if (image.copyableJpeg) copiedJpeg(index, image, File(scratch, "$name.jpg")) else null
        if (copied != null) return picture(copied, png = false)
        return drawn(index, n, image, File(scratch, name))?.let { (file, png) -> picture(file, png) }
    }

    /** The image's own JPEG bytes in [file], when they really are a JPEG; null otherwise. */
    private fun copiedJpeg(index: Int, image: PdfiumImage, file: File): File? {
        val bytes = layer.imageBytes(index, image) ?: return null
        val jpeg = bytes.size > 2 && bytes[0] == JPEG_MAGIC_0 && bytes[1] == JPEG_MAGIC_1
        return if (jpeg) file.apply { writeBytes(bytes) } else null
    }

    /** The image drawn with its mask and saved next to [stem] as PNG or JPEG; null without memory for it. */
    private fun drawn(index: Int, n: Int, image: PdfiumImage, stem: File): Pair<File, Boolean>? {
        val bitmap = try {
            layer.imageBitmap(index, image, MAX_IMAGE_PIXELS)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory for image $n on page $index", e)
            null
        } ?: return null
        try {
            val png = prefersPng(bitmap)
            val file = File(stem.parentFile, if (png) "${stem.name}.png" else "${stem.name}.jpg")
            file.outputStream().buffered().use {
                if (png) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, PICTURE_JPEG_QUALITY, it)
                }
            }
            return file to png
        } finally {
            bitmap.recycle()
        }
    }

    /** Transparency, or few distinct colours in a sample: PNG keeps edges exact and stays small. */
    private fun prefersPng(bitmap: Bitmap): Boolean {
        val colours = HashSet<Int>()
        val stepX = (bitmap.width / SAMPLE_SIDE).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_SIDE).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap[x, y]
                if (Color.alpha(pixel) < OPAQUE) return true
                colours += pixel
            }
        }
        return colours.size <= FEW_COLOURS
    }

    /**
     * The whole page as pixels for recognition (pictures included, text removed), at [OCR_DPI]
     * capped to [MAX_SIDE_PX]; null when the page can't be drawn. The caller recycles it.
     */
    fun renderForOcr(index: Int, widthPt: Float, heightPt: Float): Bitmap? {
        val longest = max(widthPt, heightPt).coerceAtLeast(1f)
        val scale = min(OCR_DPI / POINTS_PER_INCH, MAX_SIDE_PX / longest)
        val width = (widthPt * scale).roundToInt().coerceAtLeast(1)
        val height = (heightPt * scale).roundToInt().coerceAtLeast(1)
        val bitmap = try {
            createBitmap(width, height)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory to read page $index", e)
            return null
        }
        if (layer.renderBackground(index, bitmap)) return bitmap
        bitmap.recycle()
        return null
    }

    private fun renderBackground(
        index: Int,
        widthPt: Float,
        heightPt: Float,
        extracted: List<PdfiumImage>,
        scratch: File
    ): File? {
        val longest = max(widthPt, heightPt).coerceAtLeast(1f)
        val scale = min(BACKGROUND_DPI / POINTS_PER_INCH, MAX_SIDE_PX / longest)
        val width = (widthPt * scale).roundToInt().coerceAtLeast(1)
        val height = (heightPt * scale).roundToInt().coerceAtLeast(1)
        val bitmap = try {
            createBitmap(width, height)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory for page $index background", e)
            return null
        }
        try {
            if (!layer.renderBackground(index, bitmap, extracted) || isBlank(bitmap)) return null
            // JPEG: a lossless 200 DPI PNG scored the same on the corpus and took ten times longer
            // to encode (a 142-page document went from 14 s to over 200 s).
            val file = File(scratch, "background$index.jpg")
            file.outputStream().buffered().use {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    BACKGROUND_JPEG_QUALITY,
                    it
                )
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    /** A page that is white once its text is gone needs no picture behind it. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step BLANK_ROW_STEP) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it != Color.WHITE }) return false
        }
        return true
    }

    override fun close() = layer.close()

    companion object {
        private const val BACKGROUND_DPI = 150f
        private const val OCR_DPI = 200f
        private const val BACKGROUND_JPEG_QUALITY = 85
        private const val POINTS_PER_INCH = 72f
        private const val MAX_SIDE_PX = 2_400f
        private const val BLANK_ROW_STEP = 2
        private const val MIN_PICTURE_PT = 3f
        private const val MAX_IMAGE_PIXELS = 24_000_000
        private const val PICTURE_JPEG_QUALITY = 90
        private const val PNG_QUALITY = 100
        private const val SAMPLE_SIDE = 64
        private const val FEW_COLOURS = 64
        private const val OPAQUE = 255
        private const val JPEG_MAGIC_0 = 0xFF.toByte()
        private const val JPEG_MAGIC_1 = 0xD8.toByte()

        /** Null when the pdfium calls this needs aren't available or the file won't open. */
        fun open(context: Context, file: File, password: String): ExactPageSource? =
            PdfiumLayer.open(context, file, password)?.let(::ExactPageSource)
    }
}
