package com.skeler.scanely.core.pdfeditor

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.engine.OverlayComposer
import com.skeler.scanely.core.pdfeditor.engine.PdfEditorEngine
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.signature.PdfSigner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * A file that is not a PDF at all, or one damaged past reading, must be reported as damaged.
 *
 * It used to come back as [PdfOpenResult.NeedsPassword]: the encryption probe answers "couldn't
 * open" with null, and the session read anything but a definite "not encrypted" as encrypted, so a
 * corrupt download asked the user for a password it never had.
 */
@RunWith(AndroidJUnit4::class)
class PdfOpenFailureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "open-failure-test").apply { mkdirs() }

    private fun session(): PdfEditorSession {
        val reader = PdfPageReader(context)
        val qpdf = QpdfEngine()
        return PdfEditorSession(
            context,
            reader,
            qpdf,
            PdfEditorEngine(qpdf, OverlayComposer(context), reader, context),
            PdfSigner(context)
        )
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun randomBytesAreDamagedNotEncrypted() = runBlocking {
        val file = File(dir, "random.pdf").apply { writeBytes(Random(7).nextBytes(20_000)) }

        val result = session().open(Uri.fromFile(file))

        assertTrue("expected Failed, got $result", result is PdfOpenResult.Failed)
    }

    @Test
    fun truncatedPdfIsDamagedNotEncrypted() = runBlocking {
        val file = File(dir, "truncated.pdf").apply { writeText("%PDF-1.7\n1 0 obj\n<< /Type") }

        val result = session().open(Uri.fromFile(file))

        assertTrue("expected Failed, got $result", result is PdfOpenResult.Failed)
    }

    @Test
    fun encryptedPdfStillAsksForItsPassword() = runBlocking {
        val plain = File(dir, "plain.pdf").apply { writeText(MINIMAL_PDF) }
        val locked = File(dir, "locked.pdf")
        val encrypted = QpdfEngine().encrypt(plain, locked, "secret", "secret")
        assertTrue("fixture encryption failed: $encrypted", locked.length() > 0)

        val result = session().open(Uri.fromFile(locked))

        assertEquals(PdfOpenResult.NeedsPassword, result)
    }

    private companion object {
        val MINIMAL_PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
    }
}
