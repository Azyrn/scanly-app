package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.ocr.BatchProgress
import com.skeler.scanely.core.ocr.OcrResult
import com.skeler.scanely.core.ocr.TextOcrService
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.history.data.HistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "OcrViewModel"

data class OcrUiState(
    val isProcessing: Boolean = false,
    val result: OcrResult? = null,
    val sourceUri: Uri? = null,
    val progress: BatchProgress? = null
)

@HiltViewModel
class OcrViewModel @Inject constructor(
    private val ocrService: TextOcrService,
    private val historyManager: HistoryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private var savedHistoryId: Deferred<String?>? = null

    private var processingJob: Job? = null

    fun processPdf(pdfUri: Uri) = processPdfs(listOf(pdfUri))

    fun processPdfs(pdfUris: List<Uri>) {
        if (pdfUris.isEmpty()) return

        val sourceUri = pdfUris.first()
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _uiState.value = OcrUiState(
                isProcessing = true,
                sourceUri = sourceUri,
                progress = BatchProgress(0, pdfUris.size).takeIf { pdfUris.size > 1 }
            )

            val result = ocrService.recognizeFromPdfs(pdfUris) { done ->
                if (pdfUris.size > 1) {
                    _uiState.update { it.copy(progress = BatchProgress(done, pdfUris.size)) }
                }
            }
            _uiState.value = OcrUiState(
                isProcessing = false,
                result = result,
                sourceUri = sourceUri
            )

            if (result is OcrResult.Success && result.text.isNotBlank()) {
                saveToHistory(result.text, sourceUri)
            }
        }
    }

    private fun saveToHistory(text: String, uri: Uri) {
        savedHistoryId = viewModelScope.async(Dispatchers.IO) {
            try {
                historyManager.saveItem(text, uri.toString()).id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "History save failed", e)
                null
            }
        }
    }

    fun updateText(newText: String) {
        val current = _uiState.value.result
        if (current !is OcrResult.Success) return
        // Structured metadata describes the original scan, not user edits.
        applyEdit(current.copy(text = newText, blocks = emptyList(), markdown = null), newText)
    }

    fun updateMarkdown(newMarkdown: String) {
        val current = _uiState.value.result
        if (current !is OcrResult.Success) return
        val plain = MarkdownParser.toPlainText(newMarkdown)
        applyEdit(
            current.copy(text = plain, blocks = emptyList(), markdown = newMarkdown),
            plain
        )
    }

    private fun applyEdit(result: OcrResult.Success, historyText: String) {
        _uiState.value = _uiState.value.copy(result = result)
        val pendingId = savedHistoryId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = pendingId.await() ?: return@launch
                historyManager.updateItemText(id, historyText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "History text update failed", e)
            }
        }
    }

    fun clearResult() {
        processingJob?.cancel()
        processingJob = null
        savedHistoryId = null
        _uiState.value = OcrUiState()
    }
}
