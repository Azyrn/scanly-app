package com.skeler.scanely.ui.pdfeditor

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.pdfeditor.PdfEditorSession
import com.skeler.scanely.core.pdfeditor.PdfEntitlementGate
import com.skeler.scanely.core.pdfeditor.PdfFeature
import com.skeler.scanely.core.pdfeditor.engine.DetectedFont
import com.skeler.scanely.core.pdfeditor.engine.FontMatcher
import com.skeler.scanely.core.pdfeditor.engine.PageTextSnapshot
import com.skeler.scanely.core.pdfeditor.engine.TextSelection
import com.skeler.scanely.core.pdfeditor.model.InkStroke
import com.skeler.scanely.core.pdfeditor.model.LineStyle
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.ShapeKind
import com.skeler.scanely.core.pdfeditor.model.TextFit
import com.skeler.scanely.core.pdfeditor.model.eraseWith
import com.skeler.scanely.core.pdfeditor.signature.SignatureExtractor
import com.skeler.scanely.core.pdfeditor.signature.SignatureStore
import com.skeler.scanely.core.pdfeditor.signature.StoredSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EditorTool(val title: String, val feature: PdfFeature) {
    INK("Draw", PdfFeature.ANNOTATE),
    HIGHLIGHT("Highlight", PdfFeature.HIGHLIGHT),
    UNDERLINE("Underline", PdfFeature.UNDERLINE),
    TEXT("Text", PdfFeature.ANNOTATE),
    SIGNATURE("Sign", PdfFeature.SIGNATURE),
    SHAPE("Shapes", PdfFeature.ANNOTATE),
    RETYPE("Retype", PdfFeature.EDIT_TEXT),
    INSERT("Insert", PdfFeature.EDIT_TEXT),
    ERASER("Erase", PdfFeature.ANNOTATE),
    WHITEOUT("White-out", PdfFeature.ANNOTATE)
}

enum class TextEditKind {
    REPLACE,

    INSERT
}

/** A selected page region and its resolved text styling. */
data class TextEditTarget(
    val pageIndex: Int,
    val kind: TextEditKind,
    /** Region covered before replacement; null for inserts. */
    val cover: Rect?,
    val coverColor: Color,
    val box: Rect,
    val baselinePt: Float,
    val original: String,
    val font: OfficialFont,
    val sizePt: Float,
    /** False when the source run is too short for reliable font detection. */
    val fontDetected: Boolean
)

