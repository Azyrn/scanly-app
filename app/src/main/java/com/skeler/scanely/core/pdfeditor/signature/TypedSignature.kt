package com.skeler.scanely.core.pdfeditor.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.graphics.createBitmap

private const val TAG = "TypedSignature"

/**
 * A signature made by typing a name in a handwriting face, for people who have no pen-and-paper
 * signature to photograph or would rather not hand one over.
 *
 * The result is a transparent ink bitmap exactly like the one [SignatureExtractor] produces, so it
 * goes into [SignatureStore] and is placed and signed by the same code as a captured signature.
 */
object TypedSignature {

    /** Great Vibes, SIL Open Font License 1.1; the licence ships next to it and is shown in Settings. */
    const val FONT_ASSET = "fonts/GreatVibes-Regular.ttf"
    const val LICENSE_ASSET = "fonts/LICENSE-GreatVibes.txt"

    const val MAX_LENGTH = 60

    fun loadTypeface(context: Context): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, FONT_ASSET) }
            .onFailure { Log.w(TAG, "Signature font failed to load", it) }
            .getOrNull()

    /**
     * Renders [name] so the ink spans about [targetWidthPx], then trims to the inked pixels.
     *
     * Script faces swash well past their advance width and ascent, so measured text bounds clip the
     * flourishes; drawing onto a generous canvas and trimming by alpha keeps every stroke. The text
     * size is clamped so a single initial does not become a huge bitmap and a long name stays legible.
     */
    fun render(
        name: String,
        typeface: Typeface,
        color: Int,
        targetWidthPx: Int = DEFAULT_WIDTH_PX
    ): Bitmap? {
        val text = name.trim().take(MAX_LENGTH)
        if (text.isEmpty()) return null

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            this.color = color
            textSize = PROBE_TEXT_SIZE
        }
        val probeWidth = paint.measureText(text).coerceAtLeast(1f)
        paint.textSize = (PROBE_TEXT_SIZE * targetWidthPx / probeWidth).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

        val margin = paint.textSize
        val canvasWidth = (paint.measureText(text) + margin * 2).toInt().coerceAtLeast(1)
        val canvasHeight = (paint.textSize * HEIGHT_FACTOR).toInt().coerceAtLeast(1)
        val canvasBitmap = runCatching {
            createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            Log.w(TAG, "No memory for a ${canvasWidth}x$canvasHeight signature canvas")
            return null
        }
        Canvas(canvasBitmap).drawText(text, margin, paint.textSize * BASELINE_FACTOR, paint)

        val trimmed = trimToInk(canvasBitmap)
        if (trimmed !== canvasBitmap) canvasBitmap.recycle()
        return trimmed
    }

    /** Crops to the non-transparent pixels plus a hairline of padding; null when nothing was drawn. */
    private fun trimToInk(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        var top = -1
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var inked = false
            for (x in 0 until width) {
                if ((row[x] ushr ALPHA_SHIFT) > ALPHA_THRESHOLD) {
                    inked = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (inked) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null

        val x0 = (left - TRIM_PADDING_PX).coerceAtLeast(0)
        val y0 = (top - TRIM_PADDING_PX).coerceAtLeast(0)
        val x1 = (right + TRIM_PADDING_PX).coerceAtMost(width - 1)
        val y1 = (bottom + TRIM_PADDING_PX).coerceAtMost(height - 1)
        return Bitmap.createBitmap(bitmap, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}

private const val DEFAULT_WIDTH_PX = 1200
private const val PROBE_TEXT_SIZE = 200f
private const val MIN_TEXT_SIZE = 120f
private const val MAX_TEXT_SIZE = 420f

// Great Vibes' swashes reach roughly one em above the baseline and half an em below.
private const val HEIGHT_FACTOR = 2.2f
private const val BASELINE_FACTOR = 1.4f
private const val ALPHA_SHIFT = 24
private const val ALPHA_THRESHOLD = 8
private const val TRIM_PADDING_PX = 4
