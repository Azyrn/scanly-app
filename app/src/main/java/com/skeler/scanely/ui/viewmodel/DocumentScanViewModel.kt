package com.skeler.scanely.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.R
import com.skeler.scanely.core.image.ScanFilter
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdf.ScanPages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DocumentScanViewModel"
private const val MAX_DIMENSION = 2200
private const val QUARTER_TURN_DEGREES = 90f
private const val HALF_TURN_DEGREES = 180f
private const val THREE_QUARTER_TURN_DEGREES = 270f

enum class ScanExportFormat(val label: String, val mimeType: String) {
    PDF("PDF", ScanExporter.PDF_MIME_TYPE),
    WORD("Word", ScanExporter.WORD_MIME_TYPE)
}

data class ExportedScan(
    val uri: Uri,
    val format: ScanExportFormat
)

data class FilterPreview(
    val filter: ScanFilter,
    val bitmap: Bitmap
)

data class DocumentScanUiState(
    val pages: List<ReviewPage> = emptyList(),
    /** Display pictures by page id, only for the current page and its neighbours; the rest are not in memory. */
    val shown: Map<Long, Bitmap> = emptyMap(),
    /** Pages whose file can't be read (shown blank, left out of exports with a message). */
    val unreadable: Set<Long> = emptySet(),
    val filter: ScanFilter = ScanFilter.AUTO,
    val previews: List<FilterPreview> = emptyList(),
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val message: String? = null,
    val exportedScan: ExportedScan? = null,
    /** Something was exported from this review; until then leaving it would lose the scan (C9). */
    val exported: Boolean = false,
    /** Goes up whenever a picture leaves the state; the screen reports it back through [DocumentScanViewModel.drawn]. */
    val version: Long = 0
)