data class PageEditorUiState(
    val pages: List<StudioPage> = emptyList(),
    val tool: EditorTool? = null,
    val color: Color = InkPalette.first(),
    val strokeWidthPt: Float = 2f,
    val eraserRadiusPt: Float = 10f,
    val whiteoutColor: Color = WhiteoutPalette.first(),
    val whiteoutWidthPt: Float = 18f,
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val font: OfficialFont = OfficialFont.SANS,
    val textSizePt: Float = 12f,
    val textColor: Color = Color.Black,
    val selectionPage: Int? = null,
    val selectionQuads: List<Rect> = emptyList(),
    val markings: Map<Int, List<PdfEdit.Marking>> = emptyMap(),
    val signatures: List<StoredSignature> = emptyList(),
    val activeSignatureId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isPro: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class PageEditorViewModel @Inject constructor(
    private val session: PdfEditorSession,
    private val gate: PdfEntitlementGate,
    private val signatureStore: SignatureStore,
    private val signatureExtractor: SignatureExtractor,
    private val fontMatcher: FontMatcher,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PageEditorUiState())
    val uiState: StateFlow<PageEditorUiState> = _uiState.asStateFlow()

    private val _pages = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val pages: StateFlow<Map<Int, Bitmap>> = _pages.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<Int, PageTextSnapshot?>>(emptyMap())

    private val pending = mutableSetOf<Int>()
    private var renderGeneration = 0L
    private val pageCache = PageRenderCache<Bitmap>(Runtime.getRuntime().maxMemory() / 8) {
        it.allocationByteCount.toLong()
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = clearRenderedPages()
        override fun onLowMemory() = clearRenderedPages()
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun clearRenderedPages() {
        renderGeneration++
        pageCache.clear()
        _pages.value = emptyMap()
        _snapshots.value = emptyMap()
        pending.clear()
    }

    override fun onCleared() {
        context.unregisterComponentCallbacks(memoryCallbacks)
        clearRenderedPages()
        super.onCleared()
    }

    init {
        context.registerComponentCallbacks(memoryCallbacks)
        viewModelScope.launch {
            combine(
                session.document,
                session.edits.stack,
                gate.isPro,
                signatureStore.signatures
            ) { document, stack, isPro, signatures ->
                _uiState.update { state ->
                    state.copy(
                        pages = deriveStudioPages(
                            document?.pageCount ?: 0,
                            stack.applied,
                            document?.geometries.orEmpty()
                        ),
                        markings = stack.applied
                            .filterIsInstance<PdfEdit.Marking>()
                            .groupBy { it.pageIndex },
                        canUndo = stack.canUndo,
                        canRedo = stack.canRedo,
                        isPro = isPro,
                        signatures = signatures
                    )
                }
            }.collect { }
        }
        viewModelScope.launch { signatureStore.load() }
        viewModelScope.launch {
            session.revision.collect {
                clearRenderedPages()
            }
        }
    }

    fun geometryOf(originalIndex: Int): Pair<Float, Float> {
        val geometry = session.document.value?.geometries?.getOrNull(originalIndex)
        return (geometry?.widthPt ?: 595f) to (geometry?.heightPt ?: 842f)
    }

    fun requestPage(originalIndex: Int) {
        if (pageCache.get(originalIndex) != null || !pending.add(originalIndex)) return
        val generation = renderGeneration
        viewModelScope.launch {
            try {
                val (width, height) = geometryOf(originalIndex)
                // Leave room for the current page and both adjacent pager pages.
                val maxWidth = kotlin.math.sqrt(pageCache.maxBytes / 12.0 * width / height)
                    .toInt().coerceAtLeast(1)
                val bitmap = session.renderPage(originalIndex, min(PAGE_WIDTH_PX, maxWidth))
                if (generation != renderGeneration) return@launch
                if (bitmap != null) {
                    pageCache.put(originalIndex, bitmap)
                    _pages.value = pageCache.snapshot()
                }
                val snapshot = session.textSnapshot(originalIndex)
                if (generation == renderGeneration) {
                    _snapshots.value = _snapshots.value.filterKeys { it in _pages.value } +
                        (originalIndex to snapshot)
                }
            } finally {
                if (generation == renderGeneration) pending.remove(originalIndex)
            }
        }
    }

    fun stamp(id: String): Bitmap? = session.stamp(id)

    fun selectTool(tool: EditorTool) {
        if (tool.feature.requiresPro && !_uiState.value.isPro) return
        _uiState.update { it.copy(tool = if (it.tool == tool) null else tool) }
    }

    /**
     * Arms [tool] for a screen opened straight into it, such as Sign PDF on the tools grid. Unlike
     * [selectTool] it never toggles, so arriving twice cannot leave the tool off. The gate is read
     * directly because this can run before the first state emission has copied isPro across.
     */
    fun startWith(tool: EditorTool) {
        if (tool.feature.requiresPro && !gate.isPro.value) return
        _uiState.update { it.copy(tool = tool) }
    }

    fun setColor(color: Color) = _uiState.update { it.copy(color = color) }

    fun setStrokeWidth(width: Float) = _uiState.update { it.copy(strokeWidthPt = width) }

    fun setShape(shape: ShapeKind) = _uiState.update { it.copy(shape = shape) }

    fun setLineStyle(style: LineStyle) = _uiState.update { it.copy(lineStyle = style) }

    fun setFont(font: OfficialFont) = _uiState.update { it.copy(font = font) }

    fun setTextSize(size: Float) = _uiState.update { it.copy(textSizePt = size) }

    fun setTextColor(color: Color) = _uiState.update { it.copy(textColor = color) }

    fun undo() = session.edits.undo()

    fun redo() = session.edits.redo()

    fun addInk(pageIndex: Int, points: List<Offset>) {
        if (points.size < 2) return
        val state = _uiState.value
        session.edits.push(
            PdfEdit.Ink(
                pageIndex = pageIndex,
                strokes = listOf(
                    InkStroke(points, state.color, state.strokeWidthPt, translucent = false)
                )
            )
        )
    }

    /** Snaps to the text layer when available; image-only pages use a free-form band. */
    fun markText(pageIndex: Int, from: Offset, to: Offset) {
        val tool = _uiState.value.tool ?: return
        val snapshot = _snapshots.value[pageIndex]
        val quads = snapshot
            ?.rangeBetween(from, to)
            ?.let { snapshot.quads(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(freeFormBand(from, to))

        val state = _uiState.value
        session.edits.push(
            when (tool) {
                EditorTool.UNDERLINE -> PdfEdit.Underline(
                    pageIndex,
                    quads,
                    state.color,
                    state.lineStyle
                )
                else -> PdfEdit.Highlight(pageIndex, quads, state.color)
            }
        )
    }

    fun addTextBox(pageIndex: Int, box: Rect, text: String) {
        if (text.isBlank()) return
        val state = _uiState.value
        session.edits.push(
            PdfEdit.TextBox(pageIndex, box, text, state.font, state.textSizePt, state.textColor)
        )
    }

    fun addShape(pageIndex: Int, box: Rect) {
        val state = _uiState.value
        if (box.width < MIN_SHAPE_PT && box.height < MIN_SHAPE_PT) return
        session.edits.push(
            PdfEdit.Shape(
                pageIndex = pageIndex,
                kind = state.shape,
                box = box,
                strokeColor = state.color,
                fillColor = null,
                strokeWidthPt = state.strokeWidthPt
            )
        )
    }

    fun placeSignature(pageIndex: Int, at: Offset) {
        val state = _uiState.value
        val signature = state.signatures.firstOrNull { it.id == state.activeSignatureId }
        if (signature == null) {
            _uiState.update { it.copy(message = "Pick or capture a signature first.") }
            return
        }
        val width = SIGNATURE_WIDTH_PT
        val height = width / signature.aspectRatio.coerceAtLeast(0.1f)
        session.edits.push(
            PdfEdit.Stamp(
                pageIndex = pageIndex,
                box = Rect(at.x - width / 2f, at.y - height / 2f, at.x + width / 2f, at.y + height / 2f),
                imageId = signature.id
            )
        )
    }

    fun eraseAt(pageIndex: Int, point: Offset) {
        val radius = _uiState.value.eraserRadiusPt
        session.edits.replaceAll { edits ->
            var wiped = false
            val next = mutableListOf<PdfEdit>()
            edits.forEach { edit ->
                val marking = edit as? PdfEdit.Marking
                if (marking == null || marking.pageIndex != pageIndex) {
                    next += edit
                    return@forEach
                }
                val survivors = marking.eraseWith(point, radius)
                if (survivors == null) {
                    next += edit
                } else {
                    wiped = true
                    next += survivors
                }
            }
            if (wiped) next else edits
        }
    }

    fun beginErase() = session.edits.beginGesture()

    fun endErase() = session.edits.endGesture()

    /** Adds an opaque overlay; this hides content but does not redact the underlying PDF text. */
    fun addWhiteout(pageIndex: Int, points: List<Offset>) {
        if (points.isEmpty()) return
        val state = _uiState.value
        session.edits.push(
            PdfEdit.Ink(
                pageIndex = pageIndex,
                strokes = listOf(
                    InkStroke(
                        points = points,
                        color = state.whiteoutColor,
                        widthPt = state.whiteoutWidthPt
                    )
                )
            )
        )
    }

    fun setEraserRadius(radiusPt: Float) = _uiState.update { it.copy(eraserRadiusPt = radiusPt) }

    fun setWhiteoutColor(color: Color) = _uiState.update { it.copy(whiteoutColor = color) }

    fun setWhiteoutWidth(widthPt: Float) = _uiState.update { it.copy(whiteoutWidthPt = widthPt) }

    fun prepareRetype(pageIndex: Int, point: Offset): TextEditTarget? {
        val snapshot = _snapshots.value[pageIndex] ?: return null
        val selection = snapshot.lineSelectionAt(point, RETYPE_TOLERANCE_PT) ?: return null
        return replaceTarget(pageIndex, selection)
    }

    fun prepareRetypeRange(pageIndex: Int, from: Offset, to: Offset): TextEditTarget? {
        val snapshot = _snapshots.value[pageIndex] ?: return null
        val selection = snapshot.selectionBetween(from, to) ?: return null
        return replaceTarget(pageIndex, selection)
    }

    /** Adds text at the tapped baseline. PDF text does not reflow existing content. */
    fun prepareInsert(pageIndex: Int, point: Offset): TextEditTarget? {
        val snapshot = _snapshots.value[pageIndex] ?: return null
        val line = snapshot.lineSelectionAt(point, INSERT_TOLERANCE_PT) ?: return null
        val (pageWidth, _) = geometryOf(pageIndex)

        val detected = detect(line)
        return TextEditTarget(
            pageIndex = pageIndex,
            kind = TextEditKind.INSERT,
            cover = null,
            coverColor = Color.Transparent,
            box = TextFit.runToMargin(point.x, line.bounds.top, line.bounds.bottom, pageWidth),
            baselinePt = line.baselinePt,
            original = "",
            font = detected.font,
            sizePt = detected.sizePt,
            fontDetected = detected.isConfident
        )
    }

    private fun replaceTarget(pageIndex: Int, selection: TextSelection): TextEditTarget {
        val cover = selection.bounds.inflate(COVER_PADDING_PT)
        val (pageWidth, _) = geometryOf(pageIndex)
        val detected = detect(selection)
        return TextEditTarget(
            pageIndex = pageIndex,
            kind = TextEditKind.REPLACE,
            cover = cover,
            coverColor = paperColorAround(pageIndex, cover),
            // Allow replacements to extend to the margin without reflowing the next line.
            box = TextFit.runToMargin(cover.left, cover.top, cover.bottom, pageWidth),
            baselinePt = selection.baselinePt,
            original = selection.text,
            font = detected.font,
            sizePt = detected.sizePt,
            fontDetected = detected.isConfident
        )
    }

    private fun detect(selection: TextSelection): DetectedFont = fontMatcher.detect(
        text = selection.text,
        // Multi-line bounds describe the column, not the selected text width.
        measuredWidthPt = if (selection.quads.size == 1) selection.bounds.width else 0f,
        reportedSizePt = selection.fontSizePt
    )

    fun applyTextEdit(
        target: TextEditTarget,
        text: String,
        font: OfficialFont,
        sizePt: Float
    ) {
        if (text.isBlank()) return
        val color = _uiState.value.textColor
        session.edits.push(
            when (target.kind) {
                TextEditKind.REPLACE -> PdfEdit.ReplaceText(
                    pageIndex = target.pageIndex,
                    cover = target.cover ?: target.box,
                    coverColor = target.coverColor,
                    replacement = text,
                    font = font,
                    sizePt = sizePt,
                    color = color,
                    baselinePt = target.baselinePt,
                    textBox = target.box
                )
                TextEditKind.INSERT -> PdfEdit.TextBox(
                    pageIndex = target.pageIndex,
                    box = target.box,
                    text = text,
                    font = font,
                    sizePt = sizePt,
                    color = color,
                    baselinePt = target.baselinePt
                )
            }
        )
    }

    fun previewSelection(pageIndex: Int, from: Offset, to: Offset) {
        val quads = _snapshots.value[pageIndex]?.selectionBetween(from, to)?.quads.orEmpty()
        // Avoid recomposition when word snapping leaves the selection unchanged.
        val current = _uiState.value
        if (current.selectionPage == pageIndex && current.selectionQuads == quads) return
        _uiState.update { it.copy(selectionPage = pageIndex, selectionQuads = quads) }
    }

    fun clearSelectionPreview() =
        _uiState.update { it.copy(selectionPage = null, selectionQuads = emptyList()) }

    fun selectSignature(id: String?) {
        _uiState.update { it.copy(activeSignatureId = id) }
        if (id == null) return
        viewModelScope.launch {
            signatureStore.bitmapFor(id)?.let { session.registerStamp(id, it) }
        }
    }

    fun captureSignature(source: Bitmap, label: String) {
        viewModelScope.launch {
            val ink = signatureExtractor.extract(source)
            if (ink == null) {
                _uiState.update { it.copy(message = "No signature found on that page.") }
                return@launch
            }
            storeSignature(ink, label)
        }
    }

    /** A typed signature is already clean ink on transparency, so it skips the extractor. */
    fun saveTypedSignature(ink: Bitmap, label: String) {
        viewModelScope.launch { storeSignature(ink, label) }
    }

    private suspend fun storeSignature(ink: Bitmap, label: String) {
        val stored = signatureStore.save(ink, label)
        if (stored == null) {
            _uiState.update { it.copy(message = "Couldn't save that signature.") }
        } else {
            session.registerStamp(stored.id, ink)
            _uiState.update { it.copy(activeSignatureId = stored.id) }
        }
    }

    fun deleteSignature(id: String) {
        viewModelScope.launch {
            signatureStore.delete(id)
            _uiState.update {
                it.copy(activeSignatureId = it.activeSignatureId.takeIf { active -> active != id })
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /** Samples the rendered page to match the replacement cover to its paper color. */
    private fun paperColorAround(pageIndex: Int, cover: Rect): Color {
        val bitmap = _pages.value[pageIndex] ?: return Color.White
        val (widthPt, _) = geometryOf(pageIndex)
        val scale = bitmap.width / widthPt.coerceAtLeast(1f)

        val left = (cover.left * scale).roundToInt().coerceIn(0, bitmap.width - 1)
        val right = (cover.right * scale).roundToInt().coerceIn(left + 1, bitmap.width)
        val bandHeight = max(1, (COVER_PADDING_PT * scale).roundToInt())
        val top = ((cover.top * scale).roundToInt() - bandHeight).coerceAtLeast(0)
        val bottom = (top + bandHeight).coerceAtMost(bitmap.height)
        if (bottom <= top) return Color.White

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val step = max(1, (right - left) / SAMPLE_COLUMNS)
        var x = left
        while (x < right) {
            for (y in top until bottom) {
                val pixel = bitmap.getPixel(x, y)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
            x += step
        }
        if (count == 0L) return Color.White
        return Color(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt()
        )
    }

    private fun freeFormBand(from: Offset, to: Offset): Rect {
        val halfHeight = max(abs(to.y - from.y) / 2f, MIN_BAND_HALF_HEIGHT_PT)
        val centre = (from.y + to.y) / 2f
        return Rect(
            min(from.x, to.x),
            centre - halfHeight,
            max(from.x, to.x),
            centre + halfHeight
        )
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1200
        const val SIGNATURE_WIDTH_PT = 150f
        const val MIN_SHAPE_PT = 6f
        const val MIN_BAND_HALF_HEIGHT_PT = 6f
        const val RETYPE_TOLERANCE_PT = 14f
        const val INSERT_TOLERANCE_PT = 20f
        const val COVER_PADDING_PT = 1.5f
        const val SAMPLE_COLUMNS = 24
    }
}

val WhiteoutPalette: List<Color> = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFFAF7F0),
    Color(0xFFF1F1F1),
    Color(0xFFE0E0E0),
    Color(0xFF212121)
)

val InkPalette: List<Color> = listOf(
    Color(0xFFE53935),
    Color(0xFFFB8C00),
    Color(0xFFFDD835),
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFF8E24AA),
    Color(0xFF212121)
)

val TextPalette: List<Color> = listOf(
    Color(0xFF000000),
    Color(0xFF3A3A3A),
    Color(0xFF1A3E7A),
    Color(0xFFB00020),
    Color(0xFF1E88E5),
    Color(0xFF43A047)
)
