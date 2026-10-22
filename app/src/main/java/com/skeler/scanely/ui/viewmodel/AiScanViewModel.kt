package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.ai.AiEvent
import com.skeler.scanely.core.ai.AiFeature
import com.skeler.scanely.core.ai.AiMode
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.AiRunInfo
import com.skeler.scanely.core.ai.AiStage
import com.skeler.scanely.core.ai.FeatureSwitches
import com.skeler.scanely.core.ai.GenerativeAiService
import com.skeler.scanely.core.ai.ScanExtraction
import com.skeler.scanely.core.ai.isOffered
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.ocr.PdfRendererHelper
import com.skeler.scanely.history.data.HistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AiScanViewModel"

data class AiScanState(
    val isProcessing: Boolean = false,
    val stage: AiStage? = null,
    val stageMessage: String? = null,
    val streamingText: String? = null,
    val result: AiResult? = null,
    val mode: AiMode? = null,
    val runInfo: AiRunInfo? = null,
    val originalText: String? = null,
    val translationCache: Map<String, String> = emptyMap(),
    val currentLanguage: String? = null,
    val isTranslating: Boolean = false,
    val lastImageUri: Uri? = null,
    val totalFiles: Int = 0,
    val currentFileIndex: Int = 0,
    val allUris: List<Uri> = emptyList(),
    val pageRange: String? = null,
    val extractPreset: String? = null,
    val message: String? = null,
    val translationUpgrade: AiResult.Error? = null
)

data class RescanParams(
    val uri: Uri,
    val mode: AiMode,
    val pageRange: String?,
    val extractPreset: String?
)

