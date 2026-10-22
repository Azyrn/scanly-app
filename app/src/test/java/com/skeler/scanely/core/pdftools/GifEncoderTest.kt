package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.imagetools.GifEncoder
import com.skeler.scanely.core.pdftools.imagetools.LzwEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

/**
 * The GIF writer checked against a small reference decoder written here from the GIF89a spec, so
 * the test doesn't trust the code it tests. The LZW cases are sized to cross every code-width
 * change and several 4096-entry table resets, which is where encoders usually go wrong.
 */
class GifEncoderTest {

    @Test
    fun lzwRoundTripsShortInputs() {
        for (indices in listOf(intArrayOf(0), intArrayOf(3, 3), intArrayOf(1, 2, 1, 2, 1, 2, 1, 2, 1))) {
            assertArrayEquals(indices, lzwRoundTrip(indices, minCodeSize = 2, alphabet = 4))
        }
    }

    @Test
    fun lzwRoundTripsRandomDataAcrossTableResets() {
        val random = Random(42)
        val indices = IntArray(200_000) { random.nextInt(256) }
        assertArrayEquals(indices, lzwRoundTrip(indices, minCodeSize = 8, alphabet = 256))
    }

    @Test
    fun lzwRoundTripsRepetitiveDataThatGrowsLongStrings() {
        val random = Random(7)
        // Long runs and few symbols build long dictionary strings and hit the KwKwK case.
        val indices = IntArray(300_000) { if (random.nextInt(50) == 0) random.nextInt(4) else 0 }
        assertArrayEquals(indices, lzwRoundTrip(indices, minCodeSize = 8, alphabet = 256))
    }

    @Test
    fun lzwEveryCodeWidthBoundary() {
        // Lengths around each power of two exercise the final-code width adjustment.
        for (length in listOf(1, 2, 3, 250, 254, 255, 256, 257, 258, 511, 512, 513, 2047, 2048, 4095, 4096, 4097)) {
            val random = Random(length)
            val indices = IntArray(length) { random.nextInt(256) }
            assertArrayEquals("length $length", indices, lzwRoundTrip(indices, minCodeSize = 8, alphabet = 256))
        }
    }

    @Test
    fun fewColorsComeBackExactly() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val width = 37
        val height = 23
        val pixels = IntArray(width * height) { colors[(it * 7 + it / width) % colors.size] }

