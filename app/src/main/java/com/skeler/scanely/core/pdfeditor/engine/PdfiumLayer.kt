package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.core.unlocked.PdfiumCoreU
import java.io.Closeable
import java.io.File

/**
 * pdfium calls the wrapper library doesn't expose (see cpp/pdfium_layer.cpp): full per-character
 * attributes and a render of the page without its text. Each instance holds one document of its
 * own; every call takes the wrapper's global pdfium lock, because pdfium is not thread-safe and
 * the editor may be using it at the same time.
 *
 * The wrapper reference-counts pdfium: it initialises the library when it opens its first document
 * and destroys it when the last one closes. So each instance also keeps the same file open through
 * the wrapper ([keepAlive]) for as long as it lives; without it pdfium can be torn down under it.
 */
class PdfiumLayer private constructor(
    private var handle: Long,
    /** A second copy of the document, the only one ever edited: see [renderBackground]. */
    private var renderHandle: Long,
    private val keepAlive: PdfDocument,
    private val descriptor: ParcelFileDescriptor
) : Closeable {

    /** Null when the page can't be loaded. */
    fun readPage(index: Int): PdfiumPage? = locked {
        @Suppress("UNCHECKED_CAST")
        val result = nativeReadPage(handle, index) as Array<Any>? ?: return@locked null
        val data = result[0] as FloatArray
        val fonts = result[1] as Array<String>
        val count = data[2].toInt()
        val chars = List(count) { i ->
            val o = HEADER + i * FIELDS
            PdfiumChar(
                code = data[o].toInt(),
                originX = data[o + 1], originY = data[o + 2],
                left = data[o + 3], bottom = data[o + 4], right = data[o + 5], top = data[o + 6],
                size = data[o + 7],
                matrixA = data[o + 8], matrixB = data[o + 9], matrixC = data[o + 10], matrixD = data[o + 11],
                font = fonts.getOrElse(data[o + 12].toInt()) { "" },
                fontFlags = data[o + 13].toInt(),
                weight = data[o + 14].toInt(),
                argb = data[o + 15].toRawBits(),
                flags = data[o + 16].toInt(),
                renderMode = data[o + 17].toInt(),
                inkLeft = data[o + 18], inkBottom = data[o + 19], inkRight = data[o + 20], inkTop = data[o + 21]
            )
        }
        PdfiumPage(data[0], data[1], chars)
    }

    /** The page's top-level images, in drawing order; empty when the page can't be loaded. */
    fun images(index: Int): List<PdfiumImage> = locked {
        val data = nativePageImages(handle, index) ?: return@locked emptyList()
        List(data.size / IMAGE_FIELDS) { i ->
            val o = i * IMAGE_FIELDS
            PdfiumImage(
                objectIndex = data[o].toInt(),
                left = data[o + 1],
                bottom = data[o + 2],
                right = data[o + IMAGE_RIGHT],
                top = data[o + IMAGE_TOP],
                copyableJpeg = data[o + IMAGE_JPEG] > 0f
            )
        }
    }

    /** The image's encoded bytes as stored in the PDF (see [PdfiumImage.copyableJpeg]). */
    fun imageBytes(index: Int, image: PdfiumImage): ByteArray? = locked {
        nativeImageRaw(handle, index, image.objectIndex)
    }

    /** The image's decoded pixels at their own resolution, or null (too large, no memory). */
    fun imageBitmap(index: Int, image: PdfiumImage, maxPixels: Int): Bitmap? = locked {
        nativeImageBitmap(handle, index, image.objectIndex, maxPixels) as Bitmap?
    }

    /**
     * Draws page [index] into [bitmap] (ARGB_8888, scaled to fill it) on white, without its text
     * and without the images listed in [extracted] (they are written as pictures of their own).
     *
     * Removing objects and regenerating a page's content disturbs fonts the document shares between
     * pages (later pages then read back glyph codes instead of text), so this works on a separate
     * copy of the document and [readPage] never sees an edited one.
     */
    fun renderBackground(index: Int, bitmap: Bitmap, extracted: List<PdfiumImage> = emptyList()): Boolean = locked {
        nativeRenderBackground(renderHandle, index, bitmap, extracted.map { it.objectIndex }.toIntArray())
    }

    override fun close() {
        if (handle != 0L) {
            val closing = handle
            val closingRender = renderHandle
            handle = 0L
            renderHandle = 0L
            locked {
                nativeClose(closing)
                nativeClose(closingRender)
            }
            runCatching { keepAlive.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val HEADER = 4
        private const val FIELDS = 22
        private const val IMAGE_FIELDS = 6

        // nativePageImages record: object index, left, bottom, right, top, 1 when the bytes are a plain JPEG.
        private const val IMAGE_RIGHT = 3
        private const val IMAGE_TOP = 4
        private const val IMAGE_JPEG = 5

        init {
            System.loadLibrary("scanlypdf")
        }

        val available: Boolean by lazy { locked { nativeAvailable() } }

        /** Null when pdfium can't open [file] with [password], or the needed calls are missing. */
        fun open(context: Context, file: File, password: String = ""): PdfiumLayer? {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val keepAlive = runCatching {
                val core = PdfiumCore(context)
                if (password.isEmpty()) core.newDocument(descriptor) else core.newDocument(descriptor, password)
            }.getOrNull()
            if (keepAlive == null || !available) {
                runCatching { keepAlive?.close() }
                descriptor.close()
                return null
            }
            val handles = locked { nativeOpen(file.absolutePath, password) to nativeOpen(file.absolutePath, password) }
            if (handles.first == 0L || handles.second == 0L) {
                locked { handles.toList().filter { it != 0L }.forEach(::nativeClose) }
                keepAlive.close()
                descriptor.close()
                return null
            }
            return PdfiumLayer(handles.first, handles.second, keepAlive, descriptor)
        }

        @JvmStatic private external fun nativeAvailable(): Boolean

        @JvmStatic private external fun nativeOpen(path: String, password: String): Long

        @JvmStatic private external fun nativeClose(handle: Long)

        @JvmStatic private external fun nativeReadPage(handle: Long, index: Int): Any?

        @JvmStatic private external fun nativeRenderBackground(
            handle: Long,
            index: Int,
            bitmap: Bitmap,
            removeImages: IntArray
        ): Boolean

        @JvmStatic private external fun nativePageImages(handle: Long, index: Int): FloatArray?

        @JvmStatic private external fun nativeImageRaw(handle: Long, index: Int, objectIndex: Int): ByteArray?

        @JvmStatic private external fun nativeImageBitmap(
            handle: Long,
            index: Int,
            objectIndex: Int,
            maxPixels: Int
        ): Any?
    }
}

/** pdfium isn't thread-safe: every call shares the lock the rest of the app's pdfium use takes. */
private fun <T> locked(block: () -> T): T = PdfiumCoreU.lock.withLockBlocking(block)
