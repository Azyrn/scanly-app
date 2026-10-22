package com.skeler.scanely.core.pdftools.imagetools

import androidx.core.graphics.scale
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SUFFIX_RESIZED = "_resized"
private const val QUALITY = 90
private const val PERCENT = 100.0

/** How the user asked for the new size. */
sealed interface ResizeMode {
    data class Percent(val percent: Int) : ResizeMode

    /**
     * Either bound may be null ("any height"). With [keepAspect] the image fits inside the box;
     * without it, each given bound is applied exactly and a missing one keeps the original side.
     */
    data class Fit(val maxWidth: Int?, val maxHeight: Int?, val keepAspect: Boolean, val noEnlarge: Boolean) :
        ResizeMode
}

/**
 * Scales each image, keeping its format where Android can write it (BMP becomes PNG, HEIC becomes
 * JPEG, a GIF keeps only its first frame). Decoding is sampled down to just above the target
 * first, so shrinking a 50 MP photo never holds 50 MP, and the last step is a filtered scale for a
 * clean result.
 */
@Singleton
class ResizeImageJob @Inject constructor(private val batch: ImageBatch) {

    suspend fun resize(input: ToolJobInput, mode: ResizeMode): ToolResult =
        batch.run(input, label = "resize image", showSizeChange = true) { file ->
            val format = outputFormat(sniff(file))
            val decoded = decode(file) { full ->
                val size = targetSize(full, mode)
                ImageCodec.sampleToFit(full, size.width, size.height)
            }
            val size = targetSize(decoded.fullSize, mode)
            var bitmap = decoded.bitmap
            if (bitmap.width != size.width || bitmap.height != size.height) {
                val scaled = bitmap.scale(size.width, size.height, filter = true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            if (format == EncodeFormat.JPEG) bitmap = flattenOnWhite(bitmap)
            ImageOutcome(write(bitmap, file, input.run, SUFFIX_RESIZED, format, QUALITY))
        }

    /** The format a resized image is written in: its own where Android can write it. */
    private fun outputFormat(source: ImageFormat): EncodeFormat = when (source) {
        ImageFormat.PNG, ImageFormat.BMP -> EncodeFormat.PNG
        ImageFormat.WEBP -> EncodeFormat.WEBP_LOSSY
        ImageFormat.GIF -> EncodeFormat.GIF
        ImageFormat.JPEG, ImageFormat.HEIF, ImageFormat.OTHER -> EncodeFormat.JPEG
    }

    companion object {
        /** The new upright size for an image of upright size [full]; never below 1 px a side. */
        fun targetSize(full: ImageSize, mode: ResizeMode): ImageSize {
            val (width, height) = when (mode) {
                is ResizeMode.Percent -> {
                    val scale = mode.percent / PERCENT
                    full.width * scale to full.height * scale
                }
                is ResizeMode.Fit -> fit(full, mode)
            }
            var w = max(1, width.roundToInt())
            var h = max(1, height.roundToInt())
            // Enlarging is capped by the same pixel budget decoding is, scaled down evenly.
            val pixels = w.toLong() * h
            if (pixels > ImageCodec.MAX_DECODE_PIXELS) {
                val shrink = sqrt(ImageCodec.MAX_DECODE_PIXELS.toDouble() / pixels)
                w = max(1, (w * shrink).toInt())
                h = max(1, (h * shrink).toInt())
            }
            return ImageSize(w, h)
        }

        private fun fit(full: ImageSize, mode: ResizeMode.Fit): Pair<Double, Double> {
            val w = full.width.toDouble()
            val h = full.height.toDouble()
            return if (mode.keepAspect) fitKeepingAspect(w, h, mode) else fitStretching(w, h, mode)
        }

        private fun fitKeepingAspect(w: Double, h: Double, mode: ResizeMode.Fit): Pair<Double, Double> {
            var scale = min(
                mode.maxWidth?.let { it / w } ?: Double.MAX_VALUE,
                mode.maxHeight?.let { it / h } ?: Double.MAX_VALUE
            )
            if (scale == Double.MAX_VALUE) scale = 1.0
            if (mode.noEnlarge) scale = min(scale, 1.0)
            return w * scale to h * scale
        }

        private fun fitStretching(w: Double, h: Double, mode: ResizeMode.Fit): Pair<Double, Double> {
            var tw = mode.maxWidth?.toDouble() ?: w
            var th = mode.maxHeight?.toDouble() ?: h
            if (mode.noEnlarge) {
                tw = min(tw, w)
                th = min(th, h)
            }
            return tw to th
        }
    }
}
