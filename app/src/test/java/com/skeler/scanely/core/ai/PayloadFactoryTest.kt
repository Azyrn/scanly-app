package com.skeler.scanely.core.ai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class PayloadFactoryTest {
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val uri = mock(Uri::class.java)
    init { `when`(context.contentResolver).thenReturn(resolver) }

    @Test fun `large document uses less than two payloads of heap`() {
        val size = 4 * 1024 * 1024
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        val source = ByteArray(size)
        `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(source) }
        val factory = PayloadFactory(context)
        factory.create(uri, size.toLong())
        val bean = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        val before = bean.currentThreadAllocatedBytes
        factory.create(uri, size.toLong())
        val allocated = bean.currentThreadAllocatedBytes - before
        assertTrue("Allocated $allocated bytes for a $size-byte upload", allocated < size * 2L)
    }

    @Test fun `oversized PDF stops reading at the cap and closes its stream`() {
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(1000)) {
            override fun close() { closed = true; super.close() }
        }
        `when`(resolver.openInputStream(uri)).thenReturn(input)
        mockStatic(Log::class.java).use {
            assertThrows(PayloadFactory.PayloadException::class.java) { PayloadFactory(context).create(uri, 10) }
        }
        assertTrue(closed)
        assertTrue("Do not consume the entire oversized file", input.available() >= 989)
    }

    @Test fun `a document exactly at the server limit remains unchanged`() {
        `when`(resolver.getType(uri)).thenReturn("application/pdf")
        val bytes = byteArrayOf(1, 2, 3)
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bytes))
        val payload = PayloadFactory(context).create(uri, 3) as PayloadFactory.Payload.File
        assertArrayEquals(bytes, payload.bytes.toByteArray())
    }

    @Test fun `large camera images are sampled before pixel allocation`() {
        `when`(resolver.getType(uri)).thenReturn("image/jpeg")
        `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(byteArrayOf(1)) }
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.width).thenReturn(1536)
        `when`(bitmap.height).thenReturn(1024)
        `when`(bitmap.compress(any(), anyInt(), any())).thenAnswer {
            (it.arguments[2] as OutputStream).write(byteArrayOf(1, 2))
            true
        }
        var sample = 0
        mockStatic(BitmapFactory::class.java).use { factory ->
            factory.`when`<Bitmap?> { BitmapFactory.decodeStream(any(), isNull(), any()) }.thenAnswer {
                val options = it.arguments[2] as BitmapFactory.Options
                if (options.inJustDecodeBounds) {
                    options.outWidth = 12000
                    options.outHeight = 8000
                    null
                } else {
                    sample = options.inSampleSize
                    bitmap
                }
            }
            PayloadFactory(context).create(uri)
        }
        assertEquals(4, sample)
        verify(bitmap).recycle()
    }
}
