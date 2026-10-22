package com.skeler.scanely.core.pdfeditor.signature

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Lifts signature ink off a photographed page, leaving a transparent PNG that can be stamped onto
 * any document.
 *
 * Shares the local-mean idea with `DocumentFilters.binarize`, but deliberately does not reuse it:
 * that function returns a hard 1-bit image, and a signature thresholded to 1-bit shows staircased
 * edges the moment it is scaled onto a page. Here the local mean drives an *alpha ramp* instead,
 * so stroke edges stay smooth and thin pen pressure survives.
 */
@Singleton
class SignatureExtractor @Inject constructor() {

    /**
     * [inkColor] null keeps the pen's own colour, sampled from the darkest pixels — a blue pen
     * stays blue. Supplying a colour re-inks the signature instead.
     */
    suspend fun extract(
        source: Bitmap,
        inkColor: Int? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        val scaled = downscale(source)
        val width = scaled.width
        val height = scaled.height
        if (width < MIN_EDGE || height < MIN_EDGE) return@withContext null

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(width * height) { luminanceOf(pixels[it]) }
        val localMean = localMeans(luminance, width, height)

        val alpha = IntArray(width * height)
        for (i in alpha.indices) {
            // Distance below the local paper level, ramped over INK_RAMP so edge pixels get
            // partial alpha rather than snapping to opaque.
            val depth = localMean[i] - INK_BIAS - luminance[i]
            alpha[i] = ((depth.toFloat() / INK_RAMP) * 255f).roundToInt().coerceIn(0, 255)
        }

        despeckle(alpha, width, height)

        val ink = inkColor ?: sampleInkColor(pixels, alpha)
        val bounds = inkBounds(alpha, width, height) ?: return@withContext null

        buildMatte(alpha, pixels, ink, inkColor == null, width, bounds)
    }

    /** Photos arrive at camera resolution; a signature never needs more than this to print well. */
    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val scale = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun luminanceOf(pixel: Int): Int =
        ((pixel shr 16 and 0xFF) * 30 + (pixel shr 8 and 0xFF) * 59 + (pixel and 0xFF) * 11) / 100

    /** O(1) per-pixel box mean via a summed-area table. */
    private fun localMeans(luminance: IntArray, width: Int, height: Int): IntArray {
        val stride = width + 1
        val integral = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            val row = (y + 1) * stride
            val previous = y * stride
            for (x in 0 until width) {
                rowSum += luminance[y * width + x]
                integral[row + x + 1] = integral[previous + x + 1] + rowSum
            }
        }

        val radius = (minOf(width, height) / 12).coerceIn(16, 80)
        val means = IntArray(width * height)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val area = (right - left) * (bottom - top)
                means[y * width + x] = (
                    integral[bottom * stride + right] -
                        integral[top * stride + right] -
                        integral[bottom * stride + left] +
                        integral[top * stride + left]
                    ) / area
            }
        }
        return means
    }

    /**
     * Drops connected blobs smaller than [MIN_BLOB_PIXELS] — paper grain, dust and JPEG noise all
     * threshold as tiny specks that would otherwise stamp onto every document.
     */
    private fun despeckle(alpha: IntArray, width: Int, height: Int) {
        val visited = BooleanArray(alpha.size)
        val stack = ArrayDeque<Int>()
        val blob = ArrayList<Int>()

        for (start in alpha.indices) {
            if (visited[start] || alpha[start] < SPECKLE_ALPHA) continue

            blob.clear()
            stack.addLast(start)
            visited[start] = true

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                blob.add(index)
                val x = index % width
                val y = index / width

                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbour = ny * width + nx
                    if (!visited[neighbour] && alpha[neighbour] >= SPECKLE_ALPHA) {
                        visited[neighbour] = true
                        stack.addLast(neighbour)
                    }
                }
            }

            if (blob.size < MIN_BLOB_PIXELS) blob.forEach { alpha[it] = 0 }
        }
    }

    private fun sampleInkColor(pixels: IntArray, alpha: IntArray): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        for (i in alpha.indices) {
            if (alpha[i] < CORE_INK_ALPHA) continue
            val p = pixels[i]
            r += (p shr 16 and 0xFF)
            g += (p shr 8 and 0xFF)
            b += (p and 0xFF)
            count++
        }
        if (count == 0L) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun inkBounds(alpha: IntArray, width: Int, height: Int): IntArray? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha[y * width + x] < SPECKLE_ALPHA) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < 0) return null

        val padX = ((maxX - minX) * CROP_PAD).roundToInt()
        val padY = ((maxY - minY) * CROP_PAD).roundToInt()
        return intArrayOf(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(width - 1),
            (maxY + padY).coerceAtMost(height - 1)
        )
    }

    private fun buildMatte(
        alpha: IntArray,
        pixels: IntArray,
        ink: Int,
        preserveOriginalColor: Boolean,
        sourceWidth: Int,
        bounds: IntArray
    ): Bitmap {
        val (left, top, right, bottom) = bounds
        val width = right - left + 1
        val height = bottom - top + 1
        val out = IntArray(width * height)

        for (y in 0 until height) for (x in 0 until width) {
            val sourceIndex = (top + y) * sourceWidth + (left + x)
            val a = alpha[sourceIndex]
            out[y * width + x] = if (a == 0) {
                Color.TRANSPARENT
            } else {
                val rgb = if (preserveOriginalColor) pixels[sourceIndex] else ink
                (a shl 24) or (rgb and 0x00FFFFFF)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, width, 0, 0, width, height)
        }
    }

    private companion object {
        const val MAX_EDGE = 1400
        const val MIN_EDGE = 24

        /** How far below the local paper level a pixel must sit before it reads as ink at all. */
        const val INK_BIAS = 12

        /** Luminance span mapped onto 0..255 alpha; wider gives softer stroke edges. */
        const val INK_RAMP = 48
        const val SPECKLE_ALPHA = 40
        const val CORE_INK_ALPHA = 180
        const val MIN_BLOB_PIXELS = 24
        const val CROP_PAD = 0.04f
    }
}
