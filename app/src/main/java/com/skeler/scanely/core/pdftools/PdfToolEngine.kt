package com.skeler.scanely.core.pdftools

import android.util.Log
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdfeditor.engine.EncryptionState
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.PdfPermissions
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "PdfToolEngine"

private const val SUFFIX_COMPRESSED = "_compressed"
private const val SUFFIX_PROTECTED = "_protected"
private const val SUFFIX_UNLOCKED = "_unlocked"
private const val SUFFIX_ROTATED = "_rotated"

private const val PDF_EXTENSION = ".pdf"

/** qpdf page-range syntax for every page. */
private const val ALL_PAGES = "1-z"

private val ROTATIONS = setOf(90, 180, 270, -90)

/**
 * An imported file that is known to open. [password] is the one that opened it, empty for a plain
 * file or one with only owner restrictions; every later operation reuses it, so the user types it
 * once per run.
 */
data class ToolDocument(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val password: String,
    val isEncrypted: Boolean
)

sealed interface ProbeResult {
    data class Opened(val document: ToolDocument) : ProbeResult

    /** Also the answer to a wrong password: the caller asks again rather than failing the run. */
    data object NeedsPassword : ProbeResult
}

data class ToolOutput(val file: File, val fileName: String, val mimeType: String, val sizeBytes: Long)

/**
 * [inputBytes] travels with the outputs so a result screen can show the saving without a lookup;
 * [showSizeChange] asks it to (Compress does, a converter doesn't). [notes] say what the user
 * should know about the result, such as content a conversion had to simplify.
 */
data class ToolResult(
    val outputs: List<ToolOutput>,
    val inputBytes: Long,
    val notes: List<ToolNote> = emptyList(),
    val showSizeChange: Boolean = false
)

/**
 * The one-shot jobs behind the PDF tools grid. Thin on purpose: qpdf and pdfium do the work, this
 * layer only turns their answers into [ToolFailure]s a screen can act on, and guarantees a failed
 * or cancelled job leaves nothing behind in the run directory.
 */