@HiltViewModel
class AiScanViewModel @Inject constructor(
    private val aiService: GenerativeAiService,
    private val historyManager: HistoryManager,
    private val entitlementRepository: EntitlementRepository,
    private val pdfRenderer: PdfRendererHelper,
    private val featureSwitches: FeatureSwitches
) : ViewModel() {

    private val _offeredFeatures = MutableStateFlow(AiFeature.entries.toSet())

    /** AI features whose entry points show: not switched off server-side, or served by a custom endpoint. */
    val offeredFeatures: StateFlow<Set<AiFeature>> = _offeredFeatures.asStateFlow()

    /** Re-reads the server's switches and the custom-endpoint setting; Home calls it on each visit. */
    fun refreshOfferedFeatures() {
        viewModelScope.launch {
            featureSwitches.refresh()
            val disabled = featureSwitches.disabled.value.orEmpty()
            val custom = usesCustomProvider()
            _offeredFeatures.update { AiFeature.entries.filterTo(mutableSetOf()) { it.isOffered(disabled, custom) } }
        }
    }

    private val _aiState = MutableStateFlow(AiScanState())
    val aiState: StateFlow<AiScanState> = _aiState.asStateFlow()

    private var processingJob: Job? = null
    private var translationJob: Job? = null

    private var savedHistoryId: Deferred<String?>? = null

    fun processImage(
        imageUri: Uri,
        mode: AiMode,
        pageRange: String? = null,
        extractPreset: String? = null
    ) {
        if (processingJob?.isActive == true) return
        translationJob?.cancel()
        processingJob = viewModelScope.launch {
            _aiState.value = AiScanState(
                isProcessing = true,
                stage = AiStage.PREPARING,
                mode = mode,
                lastImageUri = imageUri,
                pageRange = pageRange,
                extractPreset = extractPreset
            )

            val result = collectPipeline(imageUri, pageRange, extractPreset)
            publishFinalResult(result, imageUri)
        }
    }

    suspend fun usesCustomProvider(): Boolean = aiService.usesCustomProvider()

    suspend fun pdfPageCount(uri: Uri): Int = pdfRenderer.getPageCount(uri)

    private suspend fun collectPipeline(
        uri: Uri,
        pageRange: String? = null,
        extractPreset: String? = null
    ): AiResult = collect(aiService.processFileEvents(uri, pageRange, extractPreset))

    private suspend fun collect(events: Flow<AiEvent>): AiResult {
        var result: AiResult = AiResult.Error("Cancelled")
        events.catch { error ->
            if (error is CancellationException) throw error
            val message = if (error is SecurityException) {
                "File access expired. Select the file again."
            } else {
                "Couldn't finish the request. Please try again."
            }
            emit(AiEvent.Finished(AiResult.Error(message)))
        }.collect { event ->
            when (event) {
                is AiEvent.Stage -> _aiState.value = _aiState.value.copy(
                    stage = event.stage,
                    stageMessage = event.message
                )
                is AiEvent.Delta -> _aiState.value = _aiState.value.copy(
                    streamingText = event.textSoFar
                )
                is AiEvent.Finished -> {
                    result = event.result
                    event.runInfo?.let {
                        _aiState.value = _aiState.value.copy(runInfo = it)
                    }
                }
            }
        }
        return result
    }

    private fun publishFinalResult(result: AiResult, historyUri: Uri?) {
        _aiState.value = _aiState.value.copy(
            isProcessing = false,
            stage = null,
            stageMessage = null,
            streamingText = null,
            result = result,
            originalText = (result as? AiResult.Success)?.text
        )
        if (result is AiResult.Success && result.text.isNotBlank()) {
            saveToHistory(result.text, historyUri)
        }

        // Refresh server-authoritative quota after a scan.
        entitlementRepository.refresh()
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _aiState.value = _aiState.value.copy(
            isProcessing = false,
            stage = null,
            stageMessage = null,
            streamingText = null
        )
    }

    /** Page ranges apply only to single-file scans. */
    fun processMultipleFiles(
        uris: List<Uri>,
        mode: AiMode,
        pageRange: String? = null,
        extractPreset: String? = null
    ) {
        if (uris.isEmpty()) return

        val range = pageRange?.takeIf { uris.size == 1 }

        if (processingJob?.isActive == true) return
        translationJob?.cancel()
        processingJob = viewModelScope.launch {
            _aiState.value = AiScanState(
                isProcessing = true,
                stage = AiStage.PREPARING,
                mode = mode,
                totalFiles = uris.size,
                currentFileIndex = 1,
                allUris = uris,
                lastImageUri = uris.first(),
                pageRange = range,
                extractPreset = extractPreset
            )

            val results = mutableListOf<AiResult>()
            uris.forEachIndexed { index, uri ->
                _aiState.value = _aiState.value.copy(
                    currentFileIndex = index + 1,
                    lastImageUri = uri
                )

                results += collectPipeline(uri, range, extractPreset)
                _aiState.value = _aiState.value.copy(streamingText = null)
            }

            val combinedResult = combineBatchResults(results)
            publishFinalResult(combinedResult, uris.first())
        }
    }

    private fun saveToHistory(text: String, uri: Uri?) {
        savedHistoryId = viewModelScope.async(Dispatchers.IO) {
            try {
                historyManager.saveItem(text, uri?.toString().orEmpty()).id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "History save failed", e)
                null
            }
        }
    }

    fun updateText(newText: String) {
        val state = _aiState.value
        val language = state.currentLanguage
        if (language != null) {
            _aiState.value = state.copy(
                translationCache = state.translationCache + (language to newText)
            )
            return
        }
        // Preserve extracted figures and links; edits only replace text.
        val scan = state.result as? AiResult.Success
        _aiState.value = state.copy(
            result = AiResult.Success(
                newText,
                scan?.images.orEmpty(),
                scan?.links.orEmpty(),
                extraction = scan?.extraction
            ),
            originalText = newText
        )
        val pendingId = savedHistoryId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = pendingId.await() ?: return@launch
                historyManager.updateItemText(id, newText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "History text update failed", e)
            }
        }
    }

    fun consumeMessage() {
        _aiState.value = _aiState.value.copy(message = null)
    }

    fun consumeTranslationUpgrade() {
        _aiState.value = _aiState.value.copy(translationUpgrade = null)
    }

    fun getRescanParams(): RescanParams? {
        val currentState = _aiState.value
        val imageUri = currentState.lastImageUri ?: return null
        val mode = currentState.mode ?: return null
        return RescanParams(
            imageUri,
            mode,
            currentState.pageRange,
            currentState.extractPreset
        )
    }

    fun translateResult(targetLanguage: String) {
        if (translationJob?.isActive == true) return
        val currentText = _aiState.value.originalText
        if (currentText.isNullOrBlank()) {
            _aiState.value = _aiState.value.copy(message = "Nothing to translate yet.")
            return
        }

        translationJob = viewModelScope.launch {
            _aiState.value = _aiState.value.copy(isTranslating = true)

            val translationResult = aiService.translateText(currentText, targetLanguage)

            when (translationResult) {
                is AiResult.Success -> {
                    val updatedCache = _aiState.value.translationCache + (targetLanguage to translationResult.text)
                    _aiState.value = _aiState.value.copy(
                        isTranslating = false,
                        translationCache = updatedCache,
                        currentLanguage = targetLanguage
                    )
                }
                is AiResult.Error -> {
                    val upgrade = translationResult.takeIf { it.isUpgradePath }
                    _aiState.value = _aiState.value.copy(
                        isTranslating = false,
                        message = if (upgrade == null) translationResult.message else null,
                        translationUpgrade = upgrade
                    )
                }
            }
            entitlementRepository.refresh()
        }
    }

    fun selectCachedLanguage(language: String) {
        if (_aiState.value.translationCache.containsKey(language)) {
            _aiState.value = _aiState.value.copy(currentLanguage = language)
        }
    }

    fun showOriginal() {
        _aiState.value = _aiState.value.copy(currentLanguage = null)
    }

    fun clearResult() {
        processingJob?.cancel()
        translationJob?.cancel()
        savedHistoryId = null
        _aiState.value = AiScanState()
    }
}

