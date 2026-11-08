package com.skeler.scanely.ui.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.R
import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.image.ScanFilter
import com.skeler.scanely.core.scan.ScanMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** One confirmed page: the straightened file, and the photo and outline it came from. */
data class ScanPage(
    val output: Uri,
    val source: Uri,
    val quad: NormQuad,
    val quarterTurns: Int,
    val thumbnail: Bitmap
)

/** The photo on the crop screen. [photo] is null while it loads; [quad] and [detected] are on the turned photo. */
data class CropSession(
    val source: Uri,
    val photo: Bitmap? = null,
    val quarterTurns: Int = 0,
    val detected: NormQuad? = null,
    val quad: NormQuad = NormQuad.FULL
) {
    /** Corner [index] dragged to [to]; unchanged when that would break the outline's convexity. */
    fun movedCorner(index: Int, to: NormPoint): CropSession =
        quad.withCorner(index, to)?.let { copy(quad = it) } ?: this

    /** Back to what detection found; the whole photo when it found nothing. */
    fun auto(): CropSession = copy(quad = detected ?: NormQuad.FULL)

    fun fullImage(): CropSession = copy(quad = NormQuad.FULL)

    /** The photo turned a quarter clockwise as [turned], with its outlines turned along. */
    fun turned(turned: Bitmap): CropSession = copy(
        photo = turned,
        quarterTurns = (quarterTurns + 1) % FULL_TURN,
        detected = detected?.turnedClockwise(),
        quad = quad.turnedClockwise()
    )

    private companion object {
        const val FULL_TURN = 4
    }
}

/** Where a finished session hands its pages. */
sealed interface ScanResult {
    data class Review(val pages: List<Uri>, val filter: ScanFilter) : ScanResult
    data class IdCopy(val kind: IdDocumentKind, val sides: List<Uri>) : ScanResult
    data class ReplaceSide(val index: Int, val side: Uri) : ScanResult
}

sealed interface ScanEvent {
    /** The crop is confirmed and the camera takes the next page. */
    data object BackToCamera : ScanEvent
    data class Finished(val result: ScanResult) : ScanEvent

    /** Something to tell the user (a short toast). */
    data class Message(@param:StringRes val text: Int) : ScanEvent
}

data class ScanSessionUiState(
    val goal: ScanGoal = ScanGoal.Pages(ScanMode.DOCUMENT),
    /** Card or passport, remembered while the user is in another mode. */
    val idKind: IdDocumentKind = IdDocumentKind.ID_CARD,
    val pages: List<ScanPage> = emptyList(),
    val sides: List<ScanPage> = emptyList(),
    val crop: CropSession? = null,
    val imports: List<Uri> = emptyList(),
    val working: Boolean = false,
    val event: ScanEvent? = null
) {
    val mode: ScanMode get() = (goal as? ScanGoal.Pages)?.mode ?: ScanMode.ID_CARD

    /** The ID side being taken; null outside ID mode. */
    val side: IdSide?
        get() = when (goal) {
            is ScanGoal.Pages -> null
            is ScanGoal.IdCopy -> IdSide.of(goal.kind, sides.size)
            is ScanGoal.ReplaceSide -> goal.side
        }

    /** The pages the camera's page stack shows. */
    val stack: List<ScanPage> get() = if (goal is ScanGoal.Pages) pages else sides

    val cropActions: CropActions get() = cropActions(goal, side)

    /** How many photos the gallery may hand over now. */
    val importLimit: Int
        get() = when (goal) {
            is ScanGoal.Pages -> MAX_IMPORT
            is ScanGoal.IdCopy -> goal.kind.sides - sides.size
            is ScanGoal.ReplaceSide -> 1
        }

    /** Where the session goes with what it has, [last] being the page just confirmed. */
    fun resultWith(last: ScanPage): ScanResult = when (goal) {
        is ScanGoal.Pages -> ScanResult.Review(pages.map { it.output }, goal.mode.defaultFilter)
        is ScanGoal.IdCopy -> ScanResult.IdCopy(goal.kind, sides.map { it.output })
        is ScanGoal.ReplaceSide -> ScanResult.ReplaceSide(goal.side.index, last.output)
    }

    private companion object {
        const val MAX_IMPORT = 20
    }
}

/**
 * One scan session: camera → crop → (camera …) → review or ID copy. Activity-scoped, so the camera and crop
 * screens share it and it outlives a rotation; [begin] makes reopening the same screen a no-op.
 */
