package com.skeler.scanely.core.pdftools.stamp

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "WatermarkJob"

/** What is stamped: a line of text, or a picture the user picked (kept as a content Uri string). */
sealed interface WatermarkMark {
    data class Text(val text: String) : WatermarkMark

    data class Image(val uri: String) : WatermarkMark
}

enum class WatermarkPlacement { CENTER, TOP, BOTTOM, TILED }

/** [degrees] is clockwise on the page; a mark that rises left to right is a negative turn. */
enum class WatermarkRotation(val degrees: Float) { NONE(0f), RISING(-45f), FALLING(45f) }

enum class WatermarkOpacity(val alpha: Int) { LIGHT(64), MEDIUM(128), STRONG(191) }

/** Share of the page's reference length one mark spans, alone and as a tile. */
enum class WatermarkSize(val single: Float, val tile: Float) {
    SMALL(0.35f, 0.18f),
    MEDIUM(0.55f, 0.26f),
    LARGE(0.8f, 0.36f)
}

data class WatermarkOptions(
    val mark: WatermarkMark,
    val placement: WatermarkPlacement = WatermarkPlacement.CENTER,
    val rotation: WatermarkRotation = WatermarkRotation.RISING,
    val opacity: WatermarkOpacity = WatermarkOpacity.MEDIUM,
    val size: WatermarkSize = WatermarkSize.MEDIUM,
    val layer: StampLayer = StampLayer.OVER
)

/**
 * Stamps text or a picture on every page. Pages of one size share a single stamp page — qpdf
 * repeats it — so the stamp PDF stays a few kilobytes (or one embedded image) whatever the page
 * count, and a document of mixed sizes gets one stamp page per distinct size.
 *
 * The picture is read from the picked Uri here rather than copied in by the flow: the options
 * screen has no run directory, and the read grant outlives the screen for as long as the run does.
 */
@Singleton
class WatermarkJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stamper: StampRunner
) {

    suspend fun watermark(input: ToolJobInput, options: WatermarkOptions): ToolResult {
        val document = input.documents.first()
        val mark = options.mark
        if (mark is WatermarkMark.Text) require(mark.text.isNotBlank()) { "empty watermark text" }

        val bitmap = (mark as? WatermarkMark.Image)?.let { loadImage(it.uri.toUri()) }
        try {
            return stamper.stamp(document, input, SUFFIX_WATERMARKED, "watermark", options.layer) { geometries ->
                val sizes = LinkedHashMap<StampSize, Int>()
                val targets = geometries.mapIndexed { index, geometry ->
                    val size = StampSize.of(geometry)
                    index + 1 to sizes.getOrPut(size) { sizes.size + 1 }
                }
                val painter = MarkPainter(options, bitmap)
                StampPlan(
                    sizes = sizes.keys.toList(),
                    targets = targets,
                    draw = { canvas, _, size -> painter.paint(canvas, size) }
                )
            }
        } finally {
            // Only after the stamp is written: PdfDocument reads the pixels at writeTo.
            bitmap?.recycle()
        }
    }

    /**
     * Downsampled to [MAX_IMAGE_EDGE] at decode time, so a 50-megapixel photo never sits in memory
     * whole, and turned upright from its EXIF orientation, which BitmapFactory ignores.
     */
    private suspend fun loadImage(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            upright(decodeSampled(resolver, uri), exifOrientation(resolver, uri))
        } catch (e: IOException) {
            Log.w(TAG, "Watermark image could not be read", e)
            throw ToolException(ToolFailure.Unreadable, e)
        } catch (e: SecurityException) {
            // The picker's grant can lapse if the app was killed and restored mid-flow.
            Log.w(TAG, "Watermark image grant lapsed", e)
            throw ToolException(ToolFailure.Unreadable, e)
        }
    }

    /** Decodes [uri] at the largest power-of-two sample that keeps it within [MAX_IMAGE_EDGE]. */
    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ToolException(ToolFailure.Unreadable)

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw ToolException(ToolFailure.Unreadable)
    }

    private fun exifOrientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER_TURN)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(HALF_TURN)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(THREE_QUARTER_TURN)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}

/**
 * Lays one mark out on a page. Every placement works the same way: size the mark from the page,
 * shrink it until its rotated bounding box fits, then put that box's centre where the placement
 * says — so a diagonal mark at the top never pokes off the page edge.
 */
