package com.skeler.scanely.core.pdftools.imagetools

import java.io.OutputStream

/**
 * A single-frame GIF89a writer. Android can decode GIFs but has no encoder, and one still image
 * doesn't justify a library.
 *
 * Pixels are read twice through [RowSource] — once to build a colour histogram, once to write
 * indices — so the encoder never holds the image, only one row and a 32K-bin histogram. The
 * palette comes from median cut over 5-bit-per-channel bins, with each entry the true average of
 * the colours that fell in its box, so an image with at most 255 distinct bins comes back exact.
 * Pixels under half alpha become the one transparent entry GIF allows.
 */
object GifEncoder {

    /** Fills [row] (length = width) with the ARGB pixels of row [y]; called in order, twice per row. */
    fun interface RowSource {
        fun read(y: Int, row: IntArray)
    }

    fun encode(width: Int, height: Int, out: OutputStream, rows: RowSource) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "GIF size out of range: $width x $height" }
        val row = IntArray(width)

        val histogram = Histogram()
        for (y in 0 until height) {
            rows.read(y, row)
            for (pixel in row) histogram.add(pixel)
        }
        val palette = histogram.buildPalette()

        writeHeader(out, width, height, palette)
        val lzw = LzwEncoder(out, MIN_CODE_SIZE)
        for (y in 0 until height) {
            rows.read(y, row)
            for (pixel in row) lzw.write(palette.indexOf(pixel))
        }
        lzw.finish()
        out.write(TRAILER)
    }

    private fun writeHeader(out: OutputStream, width: Int, height: Int, palette: Palette) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor: global colour table present, 8 bits per channel, 256 entries.
        writeShort(out, width)
        writeShort(out, height)
        out.write(GLOBAL_TABLE_FLAGS)
        out.write(0) // background colour index
        out.write(0) // no pixel aspect ratio
        for (color in palette.colors) {
            out.write(color shr RED_SHIFT and BYTE_MASK)
            out.write(color shr GREEN_SHIFT and BYTE_MASK)
            out.write(color and BYTE_MASK)
        }
        if (palette.transparentIndex >= 0) {
            // Graphic control extension, only needed to name the transparent index.
            out.write(EXTENSION_INTRODUCER)
            out.write(GRAPHIC_CONTROL_LABEL)
            out.write(GRAPHIC_CONTROL_SIZE)
            out.write(TRANSPARENT_FLAG)
            writeShort(out, 0) // delay
            out.write(palette.transparentIndex)
            out.write(0) // block terminator
        }
        // Image descriptor: whole canvas, no local table, not interlaced.
        out.write(IMAGE_SEPARATOR)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        out.write(0)
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and BYTE_MASK)
        out.write(value shr BYTE_BITS and BYTE_MASK)
    }

    /** 256 colours as 0xRRGGBB, plus a lookup from 15-bit bin to palette index. */
    private class Palette(val colors: IntArray, private val binToIndex: IntArray, val transparentIndex: Int) {
        fun indexOf(argb: Int): Int =
            if (transparentIndex >= 0 && argb ushr ALPHA_SHIFT < ALPHA_OPAQUE_THRESHOLD) {
                transparentIndex
            } else {
                binToIndex[binOf(argb)]
            }
    }

    private class Histogram {
        val counts = LongArray(BIN_COUNT)
        val sumR = LongArray(BIN_COUNT)
        val sumG = LongArray(BIN_COUNT)
        val sumB = LongArray(BIN_COUNT)
        var hasTransparency = false

        fun add(argb: Int) {
            if (argb ushr ALPHA_SHIFT < ALPHA_OPAQUE_THRESHOLD) {
                hasTransparency = true
                return
            }
            val bin = binOf(argb)
            counts[bin]++
            sumR[bin] += (argb shr RED_SHIFT and BYTE_MASK).toLong()
            sumG[bin] += (argb shr GREEN_SHIFT and BYTE_MASK).toLong()
            sumB[bin] += (argb and BYTE_MASK).toLong()
        }

        fun buildPalette(): Palette {
            val maxColors = if (hasTransparency) PALETTE_SIZE - 1 else PALETTE_SIZE
            val used = (0 until BIN_COUNT).filter { counts[it] > 0 }.toIntArray()
            val boxes = ArrayList<Box>()
            if (used.isNotEmpty()) boxes += Box(used)
            // Split the box with the widest channel range until there are enough boxes or none can split.
            while (boxes.size < maxColors) {
                val widest = boxes.filter { it.bins.size > 1 }.maxByOrNull { it.widestRange() } ?: break
                boxes.remove(widest)
                boxes += widest.split()
            }

            val colors = IntArray(PALETTE_SIZE)
            val binToIndex = IntArray(BIN_COUNT)
            boxes.forEachIndexed { index, box ->
                var n = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (bin in box.bins) {
                    n += counts[bin]
                    r += sumR[bin]
                    g += sumG[bin]
                    b += sumB[bin]
                    binToIndex[bin] = index
                }
                colors[index] = ((r / n).toInt() shl RED_SHIFT) or ((g / n).toInt() shl GREEN_SHIFT) or (b / n).toInt()
            }
            val transparent = if (hasTransparency) boxes.size else -1
            return Palette(colors, binToIndex, transparent)
        }

        private inner class Box(val bins: IntArray) {
            fun range(channel: Int): Int {
                var lo = Int.MAX_VALUE
                var hi = Int.MIN_VALUE
                for (bin in bins) {
                    val v = channelOf(bin, channel)
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                return hi - lo
            }

            fun widestRange(): Int = (0 until CHANNELS).maxOf { range(it) }

            /** Splits at the pixel-weighted median of the widest channel, so busy colours get more entries. */
            fun split(): List<Box> {
                val channel = (0 until CHANNELS).maxBy { range(it) }
                val sorted = bins.sortedBy { channelOf(it, channel) }
                val total = sorted.sumOf { counts[it] }
                var running = 0L
                var cut = 1
                for (i in 0 until sorted.size - 1) {
                    running += counts[sorted[i]]
                    cut = i + 1
                    if (running * 2 >= total) break
                }
                return listOf(
                    Box(sorted.subList(0, cut).toIntArray()),
                    Box(sorted.subList(cut, sorted.size).toIntArray())
                )
            }
        }
    }

    private fun binOf(argb: Int): Int =
        ((argb shr (RED_SHIFT + BIN_DROP) and BIN_MASK) shl (2 * BIN_BITS)) or
            ((argb shr (GREEN_SHIFT + BIN_DROP) and BIN_MASK) shl BIN_BITS) or
            (argb shr BIN_DROP and BIN_MASK)

    private fun channelOf(bin: Int, channel: Int): Int = bin shr ((CHANNELS - 1 - channel) * BIN_BITS) and BIN_MASK

    private const val MAX_DIMENSION = 65_535
    private const val MIN_CODE_SIZE = 8
    private const val PALETTE_SIZE = 256
    private const val CHANNELS = 3
    private const val BIN_BITS = 5
    private const val BIN_DROP = 8 - BIN_BITS
    private const val BIN_MASK = (1 shl BIN_BITS) - 1
    private const val BIN_COUNT = 1 shl (BIN_BITS * CHANNELS)
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val ALPHA_OPAQUE_THRESHOLD = 128

    /** Global table flag, colour resolution 7, table size 2^(7+1) = 256. */
    private const val GLOBAL_TABLE_FLAGS = 0xF7
    private const val EXTENSION_INTRODUCER = 0x21
    private const val GRAPHIC_CONTROL_LABEL = 0xF9
    private const val GRAPHIC_CONTROL_SIZE = 4
    private const val TRANSPARENT_FLAG = 0x01
    private const val IMAGE_SEPARATOR = 0x2C
    private const val TRAILER = 0x3B
}

