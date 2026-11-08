package com.skeler.scanely.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a photo of a whiteboard into a clean page: the board becomes white, marker strokes keep
 * their colour and get stronger.
 *
 * Works on ARGB pixels so it runs (and is tested) without Android. Three steps:
 * 1. **Board estimate** — per channel, the brightest value in coarse blocks (marker strokes are thin,
 *    so a block's maximum is the board, not the ink). Blocks far darker than the board near them or
 *    than the photo's board level (filled strokes, a desk or bezel left in the photo) take the
 *    board of their neighbours; then bilinear back to full size. This follows shadows, glare and
 *    tinted light.
 * 2. **Flat-field** — each channel divided by the board estimate, so the board reads 255 everywhere
 *    and the lighting drops out.
 * 3. **Ink curve** — darkness below a noise floor (smudges, ghosting of erased ink) snaps to white;
 *    the rest is stretched, and stroke colour saturation is raised so red/blue/green stay distinct.
 */
object WhiteboardEnhancer {

    /** Longest side of the board estimate grid: ~15 px blocks on a 2200 px page, fine enough to follow a shadow edge; strokes that cover whole blocks are handled by [fillInkBlocks]. */
    private const val GRID_LONG_SIDE = 150

    /** Darkness (0..255) below which a pixel is treated as board: faint smudges and erased ink. */
    private const val NOISE_FLOOR = 28f

    /**
     * How much the remaining darkness is stretched, so thin strokes stay legible. 1.55 left strokes greyer
     * than GMS (contrast 45.7 vs 46.2 on the whiteboard fixture); 1.8 is the lowest that beats it with
     * marker hue and ink kept (ScanQualityTest).
     */
    private const val INK_GAIN = 1.8f

    /** Chroma boost for coloured strokes. */
    private const val SATURATION_GAIN = 1.45f

    fun enhance(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) { "pixels must be width * height" }
        val board = boardEstimate(pixels, width, height)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val b = i * 3
            val r = flatField(p shr 16 and 0xFF, board[b])
            val g = flatField(p shr 8 and 0xFF, board[b + 1])
            val bl = flatField(p and 0xFF, board[b + 2])
            out[i] = inkCurve(r, g, bl)
        }
        return out
    }

    private fun flatField(value: Int, board: Float): Float = min(255f, value * 255f / max(board, 1f))

    private fun inkCurve(r: Float, g: Float, b: Float): Int {
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val darkness = 255f - luma
        if (darkness <= NOISE_FLOOR) return WHITE
        val newDarkness = min(255f, (darkness - NOISE_FLOOR) * INK_GAIN)
        val newLuma = 255f - newDarkness
        // Keep the stroke's hue: scale its chroma (offset from grey) and re-centre on the new luma.
        val scale = SATURATION_GAIN
        val nr = channel(newLuma + (r - luma) * scale)
        val ng = channel(newLuma + (g - luma) * scale)
        val nb = channel(newLuma + (b - luma) * scale)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun channel(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Per-pixel board colour, interleaved R,G,B floats, from blocks [gridLongSide] to the long side. */
    internal fun boardEstimate(
        pixels: IntArray,
        width: Int,
        height: Int,
        gridLongSide: Int = GRID_LONG_SIDE
    ): FloatArray {
        val block = max(1, (max(width, height) + gridLongSide - 1) / gridLongSide)
        val gw = (width + block - 1) / block
        val gh = (height + block - 1) / block
        // 1. block maxima per channel
        val grid = blockMaxima(pixels, width, height, block, gw, gh)
        // 2. blocks fully covered by ink (thick strokes, filled shapes) are far darker than the board
        //    around them: drop them and fill them in from the board blocks next to them
        val smooth = fillInkBlocks(grid, gw, gh)
        // 3. bilinear back to full size, sampling at block centres
        val out = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val fy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, gh - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, gw - 1)
                val tx = fx - x0
                val o = (y * width + x) * 3
                for (c in 0 until 3) {
                    val a = smooth[(y0 * gw + x0) * 3 + c]
                    val b = smooth[(y0 * gw + x1) * 3 + c]
                    val d = smooth[(y1 * gw + x0) * 3 + c]
                    val e = smooth[(y1 * gw + x1) * 3 + c]
                    val top = a + (b - a) * tx
                    val bottom = d + (e - d) * tx
                    out[o + c] = top + (bottom - top) * ty
                }
            }
        }
        return out
    }

    private fun blockMaxima(pixels: IntArray, width: Int, height: Int, block: Int, gw: Int, gh: Int): FloatArray {
        val grid = FloatArray(gw * gh * 3)
        for (y in 0 until height) {
            val gy = y / block
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val gi = (gy * gw + x / block) * 3
                grid[gi] = max(grid[gi], (p shr 16 and 0xFF).toFloat())
                grid[gi + 1] = max(grid[gi + 1], (p shr 8 and 0xFF).toFloat())
                grid[gi + 2] = max(grid[gi + 2], (p and 0xFF).toFloat())
            }
        }
        return grid
    }

    /**
     * A block is ink-only when its brightest pixel is under [INK_BLOCK_RATIO] of the brightest block
     * within [INK_BLOCK_RADIUS] — darker than any shadow a board realistically gets, lighter than no
     * marker (black ~0.15, blue ~0.27, red ~0.33 of the board; shadows rarely under 0.45). Ink-only blocks take the mean of their board neighbours, growing inwards until filled.
     */
    private fun fillInkBlocks(grid: FloatArray, gw: Int, gh: Int): FloatArray {
        val n = gw * gh
        val luma = FloatArray(n) { 0.299f * grid[it * 3] + 0.587f * grid[it * 3 + 1] + 0.114f * grid[it * 3 + 2] }
        // judged against the brightest board nearby AND the photo's typical board level: a dark region
        // wider than the radius (desk, keyboard, a screen's bezel in an uncropped photo) is no board
        // either, and dividing by it would blow it to white
        val board = luma.sortedArray()[((n - 1) * BOARD_PERCENTILE).toInt()]
        val ref = maxFilter(luma, gw, gh, INK_BLOCK_RADIUS).also { r -> for (i in r.indices) r[i] = max(r[i], board) }
        val valid = BooleanArray(n) { luma[it] >= INK_BLOCK_RATIO * ref[it] }
        val out = grid.copyOf()
        var pending = valid.count { !it }
        while (pending > 0) {
            val next = valid.copyOf()
            var filled = 0
            for (i in 0 until n) {
                if (!valid[i] && fillFromNeighbours(out, valid, gw, gh, i)) {
                    next[i] = true
                    filled++
                }
            }
            if (filled == 0) break // no board anywhere (all-ink photo): keep the block maxima
            next.copyInto(valid)
            pending -= filled
        }
        return out
    }

    /** Sets block [i] to the mean of its valid 8-neighbours; false when it has none yet. */
    private fun fillFromNeighbours(out: FloatArray, valid: BooleanArray, gw: Int, gh: Int, i: Int): Boolean {
        val y = i / gw
        val x = i % gw
        var r = 0f
        var g = 0f
        var b = 0f
        var k = 0
        for (ny in max(0, y - 1)..min(gh - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(gw - 1, x + 1)) {
                val j = ny * gw + nx
                if (!valid[j]) continue
                r += out[j * 3]
                g += out[j * 3 + 1]
                b += out[j * 3 + 2]
                k++
            }
        }
        if (k == 0) return false
        out[i * 3] = r / k
        out[i * 3 + 1] = g / k
        out[i * 3 + 2] = b / k
        return true
    }

    /** Separable square max filter of radius [r] (rows, then columns). */
    private fun maxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val rows = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (nx in max(0, x - r)..min(w - 1, x + r)) m = max(m, src[y * w + nx])
            rows[y * w + x] = m
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (ny in max(0, y - r)..min(h - 1, y + r)) m = max(m, rows[ny * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** ~300 px on a 2200 px page: wider than any filled marker shape, so its inside finds board. */
    private const val INK_BLOCK_RADIUS = 20
    private const val INK_BLOCK_RATIO = 0.4f

    /** The board fills at least the brighter part of a whiteboard photo; 0.9 skips glare blocks. */
    private const val BOARD_PERCENTILE = 0.9f

    private const val WHITE = -0x1 // 0xFFFFFFFF
}
