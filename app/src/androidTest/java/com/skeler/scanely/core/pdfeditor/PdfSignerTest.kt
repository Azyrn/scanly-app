package com.skeler.scanely.core.pdfeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.signature.Integrity
import com.skeler.scanely.core.pdfeditor.signature.PdfSigner
import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Loader
import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Result
import com.skeler.scanely.core.pdfeditor.signature.SignRequest
import com.skeler.scanely.core.pdfeditor.signature.SignResult
import com.skeler.scanely.core.pdfeditor.signature.SignatureAppearance
import com.skeler.scanely.core.pdfeditor.signature.SignatureValidator
import com.skeler.scanely.core.pdfeditor.signature.SigningIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Signs on the device and leaves the results where they can be pulled and checked against an
 * independent implementation. Signing and verifying with the same library proves very little; the
 * failure that matters is a subtly malformed signature, which looks fine until someone else opens
 * it.
 *
 * Outputs land in the app's external files directory:
 *   adb shell run-as com.skeler.scanely.debug ... or adb pull from getExternalFilesDir.
 */
@RunWith(AndroidJUnit4::class)
class PdfSignerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Fixtures ship in the test APK, which is a different asset manager from the app's. */
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private val signer = PdfSigner(context)
    private val validator = SignatureValidator(context)

    private val outputDir: File
        get() = File(context.getExternalFilesDir(null), "signing-test").apply { mkdirs() }

    private fun asset(name: String): File {
        val target = File(context.cacheDir, "signing-fixtures").apply { mkdirs() }
            .resolve(name)
        testAssets.open("signing/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun identity(): SigningIdentity {
        val result = Pkcs12Loader.load(asset("signer.p12").readBytes(), "scanly".toCharArray())
        assertTrue("$result", result is Pkcs12Result.Loaded)
        return (result as Pkcs12Result.Loaded).identity
    }

    /** Stands in for a signature bitmap out of the store: ink on transparency, wide and short. */
    private fun inkBitmap(): Bitmap =
        Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                val paint = Paint().apply {
                    color = Color.rgb(20, 40, 140)
                    strokeWidth = 12f
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                }
                drawLine(40f, 150f, 200f, 40f, paint)
                drawLine(200f, 40f, 320f, 160f, paint)
                drawLine(320f, 160f, 560f, 60f, paint)
            }
        }

    private fun signFixture(
        fixture: String,
        outputName: String,
        withAppearance: Boolean = true
    ): File {
        val source = asset(fixture)
        val output = File(outputDir, outputName)
        val result = runBlocking {
            signer.sign(
                source = source,
                output = output,
                request = SignRequest(
                    identity = identity(),
                    reason = "I approve this document",
                    location = "Casablanca",
                    appearance = if (withAppearance) {
                        SignatureAppearance(
                            pageIndex = 0,
                            box = Rect(360f, 640f, 540f, 700f),
                            bitmap = inkBitmap()
                        )
                    } else {
                        null
                    }
                )
            )
        }
        assertTrue("signing $fixture gave $result", result is SignResult.Saved)
        return output
    }

    @Test
    fun signsASimpleDocument() {
        val output = signFixture("plain.pdf", "plain-signed.pdf")

        val signatures = runBlocking { validator.validate(output) }
        assertEquals(1, signatures.size)
        assertEquals(Integrity.INTACT, signatures.single().integrity)
        assertTrue(signatures.single().coversWholeDocument)
    }

    @Test
    fun signsWithoutAVisibleAppearance() {
        val output = signFixture("plain.pdf", "plain-invisible-signed.pdf", withAppearance = false)

        val signatures = runBlocking { validator.validate(output) }
        assertEquals(1, signatures.size)
        assertEquals(Integrity.INTACT, signatures.single().integrity)
    }

    /** The trap: PDFBox 2.0's incremental save has struggled with object-stream layouts. */
    @Test
    fun signsADocumentWithACompressedCrossReference() {
        val output = signFixture("objstm.pdf", "objstm-signed.pdf")

        val signatures = runBlocking { validator.validate(output) }
        assertEquals(1, signatures.size)
        assertEquals(Integrity.INTACT, signatures.single().integrity)
    }

    @Test
    fun signsARealWorldDocument() {
        val output = signFixture("real.pdf", "real-signed.pdf")

        val signatures = runBlocking { validator.validate(output) }
        assertEquals(1, signatures.size)
        assertEquals(Integrity.INTACT, signatures.single().integrity)
    }

    /**
     * The point of the incremental save: the signature already in the file must still cover the
     * bytes it signed. A rewrite through qpdf is what breaks this.
     */
    @Test
    fun signingLeavesAnExistingSignatureIntact() {
        val output = signFixture("presigned.pdf", "presigned-signed.pdf")

        val signatures = runBlocking { validator.validate(output) }
        assertEquals(2, signatures.size)
        signatures.forEach {
            assertEquals("${it.fieldName} was not intact", Integrity.INTACT, it.integrity)
        }

        // The older signature no longer covers the file's new tail, and must say so.
        val existing = signatures.single { it.fieldName == "ExistingSignature" }
        assertTrue(!existing.coversWholeDocument)

        val added = signatures.single { it.fieldName != "ExistingSignature" }
        assertTrue(added.coversWholeDocument)
    }

    @Test
    fun theSourceFileIsLeftUntouched() {
        val source = asset("plain.pdf")
        val before = source.readBytes()

        signFixture("plain.pdf", "plain-signed-again.pdf")

        assertTrue(before.contentEquals(asset("plain.pdf").readBytes()))
    }

    @Test
    fun signedBytesArePrefixedByTheOriginalDocument() {
        val source = asset("plain.pdf")
        val output = signFixture("plain.pdf", "plain-prefix-check.pdf")

        val original = source.readBytes()
        val signed = output.readBytes()

        assertTrue("signed file is not longer", signed.size > original.size)
        assertTrue(
            "the original bytes were rewritten rather than appended to",
            signed.copyOfRange(0, original.size).contentEquals(original)
        )
    }
}