        val decoded = decodeGif(encode(width, height, pixels))

        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertArrayEquals(pixels.map { it and RGB }.toIntArray(), decoded.rgb)
        assertEquals(-1, decoded.transparentIndex)
    }

    @Test
    fun manyColorsAreQuantizedClosely() {
        val width = 256
        val height = 64
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or (x shl 16) or ((y * 4) shl 8) or ((x + y) and 0xFF)
        }

        val decoded = decodeGif(encode(width, height, pixels))

        var worst = 0
        for (i in pixels.indices) {
            val a = pixels[i]
            val b = decoded.rgb[i]
            for (shift in intArrayOf(16, 8, 0)) {
                worst = maxOf(worst, abs((a shr shift and 0xFF) - (b shr shift and 0xFF)))
            }
        }
        // 256 entries over a 3-D gradient: every channel within a few bins of the truth.
        assertTrue("worst channel error $worst", worst <= 48)
    }

    @Test
    fun transparentPixelsUseTheTransparentIndex() {
        val width = 10
        val height = 10
        val pixels = IntArray(width * height) { if (it % 3 == 0) 0x00000000 else 0xFF336699.toInt() }

        val decoded = decodeGif(encode(width, height, pixels))

        assertTrue(decoded.transparentIndex >= 0)
        for (i in pixels.indices) {
            val transparent = pixels[i] ushr 24 == 0
            assertEquals("pixel $i", transparent, decoded.indices[i] == decoded.transparentIndex)
            if (!transparent) assertEquals(0x336699, decoded.rgb[i])
        }
    }

    @Test
    fun headerAndTrailerAreGif89a() {
        val bytes = encode(2, 2, IntArray(4) { 0xFF000000.toInt() })
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    // --- helpers ---

    private fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(width, height, out) { y, row -> pixels.copyInto(row, 0, y * width, (y + 1) * width) }
        return out.toByteArray()
    }

    private fun lzwRoundTrip(indices: IntArray, minCodeSize: Int, alphabet: Int): IntArray {
        require(indices.all { it < alphabet })
        val out = ByteArrayOutputStream()
        val encoder = LzwEncoder(out, minCodeSize)
        indices.forEach(encoder::write)
        encoder.finish()
        val bytes = out.toByteArray()
        assertEquals(minCodeSize, bytes[0].toInt())
        val reader = Reader(bytes, 1)
        return lzwDecode(reader, minCodeSize)
    }

    private class Decoded(
        val width: Int,
        val height: Int,
        val indices: IntArray,
        val rgb: IntArray,
        val transparentIndex: Int
    )

    private class Reader(val bytes: ByteArray, var pos: Int) {
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
    }

    /** Reference decoder: header, global table, optional graphic control, one image, LZW. */
    private fun decodeGif(bytes: ByteArray): Decoded {
        val r = Reader(bytes, 0)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        r.pos = 6
        val width = r.u16()
        val height = r.u16()
        val flags = r.u8()
        r.u8()
        r.u8()
        assertTrue("global table expected", flags and 0x80 != 0)
        val tableSize = 2 shl (flags and 0x07)
        val table = IntArray(tableSize) { (r.u8() shl 16) or (r.u8() shl 8) or r.u8() }

        var transparent = -1
        while (true) {
            when (r.u8()) {
                0x21 -> {
                    val label = r.u8()
                    if (label == 0xF9) {
                        assertEquals(4, r.u8())
                        val gceFlags = r.u8()
                        r.u16()
                        val index = r.u8()
                        if (gceFlags and 1 != 0) transparent = index
                        assertEquals(0, r.u8())
                    } else {
                        var size = r.u8()
                        while (size != 0) {
                            r.pos += size
                            size = r.u8()
                        }
                    }
                }
                0x2C -> break
                else -> error("unexpected block")
            }
        }
        assertEquals(0, r.u16())
        assertEquals(0, r.u16())
        assertEquals(width, r.u16())
        assertEquals(height, r.u16())
        assertEquals(0, r.u8() and 0x80)
        val minCodeSize = r.u8()
        val indices = lzwDecode(r, minCodeSize)
        assertEquals(0x3B, r.u8())
        assertEquals(r.pos, bytes.size)
        assertEquals(width * height, indices.size)
        return Decoded(width, height, indices, IntArray(indices.size) { table[indices[it]] }, transparent)
    }

    /** Reads data sub-blocks from [r] (positioned after the min code size) until the terminator. */
    private fun lzwDecode(r: Reader, minCodeSize: Int): IntArray {
        val data = ByteArrayOutputStream()
        var size = r.u8()
        while (size != 0) {
            data.write(r.bytes, r.pos, size)
            r.pos += size
            size = r.u8()
        }
        val stream = data.toByteArray()

        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) {
            suffix[i] = i
            length[i] = 1
            prefix[i] = -1
        }
        var codeSize = minCodeSize + 1
        var next = end + 1
        var previous = -1
        var bitPos = 0
        val out = ArrayList<Int>()
        var sawEnd = false

        fun firstOf(code: Int): Int {
            var c = code
            while (prefix[c] >= 0) c = prefix[c]
            return suffix[c]
        }

        fun emit(code: Int) {
            val start = out.size
            repeat(length[code]) { out.add(0) }
            var c = code
            var i = start + length[code] - 1
            while (c >= 0) {
                out[i--] = suffix[c]
                c = prefix[c]
            }
        }

        while (bitPos + codeSize <= stream.size * 8) {
            var code = 0
            for (b in 0 until codeSize) {
                val bit = (stream[(bitPos + b) / 8].toInt() shr ((bitPos + b) % 8)) and 1
                code = code or (bit shl b)
            }
            bitPos += codeSize
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = end + 1
                previous = -1
                continue
            }
            if (code == end) {
                sawEnd = true
                break
            }
            if (previous < 0) {
                assertTrue("first code after clear must be a literal", code < clear)
                emit(code)
                previous = code
                continue
            }
            val first = if (code < next) firstOf(code) else firstOf(previous)
            assertTrue("code $code beyond next $next", code <= next)
            if (next < 4096) {
                prefix[next] = previous
                suffix[next] = first
                length[next] = length[previous] + 1
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            emit(code)
            previous = code
        }
        assertTrue("end code missing", sawEnd)
        return out.toIntArray()
    }

    private companion object {
        const val RGB = 0xFFFFFF
    }
}