private class MarkPainter(private val options: WatermarkOptions, private val bitmap: Bitmap?) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = REFERENCE_TEXT_SIZE
        color = TEXT_GREY
        alpha = options.opacity.alpha
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        alpha = options.opacity.alpha
    }
    private val text = (options.mark as? WatermarkMark.Text)?.text?.trim().orEmpty()

    /** The mark's own width and height at scale 1. */
    private val natural: Pair<Float, Float> = if (bitmap != null) {
        bitmap.width.toFloat() to bitmap.height.toFloat()
    } else {
        val metrics = textPaint.fontMetrics
        textPaint.measureText(text).coerceAtLeast(1f) to (metrics.descent - metrics.ascent)
    }

    private val radians = Math.toRadians(options.rotation.degrees.toDouble())
    private val cosine = abs(cos(radians)).toFloat()
    private val sine = abs(sin(radians)).toFloat()

    fun paint(canvas: Canvas, size: StampSize) {
        val width = size.widthPt.toFloat()
        val height = size.heightPt.toFloat()
        val tiled = options.placement == WatermarkPlacement.TILED
        // Level text measures against the width; a diagonal against the shorter side, which is
        // what it has to cross.
        val reference = if (options.rotation == WatermarkRotation.NONE) width else min(width, height)
        val share = if (tiled) options.size.tile else options.size.single
        var scale = share * reference / natural.first
        val (boxW, boxH) = box(scale)
        val fit = min(width * FIT_SHARE / boxW, height * FIT_SHARE / boxH)
        if (fit < 1f) scale *= fit
        val (w, h) = box(scale)

        val edge = min(width, height) * EDGE_SHARE
        when (options.placement) {
            WatermarkPlacement.CENTER -> drawMark(canvas, width / 2f, height / 2f, scale)
            WatermarkPlacement.TOP -> drawMark(canvas, width / 2f, edge + h / 2f, scale)
            WatermarkPlacement.BOTTOM -> drawMark(canvas, width / 2f, height - edge - h / 2f, scale)
            WatermarkPlacement.TILED -> tile(canvas, width, height, w, h, scale)
        }
    }

    /** Rows offset by half a cell, like brickwork, so diagonal tiles don't line up into stripes. */
    private fun tile(canvas: Canvas, width: Float, height: Float, w: Float, h: Float, scale: Float) {
        val gap = max(w, h) * TILE_GAP_SHARE
        val cellW = w + gap
        val cellH = h + gap
        val columns = ceil(width / cellW).toInt() + 2
        val rows = ceil(height / cellH).toInt() + 2
        val left = width / 2f - columns / 2 * cellW
        val top = height / 2f - rows / 2 * cellH
        for (row in 0..rows) {
            val shift = if (row % 2 == 0) 0f else cellW / 2f
            for (column in 0..columns) {
                drawMark(canvas, left + column * cellW + shift, top + row * cellH, scale)
            }
        }
    }

    private fun box(scale: Float): Pair<Float, Float> {
        val w = natural.first * scale
        val h = natural.second * scale
        return (w * cosine + h * sine) to (w * sine + h * cosine)
    }

    private fun drawMark(canvas: Canvas, centerX: Float, centerY: Float, scale: Float) {
        canvas.withTranslation(centerX, centerY) {
            canvas.rotate(options.rotation.degrees)
            val w = natural.first * scale
            val h = natural.second * scale
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), bitmapPaint)
            } else {
                // Sized through textSize rather than a canvas scale, so the PDF carries a real font
                // size and viewers hint the glyphs normally.
                textPaint.textSize = REFERENCE_TEXT_SIZE * scale
                val metrics = textPaint.fontMetrics
                canvas.drawText(text, -w / 2f, -h / 2f - metrics.ascent, textPaint)
            }
        }
    }
}

private const val SUFFIX_WATERMARKED = "_watermarked"

private const val MAX_IMAGE_EDGE = 2048
private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180f
private const val THREE_QUARTER_TURN = 270f

/** Text is measured at this size and scaled from it. */
private const val REFERENCE_TEXT_SIZE = 100f

/** Mid grey reads on both white paper and dark scans. */
private const val TEXT_GREY = 0xFF808080.toInt()

/** A single mark's rotated box may use at most this share of each page side. */
private const val FIT_SHARE = 0.9f

/** Distance of a top or bottom mark from the edge, as a share of the shorter side. */
private const val EDGE_SHARE = 0.06f

/** Space between tiles, as a share of a tile's longer side. */
private const val TILE_GAP_SHARE = 0.5f
