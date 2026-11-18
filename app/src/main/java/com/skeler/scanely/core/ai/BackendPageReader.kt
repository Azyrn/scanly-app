package com.skeler.scanely.core.ai

import android.util.Log
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.OcrBlockDto
import com.skeler.scanely.core.network.OcrPageDto
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.pdf2office.CloudPageReader
import com.skeler.scanely.core.pdftools.pdf2office.exact.OcrBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackendPageReader"

/** A block's box is four fractions: left, top, right, bottom. */
private const val BOX_SIDES = 4
private const val TOP = 1
private const val RIGHT = 2

/** Tries of one read under one key, and the waits between them. */
private const val MAX_ATTEMPTS = 3
private const val IO_RETRY_MS = 2_000L
private const val MAX_WAIT_SECONDS = 10
private const val MS_PER_SECOND = 1_000L

/**
 * [CloudPageReader] through the Worker's `/v1/ocr` (`blocks=1`): the requested pages are copied
 * into one PDF with the bundled qpdf and sent in one request, 1 credit per page sent, and only after
 * the user chose "Use credits" (OnlineReadingPrompt). Signed out, offline, "Convert without", and every
 * failure (no credits, a page count that does not come back) read as nothing, so the conversion keeps
 * the PDF's own text.
 */
@Singleton
internal class BackendPageReader @Inject constructor(
    private val client: BackendClient,
    private val qpdf: QpdfEngine,
    private val charges: ConversionCharges,
    private val prompt: OnlineReadingPrompt,
    private val auth: AuthRepository,
    private val network: NetworkObserver
) : CloudPageReader {

    override suspend fun read(document: ToolDocument, pages: List<Int>, scratch: File): Map<Int, List<OcrBlock>> {
        // No flagged pages: free, no question. Signed out or offline: nothing could be read, so no question either.
        if (!mayRead(pages)) return emptyMap()
        val subset = File(scratch, "cloud-ocr-pages.pdf")
        val plain = File(scratch, "cloud-ocr.pdf")
        val read = try {
            upload(document, pages, subset, plain)?.let { readPages(it, pages) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Pages not read again: ${e.javaClass.simpleName}")
            emptyMap()
        } finally {
            subset.delete()
            plain.delete()
        }
        // The user chose to pay for this read; one that came back with nothing must not end silently.
        if (read.isEmpty()) prompt.reportFailure()
        return read
    }

    /** The requested pages copied into [subset], decrypted into [plain] when the source has a password; null when qpdf failed. */
    private suspend fun upload(document: ToolDocument, pages: List<Int>, subset: File, plain: File): File? {
        val spec = pages.joinToString(",") { (it + 1).toString() }
        if (qpdf.selectPages(document.file, subset, spec, document.password) != QpdfResult.Ok) return null
        // The copy keeps the source's encryption; the reader needs the pages themselves.
        if (document.password.isEmpty()) return subset
        return if (qpdf.decrypt(subset, plain, document.password) == QpdfResult.Ok) plain else null
    }

    private suspend fun readPages(upload: File, pages: List<Int>): Map<Int, List<OcrBlock>> {
        val key = UUID.randomUUID().toString()
        // Named before it is sent: the server bills a read the moment it succeeds there, so an
        // answer lost on the way back must still leave a charge to collect or give back. A
        // cancelled run leaves the key here for ToolRunGuard's refund.
        charges.record(key)
        val read = collect(upload, key) ?: return emptyMap()
        return if (read.size != pages.size) {
            // Paid for and unusable: given back now rather than when the conversion ends.
            charges.refund(key)
            emptyMap()
        } else {
            pages.zip(read) { page, dto -> page to blocks(dto.blocks) }.toMap()
        }
    }

    /**
     * The read under [key], asking again with the SAME key when the answer did not arrive: the
     * server replays a read it finished and answers duplicate_request (a [TransientAiException])
     * while it still runs, so asking again never bills twice. Null when it gave up; the read is
     * then given back, since it may have been billed. A refund that reaches the server while the
     * read is still running there finds nothing yet (`not_found`); ConversionCharges keeps it and
     * asks again until the read is billed or the refund window is over.
     */
    private suspend fun collect(upload: File, key: String): List<OcrPageDto>? {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val waitMs = try {
                return client.ocrBlocks(upload, key)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                retryWaitMs(e, attempt)
            } ?: break
            if (attempt < MAX_ATTEMPTS - 1) delay(waitMs)
        }
        charges.refund(key)
        return null
    }

    /** How long to wait before asking again after [e]; null when asking again is pointless. */
    private fun retryWaitMs(e: Exception, attempt: Int): Long? = when (e) {
        is TransientAiException -> {
            Log.w(TAG, "Read not answered yet (attempt ${attempt + 1})")
            (e.retryAfterSeconds ?: 1).coerceAtMost(MAX_WAIT_SECONDS) * MS_PER_SECOND
        }
        is IOException -> {
            Log.w(TAG, "Read answer lost: ${e.javaClass.simpleName} (attempt ${attempt + 1})")
            IO_RETRY_MS
        }
        else -> {
            // Refused (no credits, plan) or a body that did not parse: no point asking again.
            Log.w(TAG, "Pages not read again: ${e.javaClass.simpleName}")
            null
        }
    }

    /** No flagged pages, signed out or offline: no question. Otherwise the user decides. */
    private suspend fun mayRead(pages: List<Int>): Boolean =
        pages.isNotEmpty() && auth.isSignedIn && network.isCurrentlyOnline() && prompt.ask(pages.size)

    private fun blocks(dtos: List<OcrBlockDto>): List<OcrBlock> = dtos.mapNotNull { dto ->
        val box = dto.box
        if (box.size != BOX_SIDES || dto.text.isBlank() || box.any { !it.isFinite() }) {
            null
        } else {
            OcrBlock(box.first(), box[TOP], box[RIGHT], box.last(), dto.text)
        }
    }
}
