package com.skeler.scanely.core.scan

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.skeler.scanely.core.ocr.TextOcrService
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import dagger.Lazy
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream

class GalleryOrientationTest {
    @Test fun `gallery decode rotates the sampled pixels before recognition`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(byteArrayOf()) }
        val service = UnifiedScanService(context, mock(SettingsRepository::class.java), Lazy<TextOcrService> { error("unused") })
        val decoded = mock(Bitmap::class.java)
        val upright = mock(Bitmap::class.java)
        mockConstruction(ExifInterface::class.java) { exif, _ ->
            `when`(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
                .thenReturn(ExifInterface.ORIENTATION_ROTATE_90)
        }.use {
            mockConstruction(Matrix::class.java).use { matrices ->
                mockStatic(BitmapFactory::class.java).use { factory ->
                    factory.`when`<Bitmap?> { BitmapFactory.decodeStream(any(), isNull(), any()) }.thenReturn(decoded)
                    mockStatic(Bitmap::class.java).use { bitmaps ->
                        bitmaps.`when`<Bitmap> {
                            Bitmap.createBitmap(eq(decoded), anyInt(), anyInt(), anyInt(), anyInt(), any(), eq(true))
                        }.thenReturn(upright)
                        assertSame(upright, service.loadBitmapFromUri(uri))
                        verify(matrices.constructed().single()).setRotate(90f)
                        verify(decoded).recycle()
                    }
                }
            }
        }
    }
}
