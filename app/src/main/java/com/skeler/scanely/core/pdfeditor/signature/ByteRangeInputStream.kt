package com.skeler.scanely.core.pdfeditor.signature

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** One (offset, length) pair from a signature's /ByteRange. */
internal data class ByteSpan(val start: Long, val length: Long) {
    val end: Long get() = start + length
}

/**
 * The [spans] of [file], read back to back as one stream — the bytes a PDF signature digests.
 *
 * Reads straight from disk, so verifying a signature costs a buffer rather than the whole file.
 * The spans are checked against the file's length before this is opened; running out of file part
 * way (it shrank underneath us) is an [EOFException], never a short digest that looks like
 * tampering.
 */
internal class ByteRangeInputStream(file: File, private val spans: List<ByteSpan>) : InputStream() {

    private val raf = RandomAccessFile(file, "r")
    private var index = -1
    private var remaining = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (remaining == 0L) {
            if (++index >= spans.size) return -1
            raf.seek(spans[index].start)
            remaining = spans[index].length
        }
        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("Signed byte range runs past the end of the file")
        remaining -= n
        return n
    }

    override fun close() = raf.close()

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
