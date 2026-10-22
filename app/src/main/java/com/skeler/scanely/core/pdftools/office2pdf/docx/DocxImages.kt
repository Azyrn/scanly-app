package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import android.util.Size
import java.util.zip.ZipFile
import kotlin.math.ceil

/**
 * Pictures from the zip, decoded only at the moment a page draws them and recycled straight
 * after, so a document with hundreds of photos never holds more than one bitmap. Sizes are read
 * from headers alone and cached, because layout asks for them long before anything is drawn.
 */
internal class DocxImages(private val zip: ZipFile) {
    private val sizes = HashMap<String, Size?>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Pixel size, or null for a format the platform can't decode (EMF, WMF, SVG, TIFF). */
    fun size(entry: String): Size? {
        if (entry in sizes) return sizes[entry]
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        readPart(zip, entry, IMAGE_CAP)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        val readable = options.outWidth > 0 && options.outHeight > 0
        val size = if (readable) Size(options.outWidth, options.outHeight) else null
        sizes[entry] = size
        return size
    }

    fun draw(canvas: Canvas, entry: String, target: RectF) {
        val bitmap = decode(entry, target) ?: return
        try {
            canvas.drawBitmap(bitmap, null, target, paint)
        } finally {
            bitmap.recycle()
        }
    }

    /** Sampled down to about [DRAW_DPI] at its drawn size: more would only inflate the PDF. */
    private fun decode(entry: String, target: RectF): Bitmap? {
        val size = size(entry) ?: return null
        val wantedWidth = (target.width() / POINTS_PER_INCH * DRAW_DPI).coerceAtLeast(1f)
        val wantedHeight = (target.height() / POINTS_PER_INCH * DRAW_DPI).coerceAtLeast(1f)
        var sample = 1
        while (size.width / (sample * 2) >= wantedWidth && size.height / (sample * 2) >= wantedHeight) {
            sample *= 2
        }
        while (size.width.toLong() / sample * (size.height / sample) > MAX_PIXELS) {
            sample *= 2
        }
        val bytes = readPart(zip, entry, IMAGE_CAP) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (ignored: OutOfMemoryError) {
            null
        }
    }

    private companion object {
        const val IMAGE_CAP = 48L * 1024 * 1024
        const val MAX_PIXELS = 16_000_000L
        const val DRAW_DPI = 150f
        const val POINTS_PER_INCH = 72f
    }
}

/**
 * An inline picture inside a paragraph's text. It reserves its box in the line (so the line is
 * as tall as the picture) and decodes only when the page actually draws it.
 */
internal class PictureSpan(
    private val images: DocxImages,
    private val entry: String,
    private val width: Float,
    private val height: Float
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            fm.ascent = -ceil(height).toInt()
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        }
        return ceil(width).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val target = RectF(x, y - height, x + width, y.toFloat())
        // A line just outside this page's slice can still be asked to draw; don't decode for it.
        if (!RectF(canvas.clipBounds).intersect(target)) return
        images.draw(canvas, entry, target)
    }
}

/**
 * Word font names to the three families every Android device has. Close enough for line breaks
 * to land near where Word put them; the result says fonts were replaced unless the family was
 * one the device draws natively anyway.
 */
internal object DocxFonts {
    private val SERIF = listOf(
        "times", "cambria", "georgia", "garamond", "book antiqua", "palatino", "baskerville", "constantia",
        "century", "bodoni", "didot", "bookman", "minion", "noto serif", "liberation serif", "dejavu serif",
        "serif", "batang", "mincho", "simsun"
    )
    private val MONO = listOf(
        "courier",
        "consolas",
        "mono",
        "menlo",
        "lucida console",
        "inconsolata",
        "fira code"
    )
    private val NATIVE = setOf(
        "arial", "helvetica", "roboto", "sans-serif", "times new roman", "times", "serif", "courier new",
        "courier", "monospace", "noto sans", "noto serif", "droid sans", "droid serif", "droid sans mono"
    )

    fun typeface(font: String?, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family(font), style)
    }

    fun isReplaced(font: String?): Boolean = font != null && font.trim().lowercase() !in NATIVE

    private fun family(font: String?): Typeface {
        val name = font?.lowercase().orEmpty()
        return when {
            MONO.any { name.contains(it) } -> Typeface.MONOSPACE
            SERIF.any { name.contains(it) } && !name.contains("sans") -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
    }
}
