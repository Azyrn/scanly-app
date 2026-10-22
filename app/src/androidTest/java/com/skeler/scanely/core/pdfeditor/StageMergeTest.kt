package com.skeler.scanely.core.pdfeditor

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.pdfeditor.engine.EncryptionState
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

/**
 * Adding a password-protected PDF to a merge must ask for its password, not be reported as
 * unreadable — the editor used to read its page count without one and reject it outright.
 */
@RunWith(AndroidJUnit4::class)
class StageMergeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "stage-merge-test").apply { mkdirs() }

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
    fun encryptedPdfIsStagedNotRejected() = runBlocking {
        val plain = File(dir, "plain.pdf").apply { writeText(MINIMAL_PDF) }
        val locked = File(dir, "locked.pdf")
        QpdfEngine().encrypt(plain, locked, SECRET, SECRET)

        val session = session()

        assertEquals(StageMergeResult.NeedsPassword, session.stageMerge(Uri.fromFile(locked)))
        assertEquals(StageMergeResult.NeedsPassword, session.stageMerge(Uri.fromFile(locked), "wrong"))

        val staged = session.stageMerge(Uri.fromFile(locked), SECRET)
        assertTrue("an encrypted PDF was rejected as unreadable: $staged", staged is StageMergeResult.Staged)
        val candidate = (staged as StageMergeResult.Staged).candidate
        assertEquals(1, candidate.pageCount)
        // The append step reads the copy by path with no password, so it must be decrypted.
        assertEquals(EncryptionState.PLAIN, QpdfEngine().encryptionState(File(candidate.path)))
    }

    @Test
    fun randomBytesAreUnreadable() = runBlocking {
        val junk = File(dir, "junk.pdf").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        assertEquals(StageMergeResult.Unreadable, session().stageMerge(Uri.fromFile(junk)))
    }

    private companion object {
        const val SECRET = "secret"
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
