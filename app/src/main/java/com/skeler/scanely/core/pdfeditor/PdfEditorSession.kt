package com.skeler.scanely.core.pdfeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.pdfeditor.engine.CompressionSettings
import com.skeler.scanely.core.pdfeditor.engine.EncryptionState
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.engine.PageTextSnapshot
import com.skeler.scanely.core.pdfeditor.engine.PdfEditResult
import com.skeler.scanely.core.pdfeditor.engine.PdfEditorEngine
import com.skeler.scanely.core.pdfeditor.engine.PdfMetadata
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdfeditor.model.EditSession
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.signature.PdfSigner
import com.skeler.scanely.core.pdfeditor.signature.SignRequest
import com.skeler.scanely.core.pdfeditor.signature.SignResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "PdfEditorSession"

/** Past this the quality steps are finer than the eye, and each probe costs a full rewrite. */
private const val MAX_SEARCH_PROBES = 6

/** Low enough that a target search can reach every image the document actually spends bytes on. */
private const val TARGET_SEARCH_MIN_EDGE = 16

data class OpenPdf(
    val file: File,
    val displayName: String,
    val password: String,
    val pageCount: Int,
    val geometries: List<PageGeometry>,
    val sizeBytes: Long,
    val isEncrypted: Boolean
)

sealed interface PdfOpenResult {
    data object Opened : PdfOpenResult
    data object NeedsPassword : PdfOpenResult
    data class Failed(val message: String) : PdfOpenResult
}

/** A document queued for merging, already copied where [PdfEdit.AppendDocument] can reach it. */
data class MergeCandidate(val path: String, val displayName: String, val pageCount: Int)

sealed interface StageMergeResult {
    data class Staged(val candidate: MergeCandidate) : StageMergeResult

    /** Encrypted, and the password given (none, on the first try) didn't open it. */
    data object NeedsPassword : StageMergeResult

    data object Unreadable : StageMergeResult
}

/**
 * What one compression setting achieved, measured rather than predicted.
 *
 * [bytes] is what qpdf produced; [effectiveBytes] is what the user would actually end up with,
 * because a pass that came out larger than the original is discarded at save time.
 */
data class CompressionResult(
    val settings: CompressionSettings,
    val bytes: Long,
    val originalBytes: Long
) {
    val helps: Boolean get() = bytes < originalBytes

    val effectiveBytes: Long get() = minOf(bytes, originalBytes)

    /** Negative is a saving, positive is growth. Never rounded towards a flattering zero. */
    val deltaPercent: Int
        get() = if (originalBytes <= 0L) {
            0
        } else {
            (((bytes - originalBytes).toDouble() / originalBytes) * 100).roundToInt()
        }
}

/**
 * The one open document, shared by every editor screen.
 *
 * A singleton rather than ViewModel state: the page grid, the canvas and the document tools edit
 * one document and must share its staged edit stack, working copy and password, and none of them
 * owns its lifetime.
 *
 * Always a private working copy — the user's file is never opened for writing, so abandoning a
 * session costs nothing and cannot corrupt the original.
 */