@Singleton
class PdfToolEngine @Inject constructor(
    private val qpdf: QpdfEngine,
    private val pageReader: PdfPageReader,
    private val workspace: ToolWorkspace
) {

    /**
     * Damaged/not-a-PDF throws ToolException(ToolFailure.Damaged). A non-empty wrong password also
     * returns NeedsPassword (caller shows retry).
     *
     * qpdf answers first because pdfium can't tell "wrong password" from "damaged" — both are a
     * null document — and treating a corrupt download as locked asks for a password it never had.
     */
    suspend fun probe(imported: ImportedFile, password: String = ""): ProbeResult {
        val state = qpdf.encryptionState(imported.file, password)
        coroutineContext.ensureActive()
        when (state) {
            EncryptionState.UNREADABLE -> throw ToolException(ToolFailure.Damaged)
            EncryptionState.NEEDS_PASSWORD -> return ProbeResult.NeedsPassword
            EncryptionState.PLAIN, EncryptionState.OPENED -> Unit
        }

        // qpdf reading the structure doesn't mean pdfium can lay the pages out; a document with
        // no pages is as useless to every tool as a damaged one.
        val pages = pageReader.pageCount(imported.file, password)
        coroutineContext.ensureActive()
        if (pages == null || pages <= 0) {
            Log.w(TAG, "qpdf read ${imported.file.name} but pdfium found ${pages ?: "no"} pages")
            throw ToolException(ToolFailure.Damaged)
        }

        return ProbeResult.Opened(
            ToolDocument(
                file = imported.file,
                displayName = imported.displayName,
                sizeBytes = imported.sizeBytes,
                pageCount = pages,
                password = password,
                isEncrypted = state == EncryptionState.OPENED
            )
        )
    }

    /** A copy no smaller than the original is a failure, never a result the user might share. */
    suspend fun compress(document: ToolDocument, level: CompressionLevel, run: File): ToolResult =
        produce(document, run, SUFFIX_COMPRESSED, "compress") { output ->
            qpdf.compress(document.file, output, level.settings, document.password)
        }.let { result ->
            val output = result.outputs.single()
            if (output.sizeBytes >= document.sizeBytes) {
                output.file.delete()
                throw ToolException(ToolFailure.NoGain)
            }
            result
        }

    /**
     * One password for both roles: the grid offers "lock this file", not the owner/user split,
     * and a distinct owner password the user never saw would only lock them out of their own copy.
     */
    suspend fun protect(document: ToolDocument, password: String, run: File): ToolResult =
        produce(document, run, SUFFIX_PROTECTED, "protect") { output ->
            qpdf.encrypt(
                input = document.file,
                output = output,
                userPassword = password,
                ownerPassword = password,
                permissions = PdfPermissions(),
                openPassword = document.password
            )
        }

    /** [ToolDocument.password] may be empty here: owner-only restrictions open without one. */
    suspend fun unlock(document: ToolDocument, run: File): ToolResult {
        if (!document.isEncrypted) throw ToolException(ToolFailure.NotEncrypted)
        return produce(document, run, SUFFIX_UNLOCKED, "unlock") { output ->
            qpdf.decrypt(document.file, output, document.password)
        }
    }

    suspend fun rotateAll(document: ToolDocument, degrees: Int, run: File): ToolResult {
        require(degrees in ROTATIONS) { "rotation must be a quarter turn, got $degrees" }
        return produce(document, run, SUFFIX_ROTATED, "rotate") { output ->
            qpdf.rotatePages(document.file, output, degrees, ALL_PAGES, document.password)
        }
    }

    /**
     * The shared shape of every job: check space, run [operation] into a fresh file in [run],
     * translate the answer. The output is deleted on every path that doesn't return it —
     * including cancellation, which can only land after the native call finishes, since qpdf
     * can't be interrupted mid-write.
     */
    private suspend fun produce(
        document: ToolDocument,
        run: File,
        suffix: String,
        label: String,
        operation: suspend (File) -> QpdfResult
    ): ToolResult {
        // Output plus qpdf's own temporaries can briefly reach twice the input.
        workspace.requireSpace(document.sizeBytes * 2)

        val fileName = outputName(document.displayName, suffix)
        val output = File(run, fileName)
        var delivered = false
        try {
            val result = operation(output)
            coroutineContext.ensureActive()
            failureOf(result, label)?.let { throw it }
            if (!output.isFile || output.length() == 0L) {
                Log.w(TAG, "$label reported success but wrote no output")
                throw ToolException(ToolFailure.Failed("$label wrote no output"))
            }
            val toolOutput = ToolOutput(output, fileName, ScanExporter.PDF_MIME_TYPE, output.length())
            delivered = true
            return ToolResult(listOf(toolOutput), document.sizeBytes)
        } finally {
            if (!delivered) output.delete()
        }
    }

    /** The exception a non-Ok [result] maps to, or null when qpdf succeeded. */
    private fun failureOf(result: QpdfResult, label: String): ToolException? = when (result) {
        QpdfResult.Ok -> null
        QpdfResult.WrongPassword -> ToolException(ToolFailure.WrongPassword)
        is QpdfResult.Failed -> engineFailure(label, result.message)
        is QpdfResult.BadUsage -> engineFailure(label, result.message)
    }

    /**
     * qpdf reports a full disk only as text, so the message is run through the same ENOSPC check
     * the import path uses — a full disk is something the user can fix, an engine fault is not.
     */
    private fun engineFailure(label: String, message: String): ToolException {
        // qpdf messages name the file path and the failing object, never contents or passwords.
        Log.w(TAG, "$label failed: $message")
        return if (IOException(message).isOutOfSpace()) {
            ToolException(ToolFailure.LowStorage)
        } else {
            ToolException(ToolFailure.Failed(message))
        }
    }

    private fun outputName(displayName: String, suffix: String): String {
        // Sanitised before the suffix goes on, so the length cap can never cut off ".pdf".
        val base = displayName.substringBeforeLast('.').ifBlank { displayName }
        return ToolWorkspace.safeFileName(base) + suffix + PDF_EXTENSION
    }
}
