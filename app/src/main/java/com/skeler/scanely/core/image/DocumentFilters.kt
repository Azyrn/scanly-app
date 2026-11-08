package com.skeler.scanely.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import com.skeler.scanely.R
import com.skeler.scanely.core.scan.ScanMode

enum class ScanFilter(@param:StringRes val labelRes: Int) {
    ORIGINAL(R.string.scan_filter_original),
    AUTO(R.string.scan_filter_auto),
    MAGIC_COLOR(R.string.scan_filter_magic_color),
    WHITEBOARD(R.string.scan_filter_whiteboard),
    BLACK_WHITE(R.string.scan_filter_black_white),
    GRAYSCALE(R.string.scan_filter_grayscale)
}

object DocumentFilters {

    /** [mode] decides what AUTO does (ScanMode.autoEnhance); the other filters are the same for all. */
    fun apply(source: Bitmap, filter: ScanFilter, mode: ScanMode = ScanMode.DOCUMENT): Bitmap = when (filter) {
        ScanFilter.ORIGINAL -> source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        ScanFilter.AUTO -> pixelFilter(source, mode::autoEnhance)
        ScanFilter.MAGIC_COLOR -> applyMatrix(source, magicColorMatrix())
        ScanFilter.GRAYSCALE -> applyMatrix(source, grayscaleMatrix())
        ScanFilter.BLACK_WHITE -> binarize(source)
        ScanFilter.WHITEBOARD -> pixelFilter(source, WhiteboardEnhancer::enhance)
    }

    /** Runs a pure ARGB filter (pixels, width, height) -> pixels on a bitmap. */
    private fun pixelFilter(source: Bitmap, filter: (IntArray, Int, Int) -> IntArray): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val result = createBitmap(width, height)
        result.setPixels(filter(pixels, width, height), 0, width, 0, 0, width, height)
        return result
    }

    private fun applyMatrix(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = createBitmap(source.width, source.height)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun magicColorMatrix(): ColorMatrix {
        val saturation = ColorMatrix().apply { setSaturation(1.35f) }
        val scale = 1.28f
        val translate = (1f - scale) / 2f * 255f + 6f
        val contrast = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        return ColorMatrix().apply {
            postConcat(saturation)
            postConcat(contrast)
        }
    }

    private fun grayscaleMatrix(): ColorMatrix = ColorMatrix().apply { setSaturation(0f) }

    // Local adaptive threshold: a global one turns uneven lighting/edge shadows into
    // black bands; a local mean tracks lighting while preserving text and fine lines.
    private fun binarize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val stride = width + 1
        val integral = luminanceIntegral(pixels, width, height)

        val radius = (minOf(width, height) / 18).coerceIn(24, 96)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val area = (right - left) * (bottom - top)
                val localMean = (
                    integral[bottom * stride + right] -
                        integral[top * stride + right] -
                        integral[bottom * stride + left] +
                        integral[top * stride + left]
                    ) / area
                val luma = luminance(pixels[y * width + x])
                val value = if (luma >= localMean - BLACK_WHITE_BIAS) 0xFF else 0x00
                pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }

        val result = createBitmap(width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private const val BLACK_WHITE_BIAS = 16
}

/**
 * Summed-area table of [pixels]' luminance, (width + 1) wide, for O(1) local-mean lookups. Pages
 * max 2200px/side keeps it under Int.MAX_VALUE.
 */
private fun luminanceIntegral(pixels: IntArray, width: Int, height: Int): IntArray {
    val stride = width + 1
    val integral = IntArray(stride * (height + 1))
    for (y in 0 until height) {
        var rowSum = 0
        val integralRow = (y + 1) * stride
        val previousRow = y * stride
        val pixelRow = y * width
        for (x in 0 until width) {
            rowSum += luminance(pixels[pixelRow + x])
            integral[integralRow + x + 1] = integral[previousRow + x + 1] + rowSum
        }
    }
    return integral
}

/** Integer Rec. 601 luma of an ARGB pixel, 0..255. */
private fun luminance(p: Int): Int = (
    (p shr 16 and 0xFF) * 30 +
        (p shr 8 and 0xFF) * 59 +
        (p and 0xFF) * 11
    ) / 100