@HiltViewModel
class ScanSessionViewModel @Inject constructor(val files: ScanPageStore) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanSessionUiState())
    val uiState: StateFlow<ScanSessionUiState> = _uiState.asStateFlow()

    private var sessionKey: String? = null
    private var cropJob: Job? = null

    /**
     * Starts a session for the camera screen [key] (its back-stack entry). Calling it again with the same key —
     * after a rotation, or coming back from the crop — keeps the session.
     */
    fun begin(key: String, mode: ScanMode?, kind: IdDocumentKind?, replaceSide: Int?) {
        if (key == sessionKey) return
        sessionKey = key
        cropJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { files.deleteAll() }
        val idKind = kind ?: IdDocumentKind.ID_CARD
        val replace = replaceSide?.let { IdSide.of(idKind, it) }
        val goal = when {
            replace != null -> ScanGoal.ReplaceSide(replace)
            mode == ScanMode.ID_CARD -> ScanGoal.IdCopy(idKind)
            else -> ScanGoal.Pages(mode ?: ScanMode.DOCUMENT)
        }
        _uiState.value = ScanSessionUiState(goal = goal, idKind = idKind)
    }

    /** The camera screen left for good. Files stay until the next session: review may still be reading them. */
    fun end() {
        sessionKey = null
        cropJob?.cancel()
        _uiState.value = ScanSessionUiState()
    }

    fun setMode(mode: ScanMode) = _uiState.update {
        if (it.goal is ScanGoal.ReplaceSide || it.mode == mode) return@update it
        it.copy(goal = if (mode == ScanMode.ID_CARD) ScanGoal.IdCopy(it.idKind) else ScanGoal.Pages(mode))
    }

    /** Card or passport. A different document starts its sides again: a card's front is no passport page. */
    fun setKind(kind: IdDocumentKind) = _uiState.update {
        if (it.goal !is ScanGoal.IdCopy || it.goal.kind == kind) return@update it
        it.copy(goal = ScanGoal.IdCopy(kind), idKind = kind, sides = emptyList())
    }

    /** Photos from the camera (one) or the gallery (several); each goes through the crop in turn. */
    fun openPhotos(uris: List<Uri>) {
        val first = uris.firstOrNull() ?: return
        val limit = _uiState.value.importLimit
        _uiState.update { it.copy(imports = uris.drop(1).take((limit - 1).coerceAtLeast(0)), event = null) }
        openCrop(first)
    }

    private fun openCrop(source: Uri) {
        cropJob?.cancel()
        _uiState.update { it.copy(crop = CropSession(source)) }
        cropJob = viewModelScope.launch {
            val photo = files.decode(source)
            if (photo == null) {
                _uiState.update {
                    it.copy(crop = null, imports = emptyList(), event = ScanEvent.Message(R.string.id_scan_unreadable))
                }
                return@launch
            }
            val detected = withContext(Dispatchers.Default) { CoreScanEngine.detectStill(photo) }
            _uiState.update { state ->
                val crop = state.crop?.takeIf { it.source == source } ?: return@update state
                state.copy(crop = crop.copy(photo = photo, detected = detected, quad = detected ?: NormQuad.FULL))
            }
        }
    }

    /** Applies a corner drag or tool ([CropSession.movedCorner], [CropSession.auto], ...) to the shown photo. */
    fun edit(change: (CropSession) -> CropSession) = _uiState.update { state ->
        val crop = state.crop?.takeIf { it.photo != null && !state.working } ?: return@update state
        state.copy(crop = change(crop))
    }

    /** Turns the photo and its outline a quarter clockwise together. */
    fun rotate() {
        val state = _uiState.value
        val crop = state.crop ?: return
        val photo = crop.photo ?: return
        if (state.working) return
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val turned = withContext(Dispatchers.Default) {
                val matrix = Matrix().apply { postRotate(QUARTER) }
                Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, matrix, true)
            }
            _uiState.update { now ->
                val current = now.crop?.takeIf { it.source == crop.source }
                now.copy(working = false, crop = current?.turned(turned) ?: now.crop)
            }
        }
    }

    /** Drops the photo on the crop screen (Retake, or leaving it). */
    fun dropCrop() {
        cropJob?.cancel()
        val crop = _uiState.value.crop ?: return
        _uiState.update { it.copy(crop = null, imports = emptyList(), working = false) }
        viewModelScope.launch(Dispatchers.IO) { files.deleteIfCapture(crop.source) }
    }

    /** Confirms the crop: Add page / Next: back ([done] false) or Done / Make copy ([done] true). */
    fun confirm(done: Boolean) {
        val state = _uiState.value
        val crop = state.crop ?: return
        val photo = crop.photo ?: return
        if (state.working) return
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val page = try {
                files.writePage(crop, photo, state.goal)
            } catch (e: IOException) {
                Log.e(TAG, "Couldn't save the page", e)
                null
            }
            if (page == null) {
                val failed = ScanEvent.Message(R.string.scr_camera_capture_failed)
                _uiState.update { it.copy(working = false, event = failed) }
                return@launch
            }
            withContext(Dispatchers.IO) { files.deleteIfCapture(crop.source) }
            afterConfirm(page, done)
        }
    }

    private suspend fun afterConfirm(page: ScanPage, done: Boolean) {
        val state = _uiState.value
        val added = when (state.goal) {
            is ScanGoal.Pages -> state.copy(pages = state.pages + page)
            is ScanGoal.IdCopy -> state.copy(sides = state.sides + page)
            is ScanGoal.ReplaceSide -> state
        }
        when (stepAfterCrop(state.goal, state.sides.size, state.imports.size, done)) {
            CropStep.Camera ->
                _uiState.value = added.copy(working = false, crop = null, event = ScanEvent.BackToCamera)
            CropStep.NextImport -> {
                _uiState.value = added.copy(working = false, imports = state.imports.drop(1))
                openCrop(state.imports.first())
            }
            CropStep.Finish -> {
                val goal = state.goal
                val rest = if (goal is ScanGoal.Pages) files.autoCropAll(state.imports, goal) else emptyList()
                val finished = added.copy(pages = added.pages + rest, imports = emptyList())
                val event = ScanEvent.Finished(finished.resultWith(page))
                _uiState.value = finished.copy(working = false, event = event)
            }
        }
    }

    /** The page stack's "review": finish with what has been taken so far. */
    fun finishNow() {
        val state = _uiState.value
        val last = state.stack.lastOrNull() ?: return
        if (state.working || state.goal is ScanGoal.ReplaceSide) return
        _uiState.update { it.copy(event = ScanEvent.Finished(state.resultWith(last))) }
    }

    fun consumeEvent() = _uiState.update { it.copy(event = null) }

    override fun onCleared() {
        files.deleteAll()
    }

    private companion object {
        const val TAG = "ScanSession"
        const val QUARTER = 90f
    }
}
