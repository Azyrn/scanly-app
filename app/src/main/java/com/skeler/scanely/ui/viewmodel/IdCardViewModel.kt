package com.skeler.scanely.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.R
import com.skeler.scanely.core.idcard.CopyLook
import com.skeler.scanely.core.idcard.IdCardLayout
import com.skeler.scanely.core.idcard.IdCardRenderer
import com.skeler.scanely.core.idcard.IdCopy
import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.pdf.ScanExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/** Something finished that the screen hands to another app: open the saved PDF, share or print the copy. */
sealed interface IdCopyEvent {
    data class Saved(val uri: Uri, val mimeType: String) : IdCopyEvent
    data class Share(val file: File) : IdCopyEvent
    data class Print(val file: File) : IdCopyEvent
}

data class IdCardUiState(
    val kind: IdDocumentKind = IdDocumentKind.ID_CARD,
    val sides: List<Bitmap> = emptyList(),
    val look: CopyLook = CopyLook.COLOUR,
    val watermark: String = "",
    /** 1 = true size; one of [IdCardLayout.sizeSteps] for [kind]. */
    val scale: Float = 1f,
    val preview: Bitmap? = null,
    val isLoading: Boolean = false,
    val isWorking: Boolean = false,
    /** A side whose shape was too far from a card to stretch; the screen suggests a retake. */
    val offShapeSide: Int? = null,
    val message: String? = null,
    val event: IdCopyEvent? = null
) {
    val missingBack: Boolean get() = kind.sides == 2 && sides.size == 1
}

/**
 * The ID copy: scanned sides, look and watermark → an A4 page at true size. Nothing here is written to
 * History; the only files are the user's own saves and one temp PDF for share/print, deleted on [clear].
 */
