package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.ocr.BatchProgress
import com.skeler.scanely.core.ocr.OcrResult
import com.skeler.scanely.core.ocr.OcrSource
import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.ocr.scanFailureMessage
import com.skeler.scanely.core.scan.UnifiedScanResult
import com.skeler.scanely.core.scan.UnifiedScanService
import com.skeler.scanely.history.data.HistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "UnifiedScanViewModel"

@HiltViewModel
class UnifiedScanViewModel @Inject constructor(
    private val unifiedScanService: UnifiedScanService,
    private val historyManager: HistoryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnifiedScanUiState())
    val uiState: StateFlow<UnifiedScanUiState> = _uiState.asStateFlow()

    private var currentScanJob: Job? = null

    fun processImage(uri: Uri) = processImages(listOf(uri))

    fun processImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val uri = uris.first()
        currentScanJob?.cancel()
        _uiState.value = UnifiedScanUiState(
            isLoading = true,
            sourceUri = uri,
            progress = BatchProgress(0, uris.size).takeIf { uris.size > 1 }
        )

        currentScanJob = viewModelScope.launch {
            val result = if (uris.size == 1) {
                unifiedScanService.scanImage(uri)
            } else {
                unifiedScanService.scanImages(uris) { done ->
                    _uiState.update { it.copy(progress = BatchProgress(done, uris.size)) }
                }
            }

            val state = result.toUiState(uri)
            val extractedText = state.extractedText
            _uiState.value = state

            if (extractedText != null) {
                try {
                    withContext(Dispatchers.IO) {
                        historyManager.saveItem(extractedText, uri.toString())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "History save failed", e)
                }
            }
        }
    }

    fun processBarcodeOnly(uri: Uri) {
        currentScanJob?.cancel()
        _uiState.value = UnifiedScanUiState(isLoading = true, sourceUri = uri)

        currentScanJob = viewModelScope.launch {
            val barcodeActions = unifiedScanService.scanBarcodeOnly(uri)

            _uiState.value = UnifiedScanUiState(
                isLoading = false,
                barcodeActions = barcodeActions,
                hasBarcodes = barcodeActions.isNotEmpty(),
                isEmpty = barcodeActions.isEmpty(),
                sourceUri = uri
            )
        }
    }

    fun updateExtractedText(text: String) {
        // Boxes and structured Markdown describe the scan, not the edit — drop them,
        // and stop offering the Paddle-only export formats they backed.
        _uiState.value = _uiState.value.copy(
            extractedText = text,
            textBlocks = emptyList(),
            markdown = null,
            isOfflineOcr = false
        )
    }

    fun clearResult() {
        currentScanJob?.cancel()
        currentScanJob = null
        _uiState.value = UnifiedScanUiState()
    }
}

data class UnifiedScanUiState(
    val isLoading: Boolean = false,
    val extractedText: String? = null,
    val textBlocks: List<TextBlockData> = emptyList(),
    val isOfflineOcr: Boolean = false,
    val markdown: String? = null,
    val barcodeActions: List<ScanAction> = emptyList(),
    val textActions: List<ScanAction> = emptyList(),
    val hasText: Boolean = false,
    val hasBarcodes: Boolean = false,
    val isEmpty: Boolean = true,
    val sourceUri: Uri? = null,
    val progress: BatchProgress? = null,
    /** Set when the scan failed rather than found nothing, so the screen can say which. */
    val failure: String? = null
)

internal fun UnifiedScanResult.toUiState(uri: Uri?): UnifiedScanUiState {
    val success = textResult as? OcrResult.Success
    // A readable code is a result even when the text beside it failed; the failure screen is only
    // for a scan that produced nothing at all.
    val failure = scanFailureMessage(textResult).takeIf { isEmpty }
    return UnifiedScanUiState(
        isLoading = false,
        extractedText = success?.text,
        textBlocks = success?.blocks.orEmpty(),
        isOfflineOcr = success?.source == OcrSource.PADDLE,
        markdown = success?.markdown,
        barcodeActions = barcodeActions,
        textActions = textActions,
        hasText = hasText,
        hasBarcodes = hasBarcodes,
        isEmpty = isEmpty && failure == null,
        sourceUri = uri,
        failure = failure
    )
}