@Singleton
class PdfEditorSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageReader: PdfPageReader,
    private val qpdf: QpdfEngine,
    private val engine: PdfEditorEngine,
    private val signer: PdfSigner
) {
    /** A saved or signed file still to reach Downloads; see [ExportRetry]. */
    internal val exportRetry = ExportRetry()

    val edits = EditSession()

    private val _document = MutableStateFlow<OpenPdf?>(null)
    val document: StateFlow<OpenPdf?> = _document.asStateFlow()

    /** Bumped whenever the working file is replaced, so cached renders can be dropped. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Set the moment the file is copied in, so unlocking works before there is a document. */
    private var pending: Pair<File, String>? = null

    private val stamps = mutableMapOf<String, Bitmap>()
    private val snapshots = mutableMapOf<Int, PageTextSnapshot?>()
    private val snapshotLock = Mutex()

    private val workspace: File
        get() = File(context.cacheDir, "pdf-editor").apply { mkdirs() }

    suspend fun open(uri: Uri): PdfOpenResult = withContext(Dispatchers.IO) {
        close()
        val working = File(workspace, "working.pdf")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                working.outputStream().use { input.copyTo(it) }
            } ?: error("Unable to read $uri")
        }
        if (copied.isFailure) {
            Log.w(TAG, "Could not copy $uri into the editor workspace", copied.exceptionOrNull())
            return@withContext PdfOpenResult.Failed("Couldn't open that file.")
        }

        val name = UriNames.displayName(context, uri) ?: "Document.pdf"
        pending = working to name
        probe(working, name, password = "")
    }

    suspend fun unlock(password: String): PdfOpenResult {
        val (file, name) = _document.value?.let { it.file to it.displayName }
            ?: pending
            ?: return PdfOpenResult.Failed("No document is open.")
        return probe(file, name, password)
    }

    /** Continues the session on [saved], so an edit-save-edit loop never reopens from the picker. */
    suspend fun adopt(saved: File, password: String) = withContext(Dispatchers.IO) {
        val current = _document.value ?: return@withContext
        val working = File(workspace, "working.pdf")
        if (saved.absolutePath != working.absolutePath) saved.copyTo(working, overwrite = true)
        edits.clear()
        snapshots.clear()
        probe(working, current.displayName, password)
        Unit
    }

    fun close() {
        exportRetry.clear()
        edits.clear()
        snapshots.clear()
        stamps.clear()
        pending = null
        _document.value = null
        _revision.update { it + 1 }
        runCatching { workspace.deleteRecursively() }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val current = _document.value ?: return null
        return pageReader.renderPage(current.file, pageIndex, targetWidthPx, current.password)
    }

    suspend fun textSnapshot(pageIndex: Int): PageTextSnapshot? {
        val current = _document.value ?: return null
        snapshotLock.withLock {
            if (snapshots.containsKey(pageIndex)) return snapshots[pageIndex]
        }
        val snapshot = pageReader.textSnapshot(current.file, pageIndex, current.password)
        snapshotLock.withLock { snapshots[pageIndex] = snapshot }
        return snapshot
    }

    suspend fun metadata(): PdfMetadata? {
        val current = _document.value ?: return null
        return pageReader.documentMeta(current.file, current.password)
    }

    fun registerStamp(id: String, bitmap: Bitmap) {
        stamps[id] = bitmap
    }

    fun stamp(id: String): Bitmap? = stamps[id]

    /**
     * Runs one compression setting for real and reports what it achieves. Estimating from the
     * page count would be a guess; a scanned document and a text document of the same length
     * compress by wildly different amounts, and the whole point of the preview is to be right.
     */
    suspend fun compressedSize(settings: CompressionSettings): CompressionResult? =
        withContext(Dispatchers.IO) {
            val current = _document.value ?: return@withContext null
            val target = File(workspace, "estimate-${System.nanoTime()}.pdf")
            val result = qpdf.compress(current.file, target, settings, current.password)
            val size = (result as? QpdfResult.Ok)
                ?.let { target.length().takeIf { bytes -> bytes > 0 } }
            target.delete()
            size?.let { CompressionResult(settings, it, current.sizeBytes) }
        }

    /**
     * Highest image quality that still fits [targetBytes], by bisection.
     *
     * Each probe is a real compression pass, not a model, so the figure reported is the figure the
     * user gets. [MAX_SEARCH_PROBES] caps the wait; past six probes the steps are invisible.
     * Returns the closest result even when the target is unreachable, so the caller can say so.
     */
    suspend fun searchForTargetSize(
        targetBytes: Long,
        onProbe: suspend (CompressionResult) -> Unit = {}
    ): CompressionResult? = withContext(Dispatchers.IO) {
        val lossless = compressedSize(CompressionSettings(jpegQuality = 0, minImageEdge = 0))
            ?: return@withContext null
        onProbe(lossless)
        if (lossless.bytes <= targetBytes) return@withContext lossless

        var low = CompressionSettings.MIN_QUALITY
        var high = CompressionSettings.MAX_QUALITY
        var best: CompressionResult = lossless
        var probes = 0

        while (low <= high && probes < MAX_SEARCH_PROBES) {
            val quality = (low + high) / 2
            val probe = compressedSize(
                CompressionSettings(quality, TARGET_SEARCH_MIN_EDGE)
            ) ?: break
            probes++
            onProbe(probe)

            // A hit at a higher quality always wins; a miss only wins while nothing has hit yet.
            val hit = probe.bytes <= targetBytes
            val bestHit = best.bytes <= targetBytes
            if (hit && (!bestHit || probe.bytes > best.bytes)) {
                best = probe
            } else if (!hit && !bestHit && probe.bytes < best.bytes) {
                best = probe
            }

            if (hit) low = quality + 1 else high = quality - 1
        }
        best
    }

    /**
     * Copies a picked document somewhere [PdfEdit.AppendDocument] can read it back by path.
     *
     * A password-protected document is asked for its password rather than rejected, and is
     * decrypted into the copy once it opens: the append step reads it by path alone, and the
     * merged result is a new document anyway.
     */
    suspend fun stageMerge(uri: Uri, password: String = ""): StageMergeResult = withContext(Dispatchers.IO) {
        val target = File(workspace, "merge-${System.nanoTime()}.pdf")
        if (!copyInto(uri, target)) return@withContext StageMergeResult.Unreadable

        val state = qpdf.encryptionState(target, password)
        val staged = when (state) {
            EncryptionState.PLAIN -> target
            EncryptionState.OPENED -> decryptedCopy(target, password)
            EncryptionState.NEEDS_PASSWORD, EncryptionState.UNREADABLE -> {
                target.delete()
                return@withContext if (state == EncryptionState.NEEDS_PASSWORD) {
                    StageMergeResult.NeedsPassword
                } else {
                    StageMergeResult.Unreadable
                }
            }
        }
        staged?.let { stagedResult(it, uri) } ?: StageMergeResult.Unreadable
    }

    private suspend fun stagedResult(staged: File, uri: Uri): StageMergeResult {
        val pages = pageReader.pageCount(staged)
        if (pages == null) {
            staged.delete()
            return StageMergeResult.Unreadable
        }
        val name = UriNames.displayName(context, uri) ?: staged.name
        return StageMergeResult.Staged(MergeCandidate(staged.absolutePath, name, pages))
    }

    private fun copyInto(uri: Uri, target: File): Boolean {
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("Unable to read $uri")
        }
        if (copied.isFailure) target.delete()
        return copied.isSuccess
    }

    /** Replaces [source] with an unencrypted copy; null when the password didn't decrypt it. */
    private suspend fun decryptedCopy(source: File, password: String): File? {
        val plain = File(workspace, "merge-${System.nanoTime()}-open.pdf")
        val ok = qpdf.decrypt(source, plain, password) is QpdfResult.Ok
        source.delete()
        return plain.takeIf { ok }.also { if (!ok) plain.delete() }
    }

    /** Writes the named pages, in the order given, to their own file. */
    suspend fun extractPages(originalIndices: List<Int>): File? = withContext(Dispatchers.IO) {
        val current = _document.value ?: return@withContext null
        if (originalIndices.isEmpty()) return@withContext null

        val target = File(workspace, "extract.pdf")
        val spec = originalIndices.joinToString(",") { (it + 1).toString() }
        target.takeIf {
            qpdf.selectPages(current.file, it, spec, current.password) is QpdfResult.Ok
        }
    }

    /**
     * Applies whatever is staged, then signs. The order is the guarantee: signing first and editing
     * after would rewrite the file through qpdf and void the signature just made.
     *
     * Encrypted documents are refused, not handled. PDFBox cannot append to one and encrypting
     * afterwards would rewrite the file, so the two features are mutually exclusive — better said
     * than silently dropped.
     */
    suspend fun signDocument(request: SignRequest): SignResult = withContext(Dispatchers.IO) {
        val current = _document.value ?: return@withContext SignResult.Failed("No document is open.")
        if (current.isEncrypted || current.password.isNotEmpty()) {
            return@withContext SignResult.EncryptedNotSupported
        }
        if (edits.plan().structural.any { it is PdfEdit.Encrypt }) {
            return@withContext SignResult.EncryptedNotSupported
        }

        val base = when (val applied = applyEdits()) {
            is PdfEditResult.Saved -> applied.file
            is PdfEditResult.NothingToDo -> current.file
            is PdfEditResult.WrongPassword -> return@withContext SignResult.EncryptedNotSupported
            is PdfEditResult.Failed -> return@withContext SignResult.Failed(applied.message)
        }

        signer.sign(base, File(workspace, "signed.pdf"), request)
    }

    suspend fun applyEdits(): PdfEditResult {
        val current = _document.value ?: return PdfEditResult.Failed("No document is open.")
        val output = File(workspace, "edited.pdf")
        return engine.apply(
            source = current.file,
            plan = edits.plan(),
            output = output,
            password = current.password,
            stampBitmaps = stamps.toMap()
        )
    }

    private suspend fun probe(
        file: File,
        displayName: String,
        password: String
    ): PdfOpenResult {
        val pageCount = pageReader.pageCount(file, password)
        if (pageCount == null || pageCount <= 0) {
            // Only a definite "encrypted" earns a password prompt. A file qpdf can't read at all
            // is damaged or not a PDF, and asking for a password it never had sends the user
            // hunting for one that doesn't exist.
            return if (qpdf.isEncrypted(file, password) == true) {
                PdfOpenResult.NeedsPassword
            } else {
                PdfOpenResult.Failed(context.getString(R.string.pdf_open_damaged))
            }
        }

        val geometries = pageReader.pageGeometries(file, password)
            ?: return PdfOpenResult.Failed("Couldn't read this PDF's pages.")

        _document.value = OpenPdf(
            file = file,
            displayName = displayName,
            password = password,
            pageCount = pageCount,
            geometries = geometries,
            sizeBytes = file.length(),
            isEncrypted = qpdf.isEncrypted(file, password) == true
        )
        _revision.update { it + 1 }
        return PdfOpenResult.Opened
    }
}