@Suppress("TooManyFunctions") // one small action per control on the screen, like the other screen ViewModels
@HiltViewModel
class IdCardViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(IdCardUiState())
    val uiState: StateFlow<IdCardUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private val tempPdf get() = File(File(appContext.cacheDir, "scans"), "id_copy.pdf")

    /** A fresh copy from the scanner. Extra scans beyond the document's sides are dropped, with a note. */
    fun start(kind: IdDocumentKind, uris: List<Uri>) {
        _uiState.value = IdCardUiState(kind = kind, isLoading = true)
        viewModelScope.launch {
            val decoded = decode(uris.take(kind.sides))
            val note = if (uris.size > kind.sides) appContext.getString(R.string.id_extra_scans_ignored) else null
            _uiState.update { it.copy(sides = decoded, isLoading = false, message = note ?: it.message) }
            if (decoded.isEmpty()) {
                _uiState.update { it.copy(message = appContext.getString(R.string.id_scan_unreadable)) }
            }
            render(immediate = true)
        }
    }

    /** Replaces side [index] (retake), or appends it when [index] is the next side (add the back). */
    fun replaceSide(index: Int, uri: Uri) {
        viewModelScope.launch {
            val bitmap = decode(listOf(uri)).firstOrNull() ?: run {
                _uiState.update { it.copy(message = appContext.getString(R.string.id_scan_unreadable)) }
                return@launch
            }
            _uiState.update {
                val sides = it.sides.toMutableList()
                if (index < sides.size) sides[index] = bitmap else sides += bitmap
                it.copy(sides = sides)
            }
            render(immediate = true)
        }
    }

    fun rotateSide(index: Int) {
        val side = _uiState.value.sides.getOrNull(index) ?: return
        viewModelScope.launch {
            val turned = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(side, 0, 0, side.width, side.height, Matrix().apply { postRotate(90f) }, true)
            }
            _uiState.update { s -> s.copy(sides = s.sides.toMutableList().also { it[index] = turned }) }
            render(immediate = true)
        }
    }

    fun swapSides() {
        if (_uiState.value.sides.size < 2) return
        _uiState.update { it.copy(sides = it.sides.reversed()) }
        render(immediate = true)
    }

    fun setLook(look: CopyLook) {
        if (look == _uiState.value.look) return
        _uiState.update { it.copy(look = look) }
        render(immediate = true)
    }

    fun setScale(scale: Float) {
        if (scale == _uiState.value.scale) return
        _uiState.update { it.copy(scale = scale) }
        render(immediate = true)
    }

    /** Typing re-renders after a short pause, so the preview follows without stuttering the keyboard. */
    fun setWatermark(text: String) {
        _uiState.update { it.copy(watermark = text.take(MAX_WATERMARK)) }
        render(immediate = false)
    }

    private fun copy(state: IdCardUiState = _uiState.value) =
        IdCopy(state.kind, state.sides, state.look, state.watermark, state.scale)

    private fun render(immediate: Boolean) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(TYPING_PAUSE_MS)
            val state = _uiState.value
            if (state.sides.isEmpty()) {
                _uiState.update { it.copy(preview = null) }
                return@launch
            }
            val page = withContext(Dispatchers.Default) { IdCardRenderer.renderPage(copy(state), PREVIEW_DPI) }
            val placements = IdCardLayout.layout(state.kind, state.sides.map { it.width to it.height }, state.scale)
            val offShape = placements.indexOfFirst { !it.snapped }.takeIf { it >= 0 }
            _uiState.update { it.copy(preview = page, offShapeSide = offShape) }
        }
    }

    fun savePdf() = work { name ->
        val file = writeTempPdf()
        val uri = ScanExporter.exportPdfFile(appContext, file, name)
        _uiState.update {
            it.copy(
                event = IdCopyEvent.Saved(uri, ScanExporter.PDF_MIME_TYPE),
                message = appContext.getString(R.string.id_saved_pdf)
            )
        }
    }

    fun saveImage() = work { name ->
        val page = withContext(Dispatchers.Default) { IdCardRenderer.renderPage(copy()) }
        val saved = ScanExporter.saveImagesToGallery(appContext, listOf(page), name)
        page.recycle()
        val message = if (saved > 0) R.string.id_saved_image else R.string.id_save_failed
        _uiState.update { it.copy(message = appContext.getString(message)) }
    }

    fun share() = work { _ -> _uiState.update { it.copy(event = IdCopyEvent.Share(writeTempPdf())) } }

    fun print() = work { _ -> _uiState.update { it.copy(event = IdCopyEvent.Print(writeTempPdf())) } }

    private suspend fun writeTempPdf(): File = withContext(Dispatchers.IO) {
        val file = tempPdf
        file.parentFile?.mkdirs()
        file.outputStream().use { IdCardRenderer.writePdf(copy(), it) }
        file
    }

    private fun work(block: suspend (baseName: String) -> Unit) {
        if (_uiState.value.isWorking || _uiState.value.sides.isEmpty()) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                block(fileName())
            } catch (e: java.io.IOException) {
                Log.e(TAG, "ID copy export failed", e)
                _uiState.update { it.copy(message = appContext.getString(R.string.id_save_failed)) }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "ID copy export failed", e)
                _uiState.update { it.copy(message = appContext.getString(R.string.id_save_failed)) }
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    private suspend fun decode(uris: List<Uri>): List<Bitmap> = withContext(Dispatchers.IO) {
        uris.mapNotNull { runCatching { decodeScaled(appContext.contentResolver, it) }.getOrNull() }
    }

    private fun fileName(): String {
        val prefix = if (_uiState.value.kind == IdDocumentKind.PASSPORT) "Passport_copy_" else "ID_copy_"
        return prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun consumeEvent() = _uiState.update { it.copy(event = null) }

    /** Leaving the screen: drop the scans from memory and the temp PDF from disk. */
    fun clear() {
        previewJob?.cancel()
        _uiState.value = IdCardUiState()
        runCatching { tempPdf.delete() }
    }

    override fun onCleared() {
        runCatching { tempPdf.delete() }
    }

    private companion object {
        const val TAG = "IdCardViewModel"
        const val PREVIEW_DPI = 110
        const val TYPING_PAUSE_MS = 180L
        const val MAX_WATERMARK = 60
    }
}