/**
 * GIF's variable-width LZW: codes are written least-significant bit first and packed into data
 * sub-blocks of at most 255 bytes. The dictionary is an open-addressed hash of (prefix code, next
 * index) pairs, so a full image never boxes a key.
 *
 * Width changes follow the decoder, which adds each dictionary entry one code later than the
 * encoder does: the encoder widens once its next free code passes the current width, and when
 * the 4096-entry table fills it sends a clear code rather than freezing the table.
 */
internal class LzwEncoder(private val out: OutputStream, private val minCodeSize: Int) {
    private val clearCode = 1 shl minCodeSize
    private val endCode = clearCode + 1
    private val keys = IntArray(HASH_SIZE)
    private val values = IntArray(HASH_SIZE)
    private var nextCode = 0
    private var codeSize = 0
    private var prefix = -1

    private var bitBuffer = 0L
    private var bitCount = 0
    private val block = ByteArray(MAX_BLOCK)
    private var blockLength = 0

    init {
        require(minCodeSize in 2..MAX_MIN_CODE_SIZE) { "LZW minimum code size must be 2..8" }
        out.write(minCodeSize)
        resetTable()
        emit(clearCode)
    }

    fun write(index: Int) {
        val current = prefix
        if (current < 0) {
            prefix = index
            return
        }
        val key = (current shl KEY_SHIFT) or index
        var slot = hash(key)
        while (true) {
            val stored = keys[slot]
            if (stored == key) {
                prefix = values[slot]
                return
            }
            if (stored == EMPTY) break
            slot = (slot + 1) and (HASH_SIZE - 1)
        }
        emit(current)
        if (nextCode < MAX_CODES) {
            keys[slot] = key
            values[slot] = nextCode
            nextCode++
            if (nextCode > (1 shl codeSize) && codeSize < MAX_CODE_BITS) codeSize++
        } else {
            emit(clearCode)
            resetTable()
        }
        prefix = index
    }

