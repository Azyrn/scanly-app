package com.skeler.scanely.ui.pdfeditor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SignatureDecodeTest {
    @Test fun `signature input is opened off the result callback thread`() = runBlocking {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        var reader: Thread? = null
        `when`(resolver.openInputStream(uri)).thenAnswer {
            reader = Thread.currentThread()
            null
        }
        val callback = Thread.currentThread()
        decodeBitmap(context, uri)
        assertNotNull(reader)
        assertNotSame(callback, reader)
    }
}
