package com.skeler.scanely.ui.pdfeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.PdfEditorSession
import com.skeler.scanely.core.pdfeditor.PdfEntitlementGate
import com.skeler.scanely.core.pdfeditor.PdfOpenResult
import com.skeler.scanely.core.pdfeditor.canSave
import com.skeler.scanely.core.pdfeditor.engine.PdfEditResult
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.signature.SignatureValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/** A rendered page tile, tagged with the width it was rasterised at so the grid can ask for more. */
data class PageThumbnail(val bitmap: Bitmap, val widthPx: Int)

data class PdfStudioUiState(
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val pages: List<StudioPage> = emptyList(),
    val selection: Set<Int> = emptySet(),
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val isDirty: Boolean = false,
    /** Staged edits, or a saved file that still has to reach Downloads. */
    val canSave: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isPro: Boolean = false,
    val needsPassword: Boolean = false,
    val passwordError: Boolean = false,
    val message: String? = null,
    val savedUri: Uri? = null,
    /** Set when saving would invalidate signatures the document already carries. */
    val signaturesAtRisk: Int = 0,
    val isClosed: Boolean = false,
    /** A page tool to open as soon as the document is ready, set by a tools-grid entry. */
    val pendingTool: EditorTool? = null
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
}

@HiltViewModel
class PdfStudioViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val session: PdfEditorSession,
    private val gate: PdfEntitlementGate,
    private val validator: SignatureValidator
) : ViewModel() {

    private val exportRetry get() = session.exportRetry

    private val _uiState = MutableStateFlow(PdfStudioUiState())
    val uiState: StateFlow<PdfStudioUiState> = _uiState.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<Int, PageThumbnail>>(emptyMap())
    val thumbnails: StateFlow<Map<Int, PageThumbnail>> = _thumbnails.asStateFlow()

    private val pending = mutableMapOf<Int, Int>()

    private val thumbnailBudgetBytes =
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

    init {
        viewModelScope.launch {
            combine(session.document, session.edits.stack, gate.isPro) { document, stack, isPro ->
                Triple(document, stack, isPro)
            }.collect { (document, stack, isPro) ->
                _uiState.update { state ->
                    if (document == null) {
                        state.copy(isPro = isPro)
                    } else {
                        state.copy(
                            displayName = document.displayName,
                            sizeBytes = document.sizeBytes,
                            pages = deriveStudioPages(
                                document.pageCount,
                                stack.applied,
                                document.geometries
                            ),
                            isDirty = stack.isDirty,
                            canSave = canSave(stack.isDirty, exportRetry),
                            canUndo = stack.canUndo,
                            canRedo = stack.canRedo,
                            isPro = isPro,
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    fun open(uri: Uri, startTool: EditorTool? = null) {
        _uiState.value = PdfStudioUiState(isLoading = true, pendingTool = startTool)
        _thumbnails.value = emptyMap()
        pending.clear()
        viewModelScope.launch { handle(session.open(uri)) }
    }

    fun unlock(password: String) {
        _uiState.update { it.copy(isLoading = true, passwordError = false) }
        viewModelScope.launch { handle(session.unlock(password), fromPasswordPrompt = true) }
    }

    fun close() {
        session.close()
        _thumbnails.value = emptyMap()
        pending.clear()
        _uiState.value = PdfStudioUiState(isClosed = true)
    }

    /**
     * [tileWidthPx] is the tile's real size on screen: a thumbnail rasterised below it is upscaled
     * by the Image, which is what made previews look soft. Widths are quantised so a few pixels of
     * layout drift don't re-render the whole grid.
     */
    fun requestThumbnail(originalIndex: Int, tileWidthPx: Int) {
        if (tileWidthPx <= 0) return
        val width = thumbnailWidthFor(tileWidthPx)
        if ((_thumbnails.value[originalIndex]?.widthPx ?: 0) >= width) return
        if ((pending[originalIndex] ?: 0) >= width) return

        pending[originalIndex] = width
        viewModelScope.launch {
            val bitmap = session.renderPage(originalIndex, width)
            if (pending[originalIndex] == width) pending.remove(originalIndex)
            if (bitmap != null) {
                _thumbnails.update { current ->
                    withinBudget(current + (originalIndex to PageThumbnail(bitmap, width)))
                }
            }
        }
    }

    private fun thumbnailWidthFor(tileWidthPx: Int): Int {
        val stepped = ((tileWidthPx + THUMBNAIL_STEP_PX - 1) / THUMBNAIL_STEP_PX) * THUMBNAIL_STEP_PX
        return stepped.coerceIn(THUMBNAIL_STEP_PX, MAX_THUMBNAIL_WIDTH_PX)
    }

    /** Drops the least recently rendered tiles once full-resolution bitmaps outgrow the heap slice. */
    private fun withinBudget(thumbnails: Map<Int, PageThumbnail>): Map<Int, PageThumbnail> {
        var total = thumbnails.values.sumOf { it.bitmap.allocationByteCount.toLong() }
        if (total <= thumbnailBudgetBytes) return thumbnails

        val trimmed = LinkedHashMap(thumbnails)
        val iterator = trimmed.entries.iterator()
        while (iterator.hasNext() && total > thumbnailBudgetBytes && trimmed.size > MIN_THUMBNAILS) {
            total -= iterator.next().value.bitmap.allocationByteCount
            iterator.remove()
        }
        return trimmed
    }

    suspend fun renderPage(originalIndex: Int, widthPx: Int): Bitmap? =
        session.renderPage(originalIndex, widthPx)

    fun toggleSelection(originalIndex: Int) = _uiState.update { state ->
        state.copy(
            selection = if (originalIndex in state.selection) {
                state.selection - originalIndex
            } else {
                state.selection + originalIndex
            }
        )
    }

    fun clearSelection() = _uiState.update { it.copy(selection = emptySet()) }

    fun selectAll() = _uiState.update { state ->
        state.copy(selection = state.pages.map { it.originalIndex }.toSet())
    }

    fun deleteSelected() {
        val state = _uiState.value
        if (state.selection.isEmpty()) return
        if (state.selection.size >= state.pages.size) {
            _uiState.update { it.copy(message = "A document needs at least one page.") }
            return
        }
        session.edits.push(PdfEdit.DeletePages(state.selection))
        clearSelection()
    }

    fun rotateSelected(degrees: Int) {
        val selection = _uiState.value.selection
        if (selection.isEmpty()) return
        selection.sorted().forEach { session.edits.push(PdfEdit.RotatePage(it, degrees)) }
    }

    /** [fraction] is the kept region as a 0..1 box, so one drag can crop pages of differing size. */
    fun cropSelected(fraction: Rect) {
        val state = _uiState.value
        if (state.selection.isEmpty()) return
        state.pages
            .filter { it.originalIndex in state.selection }
            .forEach { page ->
                session.edits.push(
                    PdfEdit.CropPage(
                        pageIndex = page.originalIndex,
                        box = Rect(
                            fraction.left * page.widthPt,
                            fraction.top * page.heightPt,
                            fraction.right * page.widthPt,
                            fraction.bottom * page.heightPt
                        )
                    )
                )
            }
        clearSelection()
    }

    fun extractSelected() {
        val state = _uiState.value
        val selection = state.pages.map { it.originalIndex }.filter { it in state.selection }
        if (selection.isEmpty() || state.isBusy) return

        busy("Extracting") {
            val extracted = session.extractPages(selection)
            if (extracted == null) {
                fail("Couldn't extract those pages.")
            } else {
                // Extraction reads the file on disk, so anything still staged isn't in it yet.
                val note = if (state.isDirty) " — staged edits not included" else ""
                publish(extracted, "${baseName()}_pages", "Extracted ${selection.size} pages$note")
                clearSelection()
            }
        }
    }

    fun reorder(originalOrder: List<Int>) {
        if (originalOrder == _uiState.value.pages.map { it.originalIndex }) return
        session.edits.push(PdfEdit.ReorderPages(originalOrder))
    }

    fun undo() = session.edits.undo()

    fun redo() = session.edits.redo()

    /**
     * Checks for signatures before saving, because applying edits rewrites the file through qpdf
     * and every signature already in it stops verifying. That is not recoverable once written, so
     * it is worth one confirmation rather than a note afterwards.
     */
    fun save() {
        val state = _uiState.value
        if (!state.canSave || state.isBusy) return

        // Nothing staged, but the last save never reached Downloads: export it again.
        val unexported = exportRetry.unexported
        if (!state.isDirty && unexported != null) {
            busy("Saving") { publish(unexported, baseName(), "Saved to Downloads/Scanly", retryable = true) }
            return
        }

        val document = session.document.value
        if (document == null) {
            saveConfirmed()
            return
        }

        busy("Checking signatures") {
            val existing = validator.validate(document.file, document.password)
            if (existing.isEmpty()) {
                saveConfirmed()
            } else {
                _uiState.update { it.copy(signaturesAtRisk = existing.size) }
            }
        }
    }

    fun dismissSignatureWarning() = _uiState.update { it.copy(signaturesAtRisk = 0) }

    fun saveConfirmed() {
        val state = _uiState.value
        if (!state.isDirty) return
        _uiState.update { it.copy(signaturesAtRisk = 0) }

        busy("Saving") {
            val structural = session.edits.plan().structural
            when (val result = session.applyEdits()) {
                is PdfEditResult.Saved -> {
                    publish(result.file, baseName(), "Saved to Downloads/Scanly", retryable = true)
                    session.adopt(result.file, passwordAfter(structural))
                    _thumbnails.value = emptyMap()
                    pending.clear()
                }
                is PdfEditResult.WrongPassword ->
                    _uiState.update { it.copy(needsPassword = true, passwordError = true) }
                is PdfEditResult.NothingToDo -> fail("Nothing to save yet.")
                is PdfEditResult.Failed -> fail(result.message)
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun consumeSaved() = _uiState.update { it.copy(savedUri = null) }

    fun consumePendingTool() = _uiState.update { it.copy(pendingTool = null) }

    /**
     * Writes [file] to Downloads. A [retryable] save is remembered by [exportRetry] when the write
     * fails, so Save stays enabled and tapping it tries again.
     */
    private suspend fun publish(
        file: File,
        baseName: String,
        successMessage: String,
        retryable: Boolean = false
    ) {
        val write: suspend (File) -> Uri? = { ScanExporter.exportPdfFile(appContext, it, baseName) }
        val uri = if (retryable) {
            exportRetry.export(file, write)
        } else {
            runCatching { write(file) }.getOrNull()
        }
        _uiState.update {
            val next = it.copy(canSave = canSave(it.isDirty, exportRetry))
            when {
                uri != null -> next.copy(message = successMessage, savedUri = uri)
                retryable -> next.copy(message = "Couldn't write to Downloads. Tap Save to try again.")
                else -> next.copy(message = "Couldn't write to Downloads.")
            }
        }
    }

    /**
     * Staging an encrypt or a decrypt changes what opens the saved file, so the session has to
     * continue with the new password or the very next render fails.
     */
    private fun passwordAfter(structural: List<PdfEdit.Structural>): String {
        structural.filterIsInstance<PdfEdit.Encrypt>().lastOrNull()?.let { return it.userPassword }
        if (structural.any { it is PdfEdit.Decrypt }) return ""
        return session.document.value?.password.orEmpty()
    }

    private fun handle(result: PdfOpenResult, fromPasswordPrompt: Boolean = false) {
        _uiState.update { state ->
            when (result) {
                is PdfOpenResult.Opened ->
                    state.copy(isLoading = false, needsPassword = false, passwordError = false)
                is PdfOpenResult.NeedsPassword -> state.copy(
                    isLoading = false,
                    needsPassword = true,
                    passwordError = fromPasswordPrompt
                )
                is PdfOpenResult.Failed ->
                    state.copy(isLoading = false, message = result.message, isClosed = true)
            }
        }
    }

    private fun busy(label: String, block: suspend () -> Unit) {
        _uiState.update { it.copy(isBusy = true, busyLabel = label) }
        viewModelScope.launch {
            block()
            _uiState.update { it.copy(isBusy = false, busyLabel = "") }
        }
    }

    private fun fail(message: String) = _uiState.update { it.copy(message = message) }

    private fun baseName(): String {
        val name = _uiState.value.displayName.substringBeforeLast('.', "")
            .ifBlank { "Document" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "${name}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
    }

    private companion object {
        const val THUMBNAIL_STEP_PX = 64
        const val MAX_THUMBNAIL_WIDTH_PX = 1080

        // A floor well above a screenful, so eviction can never fight the tiles still on screen.
        const val MIN_THUMBNAILS = 24
        const val MIN_BUDGET_BYTES = 24L * 1024 * 1024
        const val MAX_BUDGET_BYTES = 96L * 1024 * 1024
    }
}