/**
 * One result for a multi-file scan, in file order.
 *
 * When no file was read, the result is an error — the upgrade-path one if any file was refused
 * for the plan, so the paywall opens with the server's reason. It used to be a "success" whose
 * text was a list of error messages, saved to history, with the upgrade sheet never shown.
 */
internal fun combineBatchResults(results: List<AiResult>): AiResult {
    if (results.none { it is AiResult.Success }) {
        val errors = results.filterIsInstance<AiResult.Error>()
        return errors.firstOrNull { it.isUpgradePath }
            ?: errors.firstOrNull()
            ?: AiResult.Error("No text extracted from any file")
    }

    val sections = mutableListOf<String>()
    val allImages = mutableMapOf<String, String>()
    val allLinks = mutableListOf<String>()
    val allUnclear = mutableListOf<String>()
    var extraction: ScanExtraction? = null

    results.forEachIndexed { index, result ->
        val (label, body) = when (result) {
            is AiResult.Success -> {
                allImages.putAll(result.images)
                allLinks.addAll(result.links)
                allUnclear.addAll(result.unclear)
                if (extraction == null) extraction = result.extraction
                "" to result.text
            }
            is AiResult.Error -> " (Error)" to "Error: ${result.message}"
        }
        // Headed by position, not by how much has accumulated: a file that read as blank
        // used to swallow the next file's heading and mislabel everything after it.
        val heading = if (index == 0) "" else "--- File ${index + 1}$label ---\n\n"
        sections += heading + body
    }

    val combined = sections.joinToString("\n\n").trim()
    return if (combined.isNotEmpty()) {
        AiResult.Success(
            combined,
            allImages,
            allLinks.distinct(),
            allUnclear,
            extraction
        )
    } else {
        AiResult.Error("No text extracted from any file")
    }
}

/**
 * The text the results screen shows as the scan's output. Never an error: shown as "Extracted
 * Text" it offered Copy, Export and Edit, and an edit saved it as a successful document.
 */
internal fun aiDisplayText(result: AiResult?): String? = (result as? AiResult.Success)?.text