@HiltViewModel
class DocumentScanViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentScanUiState())
    val uiState: StateFlow<DocumentScanUiState> = _uiState.asStateFlow()

    private val ops = appContext.resources.displayMetrics.let {
        BitmapPageOps(
            appContext.contentResolver,
            max(it.widthPixels, it.heightPixels).coerceAtLeast(1),
            min(it.widthPixels, it.heightPixels).coerceAtLeast(1)
        )
    }
    private var window = PageWindow(ops)
    private var current = 0
    private var nextId = 0L

    /** One worker renders the window, so no picture is used by two jobs; requests made meanwhile merge. */
    private var syncRequests = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    /** Bumped by [release]: work started for pages that were released does not touch the state. */
    private var session = 0

    /** Review screens in composition; a released page is recycled only once this is 0. */
    private val screensShowing = MutableStateFlow(0)

    /** Straightened pages from the scan flow; [filter] is the mode's default look (Whiteboard for boards). */
    fun loadPages(uris: List<Uri>, filter: ScanFilter? = null) {
        if (uris.isEmpty()) return
        current = 0
        startWorker()
        _uiState.update { state ->
            DocumentScanUiState(pages = uris.map { ReviewPage(nextId++, it) }, filter = filter ?: state.filter)
        }
        syncRequests.trySend(Unit)
    }

    fun setFilter(filter: ScanFilter) {
        if (filter == _uiState.value.filter && _uiState.value.pages.isNotEmpty()) return
        _uiState.update { state ->
            state.copy(
                filter = filter,
                pages = state.pages.map { it.copy(turns = it.turns.refiltered()) },
                isLoading = true
            )
        }
        syncRequests.trySend(Unit)
    }

    /** The page the pager settled on: it and its neighbours are kept ready, the rest are let go. */
    fun showPage(index: Int) {
        current = index
        syncRequests.trySend(Unit)
    }

    fun rotatePage(index: Int) {
        _uiState.update { state ->
            if (index !in state.pages.indices) return@update state
            val turned = state.pages.toMutableList()
            turned[index] = turned[index].copy(turns = turned[index].turns.rotated())
            state.copy(pages = turned)
        }
        syncRequests.trySend(Unit)
    }

    fun deletePage(index: Int) {
        if (index !in _uiState.value.pages.indices) return
        _uiState.update { state -> state.copy(pages = state.pages.toMutableList().also { it.removeAt(index) }) }
        current = current.coerceAtMost((_uiState.value.pages.size - 1).coerceAtLeast(0))
        syncRequests.trySend(Unit)
    }

    /** The screen has composed state [version]: pictures that left the state before it can be recycled. */
    fun drawn(version: Long) = window.drawn(version)

    private fun startWorker() {
        // Normally release() already stopped the last review's worker; if not, its window goes with it.
        if (worker != null) retireWindow()
        val requests = Channel<Unit>(Channel.CONFLATED).also { syncRequests = it }
        val pageWindow = window
        worker = viewModelScope.launch {
            requests.consumeEach {
                pageWindow.sync({ WindowTarget(_uiState.value.pages, current, _uiState.value.filter) }) { view ->
                    _uiState.update { it.withView(view) }
                }
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun export(format: ScanExportFormat) {
        val state = _uiState.value
        if (state.pages.isEmpty() || state.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        val pages = exportPages(appContext.contentResolver, state)
        val started = session
        viewModelScope.launch {
            val result = runCatching {
                when (format) {
                    ScanExportFormat.PDF -> ScanExporter.exportPdf(appContext, pages, timestampName())
                    ScanExportFormat.WORD -> ScanExporter.exportWord(appContext, pages, timestampName())
                }
            }
            if (started != session) {
                result.exceptionOrNull()?.let { Log.e(TAG, "${format.label} export failed after review closed", it) }
                return@launch
            }
            _uiState.update {
                result.fold(
                    onSuccess = { export ->
                        it.copy(
                            isExporting = false,
                            exported = true,
                            exportedScan = ExportedScan(export.value, format),
                            message = appContext.getString(R.string.cmp_doc_saved_to_downloads, format.label) +
                                unreadableNote(appContext, export.unreadable)
                        )
                    },
                    onFailure = { e ->
                        Log.e(TAG, "${format.label} export failed", e)
                        it.copy(
                            isExporting = false,
                            message = appContext.getString(R.string.cmp_doc_couldnt_create, format.label)
                        )
                    }
                )
            }
        }
    }

    fun saveImages() {
        val state = _uiState.value
        if (state.pages.isEmpty() || state.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        val pages = exportPages(appContext.contentResolver, state)
        val started = session
        viewModelScope.launch {
            val export = runCatching {
                ScanExporter.saveImagesToGallery(appContext, pages, timestampName())
            }.getOrNull()
            if (started != session) return@launch
            val count = export?.value ?: 0
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exported = it.exported || count > 0,
                    message = if (count > 0) {
                        appContext.resources.getQuantityString(R.plurals.cmp_doc_saved_images, count, count) +
                            unreadableNote(appContext, export?.unreadable.orEmpty())
                    } else {
                        appContext.getString(R.string.cmp_doc_couldnt_save_images)
                    }
                )
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun consumeExport() = _uiState.update { it.copy(exportedScan = null) }

    /** A review screen entered ([shown]) or left composition; a rotation does both. */
    fun screenInComposition(shown: Boolean) = screensShowing.update { if (shown) it + 1 else it - 1 }

    /**
     * Review left the back stack (not a configuration change): the pages are dropped from the state now and
     * their pictures recycled once no review screen can draw them and the window's worker has stopped. An
     * export still running is not waited for: it reads the files and makes and recycles its own pages.
     */
    fun release() {
        session++
        retireWindow()
        current = 0
        _uiState.value = DocumentScanUiState()
    }

    /** Stops the worker and recycles its window's pictures once the worker is done and no screen draws them. */
    private fun retireWindow() {
        val busy = listOfNotNull(worker)
        worker?.cancel()
        worker = null
        val retired = window
        window = PageWindow(ops)
        viewModelScope.launch { recycleWhenUnused({ retired.takeAll() }, busy, screensShowing) { it.recycle() } }
    }
}

/** Export makes its own full-quality pages, one at a time, from the files and the edits. */
private fun exportPages(resolver: ContentResolver, state: DocumentScanUiState): ScanPages {
    val pages = state.pages
    val filter = state.filter
    return ScanPages(pages.size) { index -> renderForExport(resolver, pages[index], filter) }
}

/** A second line for an export message naming how many pages were left out; empty when none were. */
private fun unreadableNote(context: Context, unreadable: List<Int>): String =
    if (unreadable.isEmpty()) {
        ""
    } else {
        "\n" + context.resources.getQuantityString(
            R.plurals.scr_scan_review_pages_unreadable,
            unreadable.size,
            unreadable.size
        )
    }

private fun DocumentScanUiState.withView(view: WindowView<Bitmap>) = copy(
    shown = view.shown,
    unreadable = view.unreadable,
    previews = view.previews.map { (filter, bitmap) -> FilterPreview(filter, bitmap) },
    version = view.version
)

/**
 * Recycles each of [items] (taken only after the wait) once (the same bitmap may be both a page and a preview) after every job in [busy]
 * (an export, a filter run) has finished and [screensShowing] has dropped to 0: a recycled bitmap must never
 * be drawn, filtered or written again.
 */
internal suspend fun <T : Any> recycleWhenUnused(
    items: () -> Collection<T>,
    busy: List<Job>,
    screensShowing: StateFlow<Int>,
    recycle: (T) -> Unit
) {
    busy.forEach { it.join() }
    screensShowing.first { it <= 0 }
    val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    items().forEach { if (seen.add(it)) recycle(it) }
}

/** Decodes a picked or scanned image upright (EXIF), at most 2200 px on its longest side. */
internal fun decodeScaled(resolver: ContentResolver, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longest / sample > MAX_DIMENSION) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error("Unable to decode $uri")
    return capDimension(applyExifRotation(resolver, uri, bitmap))
}

// Enforce the final cap after BitmapFactory's power-of-two sampling.
private fun capDimension(bitmap: Bitmap): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= MAX_DIMENSION) return bitmap
    val scale = MAX_DIMENSION.toFloat() / longest
    val resized = bitmap.scale(
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1)
    )
    if (resized != bitmap) bitmap.recycle()
    return resized
}

// Photos must be upright (EXIF applied) before page detection and filters.
private fun applyExifRotation(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            rotationDegrees(
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            )
        } ?: 0f
    }.getOrDefault(0f)
    if (degrees == 0f) return bitmap

    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}

private fun timestampName(): String =
    "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

/** The clockwise turn an EXIF orientation asks for; mirrored orientations are left as they are. */
private fun rotationDegrees(orientation: Int): Float = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER_TURN_DEGREES
    ExifInterface.ORIENTATION_ROTATE_180 -> HALF_TURN_DEGREES
    ExifInterface.ORIENTATION_ROTATE_270 -> THREE_QUARTER_TURN_DEGREES
    else -> 0f
}
