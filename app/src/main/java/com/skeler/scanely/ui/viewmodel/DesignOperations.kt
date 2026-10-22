package com.skeler.scanely.ui.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import com.skeler.scanely.core.ai.BackendClient
import com.skeler.scanely.core.ai.DesignEvent
import com.skeler.scanely.core.ai.DesignInputs
import com.skeler.scanely.core.ai.DesignIntakeAnswer
import com.skeler.scanely.core.ai.GenerativeAiService
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.ocr.PdfRendererHelper
import com.skeler.scanely.history.data.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The designer's device and network work, as the document chat uses it: past scans to start from,
 * reading a picked file (and its text, by Scanly's OCR), and the design call itself.
 */
class DesignOperations @Inject internal constructor(
    private val ai: GenerativeAiService,
    private val backend: BackendClient,
    private val quota: EntitlementRepository,
    private val history: HistoryManager,
    private val renderer: PdfRendererHelper,
    private val inputs: DesignInputs
) {
    suspend fun sources(): List<DesignSource> = withContext(Dispatchers.IO) {
        history.getHistory().filter { it.text.isNotBlank() }.take(MAX_RECENT_SOURCES).map { it.toDesignSource() }
    }
    suspend fun document(uri: Uri) = inputs.document(uri)
    suspend fun displayName(uri: Uri) = inputs.displayName(uri)
    suspend fun photo(uri: Uri) = inputs.photo(uri)

    /** The same design for the chat: every one a job, with the server's live steps. */
    fun designForChat(request: ChatDesignRequest): Flow<DesignEvent> = with(request) {
        ai.designDocumentEvents(
            prompt, sourceText, designId, photo, document, requestId, template, answer, ocrReceipt, chat = true
        )
    }

    /** Full-resolution render of every page, one at a time; [onPage] owns the bitmap. */
    suspend fun forEachPage(uri: Uri, onPage: suspend (index: Int, bitmap: Bitmap) -> Unit) =
        renderer.forEachPage(uri, onPage = onPage)
    suspend fun refreshQuota() { quota.refresh() }
}

private const val MAX_RECENT_SOURCES = 8

/** The Worker's MAX_SOURCE_TEXT_CHARS: a longer scan is refused there, so the text stops here. */
internal const val DESIGN_SOURCE_LIMIT = 16_000

/** One chat turn's design request, as [DesignOperations.designForChat] sends it. */
data class ChatDesignRequest(
    val prompt: String,
    val sourceText: String?,
    val designId: String?,
    val photo: String?,
    val document: String?,
    val requestId: String,
    val template: String?,
    val answer: DesignIntakeAnswer?,
    val ocrReceipt: String?
)