    fun finish() {
        if (prefix >= 0) {
            emit(prefix)
            // The decoder adds an entry on reading that last code and may widen before the end code.
            if (nextCode < MAX_CODES && nextCode + 1 > (1 shl codeSize) && codeSize < MAX_CODE_BITS) codeSize++
        }
        emit(endCode)
        if (bitCount > 0) pushByte((bitBuffer and BYTE_MASK_L).toInt())
        flushBlock()
        out.write(0) // block terminator
    }

    private fun resetTable() {
        keys.fill(EMPTY)
        nextCode = endCode + 1
        codeSize = minCodeSize + 1
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code.toLong() shl bitCount)
        bitCount += codeSize
        while (bitCount >= BYTE_BITS) {
            pushByte((bitBuffer and BYTE_MASK_L).toInt())
            bitBuffer = bitBuffer ushr BYTE_BITS
            bitCount -= BYTE_BITS
        }
    }

    private fun pushByte(value: Int) {
        block[blockLength++] = value.toByte()
        if (blockLength == MAX_BLOCK) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength == 0) return
        out.write(blockLength)
        out.write(block, 0, blockLength)
        blockLength = 0
    }

    private fun hash(key: Int): Int = (key * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)

    private companion object {
        const val MAX_MIN_CODE_SIZE = 8
        const val MAX_CODE_BITS = 12
        const val MAX_CODES = 1 shl MAX_CODE_BITS
        const val KEY_SHIFT = 8
        const val HASH_BITS = 13
        const val HASH_SIZE = 1 shl HASH_BITS
        const val HASH_MULTIPLIER = -0x61c88647 // 2^32 / golden ratio
        const val EMPTY = -1
        const val MAX_BLOCK = 255
        const val BYTE_BITS = 8
        const val BYTE_MASK_L = 0xFFL
    }
}
