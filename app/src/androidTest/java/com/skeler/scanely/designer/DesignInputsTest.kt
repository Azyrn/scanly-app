package com.skeler.scanely.designer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.ai.DesignInputs
import com.skeler.scanely.core.ai.PickedDocument
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignInputsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inputs = DesignInputs(context, PdfPageReader(context))

    @Test fun uprightJpegAndPngAttachmentsKeepTheirOriginalBytes() = runBlocking {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val file = File.createTempFile("designer-original-", ".image", context.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)
                file.outputStream().use { bitmap.compress(format, 95, it) }
                bitmap.recycle()
                val result = inputs.document(Uri.fromFile(file)) as PickedDocument.Ready
                assertArrayEquals(file.readBytes(), Base64.decode(result.base64, Base64.DEFAULT))
            } finally { file.delete() }
        }
    }

    @Test fun portraitOrientationIsAppliedBeforeTheDesignerSeesIt() = runBlocking {
        val file = File.createTempFile("designer-orientation-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val encoded = checkNotNull(inputs.photo(Uri.fromFile(file)))
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val rotated = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(40, rotated.width)
            assertEquals(20, rotated.height)
            rotated.recycle()
        } finally { file.delete() }
    }

    @Test fun aPdfWithoutProviderMimeMetadataIsStillRecognized() = runBlocking {
        val file = File.createTempFile("designer-source-", ".pdf", context.cacheDir)
        try {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
                pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            assertTrue(inputs.document(Uri.fromFile(file)) is PickedDocument.Ready)
        } finally { file.delete() }
    }

    @Test fun anOversizedPdfIsRefusedBeforeParsing() = runBlocking {
        val file = File.createTempFile("designer-oversize-", ".pdf", context.cacheDir)
        try {
            file.outputStream().use { output ->
                output.write("%PDF-1.7\n".toByteArray())
                repeat(65) { output.write(ByteArray(65536)) }
            }
            assertEquals(PickedDocument.TooLarge, inputs.document(Uri.fromFile(file)))
        } finally { file.delete() }
    }
}
