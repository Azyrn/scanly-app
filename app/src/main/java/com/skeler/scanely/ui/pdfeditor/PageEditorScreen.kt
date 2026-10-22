@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.pdfeditor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Rectangle
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.model.InkStroke
import com.skeler.scanely.core.pdfeditor.model.LineStyle
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.model.ShapeKind
import com.skeler.scanely.core.scan.DocumentScannerManager
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The page canvas.
 *
 * The tool stays armed after it is chosen: arming the highlighter once and dragging across twenty
 * lines is the whole point, and every editor that disarms after each mark turns that into forty
 * taps. Colour and width live in a strip above the rail rather than behind a dialog for the same
 * reason.
 */
@Composable
fun PageEditorScreen(startIndex: Int) {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: PageEditorViewModel = hiltViewModel(activity)
    androidx.compose.runtime.DisposableEffect(vm) {
        onDispose { vm.clearRenderedPages() }
    }
    val state by vm.uiState.collectAsState()
    val bitmaps by vm.pages.collectAsState()
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingText by remember { mutableStateOf<Offset?>(null) }
    var showPaywall by remember { mutableStateOf(false) }
    var showTypedSignature by remember { mutableStateOf(false) }
    var pendingTextEdit by remember { mutableStateOf<TextEditTarget?>(null) }

    fun noTextHere() {
        scope.launch {
            snackbarHostState.showSnackbar("No selectable text there — this page may be a scan.")
        }
    }

    val pageCount = state.pages.size
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, max(0, pageCount - 1)),
        pageCount = { pageCount }
    )

    // The pages arrive a frame after the pager is created, so the start page is applied once
    // they do rather than being coerced to zero against an empty pager.
    LaunchedEffect(pageCount) {
        if (pageCount > 0 && pagerState.currentPage != startIndex) {
            pagerState.scrollToPage(startIndex.coerceIn(0, pageCount - 1))
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val page = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?.pages?.firstOrNull()?.imageUri ?: return@rememberLauncherForActivityResult
        decodeBitmap(context, page)?.let { vm.captureSignature(it, "Signature") }
    }

    fun captureSignature() {
        if (!DocumentScannerManager.isScannerAvailable(context)) {
            scope.launch {
                snackbarHostState.showSnackbar("The document scanner isn't available on this device.")
            }
            return
        }
        DocumentScannerManager.getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Page ${pagerState.currentPage + 1} of ${pageCount.coerceAtLeast(1)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = vm::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    ToolOptionStrip(
                        state = state,
                        onColor = vm::setColor,
                        onWidth = vm::setStrokeWidth,
                        onEraserRadius = vm::setEraserRadius,
                        onWhiteoutColor = vm::setWhiteoutColor,
                        onWhiteoutWidth = vm::setWhiteoutWidth,
                        onShape = vm::setShape,
                        onLineStyle = vm::setLineStyle,
                        onFont = vm::setFont,
                        onTextSize = vm::setTextSize,
                        onTextColor = vm::setTextColor,
                        onSignature = vm::selectSignature,
                        onCaptureSignature = ::captureSignature,
                        onTypeSignature = { showTypedSignature = true },
                        onDeleteSignature = vm::deleteSignature,
                        canGoBack = pagerState.currentPage > 0,
                        canGoForward = pagerState.currentPage < pageCount - 1,
                        onPrevious = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        },
                        onNext = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    )
                    ToolRail(
                        armed = state.tool,
                        isPro = state.isPro,
                        onSelect = vm::selectTool,
                        onLocked = { showPaywall = true }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (pageCount == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // A drag tool owns single-finger drags, so paging moves to the arrows in the strip
            // above the rail rather than fighting the stroke for the same gesture.
            userScrollEnabled = state.tool?.isDragTool != true,
            pageSpacing = 8.dp
        ) { position ->
            val page = state.pages.getOrNull(position) ?: return@HorizontalPager
            LaunchedEffect(page.originalIndex, bitmaps[page.originalIndex]) { vm.requestPage(page.originalIndex) }

            PageSurface(
                page = page,
                bitmap = bitmaps[page.originalIndex],
                markings = state.markings[page.originalIndex].orEmpty(),
                selectionQuads = if (state.selectionPage == page.originalIndex) {
                    state.selectionQuads
                } else {
                    emptyList()
                },
                tool = state.tool,
                color = state.color,
                strokeWidthPt = state.strokeWidthPt,
                eraserRadiusPt = state.eraserRadiusPt,
                whiteoutColor = state.whiteoutColor,
                whiteoutWidthPt = state.whiteoutWidthPt,
                shape = state.shape,
                stampFor = vm::stamp,
                onInk = { points -> vm.addInk(page.originalIndex, points) },
                onTextMark = { from, to -> vm.markText(page.originalIndex, from, to) },
                onShape = { box -> vm.addShape(page.originalIndex, box) },
                onWhiteout = { points -> vm.addWhiteout(page.originalIndex, points) },
                onEraseStart = vm::beginErase,
                onErase = { point -> vm.eraseAt(page.originalIndex, point) },
                onEraseEnd = vm::endErase,
                onSelectionDrag = { from, to ->
                    vm.previewSelection(page.originalIndex, from, to)
                },
                onSelectionEnd = { from, to ->
                    vm.clearSelectionPreview()
                    val target = vm.prepareRetypeRange(page.originalIndex, from, to)
                    if (target == null) noTextHere() else pendingTextEdit = target
                },
                onTap = { point ->
                    when (state.tool) {
                        EditorTool.TEXT -> pendingText = point
                        EditorTool.ERASER -> vm.eraseAt(page.originalIndex, point)
                        EditorTool.WHITEOUT -> vm.addWhiteout(page.originalIndex, listOf(point))
                        EditorTool.SIGNATURE -> vm.placeSignature(page.originalIndex, point)
                        EditorTool.RETYPE -> {
                            val target = vm.prepareRetype(page.originalIndex, point)
                            if (target == null) noTextHere() else pendingTextEdit = target
                        }
                        EditorTool.INSERT -> {
                            val target = vm.prepareInsert(page.originalIndex, point)
                            if (target == null) noTextHere() else pendingTextEdit = target
                        }
                        else -> Unit
                    }
                }
            )
        }
    }

    pendingText?.let { at ->
        val page = state.pages.getOrNull(pagerState.currentPage)
        TextEntryDialog(
            title = "Add text",
            initial = "",
            confirmLabel = "Add",
            onConfirm = { text ->
                pendingText = null
                if (page != null) {
                    vm.addTextBox(
                        page.originalIndex,
                        Rect(
                            at.x,
                            at.y,
                            min(at.x + TEXT_BOX_WIDTH_PT, page.widthPt),
                            at.y + state.textSizePt * TEXT_BOX_LINES
                        ),
                        text
                    )
                }
            },
            onDismiss = { pendingText = null }
        )
    }

    if (showTypedSignature) {
        TypedSignatureDialog(
            onDismiss = { showTypedSignature = false },
            onSave = { bitmap, label ->
                showTypedSignature = false
                vm.saveTypedSignature(bitmap, label)
            }
        )
    }

    if (showPaywall) {
        val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
        val paywallState by paywallViewModel.state.collectAsState()
        PaywallSheet(
            state = paywallState,
            onDismiss = {
                showPaywall = false
                paywallViewModel.consumeMessage()
            },
            onRedeem = { key -> paywallViewModel.redeem(key) },
            onRestore = { paywallViewModel.restorePurchase() },
            onConfirmEmail = { paywallViewModel.confirmEmail() },
            onRetry = { paywallViewModel.refresh() },
            reason = "Signing and editing text are part of Scanly Pro.",
            isFeatureLock = true
        )
    }

    pendingTextEdit?.let { target ->
        TextStyleDialog(
            target = target,
            onConfirm = { text, font, sizePt ->
                pendingTextEdit = null
                vm.applyTextEdit(target, text, font, sizePt)
            },
            onDismiss = { pendingTextEdit = null }
        )
    }
}

/**
 * Types the replacement or the insertion, with the style Scanly worked out already filled in.
 *
 * The font and size are editable rather than fixed because the detection is an inference from
 * glyph widths, not a font name read out of the file — right nearly always, and when it isn't, the
 * person looking at the page can see that faster than any heuristic can.
 */
@Composable
private fun TextStyleDialog(
    target: TextEditTarget,
    onConfirm: (String, OfficialFont, Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(target.original) }
    var font by remember { mutableStateOf(target.font) }
    var sizePt by remember { mutableStateOf(target.sizePt) }

    val isReplace = target.kind == TextEditKind.REPLACE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isReplace) "Replace text" else "Insert text") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (target.fontDetected) {
                        "Matched to ${font.displayName} at ${sizePt.roundToInt()} pt from the " +
                            "surrounding text."
                    } else {
                        "Too few characters to match a font confidently — check the style below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (isReplace) "Replacement" else "Text to add") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OfficialFont.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == font,
                            onClick = { font = candidate },
                            label = { Text(candidate.displayName) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                SizeStepper(sizePt) { sizePt = it }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isReplace) {
                        "The original is covered and the new text drawn on its baseline. A longer " +
                            "replacement is condensed to fit the space, then runs on to the margin " +
                            "— it is never pushed onto the line below."
                    } else {
                        "The line stays as it is — the new words sit on the same baseline beside " +
                            "it. PDF text can't reflow, so nothing shifts to make room."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text, font, sizePt) },
                enabled = text.isNotBlank()
            ) {
                Text(if (isReplace) "Replace" else "Insert")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val EditorTool.isDragTool: Boolean
    get() = this == EditorTool.INK ||
        this == EditorTool.HIGHLIGHT ||
        this == EditorTool.UNDERLINE ||
        this == EditorTool.SHAPE ||
        this == EditorTool.ERASER ||
        this == EditorTool.WHITEOUT ||
        this == EditorTool.RETYPE

@Composable
private fun PageSurface(
    page: StudioPage,
    bitmap: Bitmap?,
    markings: List<PdfEdit.Marking>,
    selectionQuads: List<Rect>,
    tool: EditorTool?,
    color: Color,
    strokeWidthPt: Float,
    eraserRadiusPt: Float,
    whiteoutColor: Color,
    whiteoutWidthPt: Float,
    shape: ShapeKind,
    stampFor: (String) -> Bitmap?,
    onInk: (List<Offset>) -> Unit,
    onTextMark: (Offset, Offset) -> Unit,
    onShape: (Rect) -> Unit,
    onWhiteout: (List<Offset>) -> Unit,
    onEraseStart: () -> Unit,
    onErase: (Offset) -> Unit,
    onEraseEnd: () -> Unit,
    onSelectionDrag: (Offset, Offset) -> Unit,
    onSelectionEnd: (Offset, Offset) -> Unit,
    onTap: (Offset) -> Unit
) {
    var zoom by remember(page.originalIndex) { mutableStateOf(1f) }
    var pan by remember(page.originalIndex) { mutableStateOf(Offset.Zero) }
    val live = remember(page.originalIndex) { mutableStateListOf<Offset>() }

    val measurer = rememberTextMeasurer()
    val fonts = rememberOfficialFonts()
    val density = LocalDensity.current
    val fontSizeOf = remember(density) {
        {
                sizePt: Float ->
            with(density) { sizePt.toSp() }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        val projection = PageProjection(
            containerSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
            pageWidthPt = page.widthPt,
            pageHeightPt = page.heightPt,
            rotation = page.rotation,
            zoom = zoom,
            pan = pan
        )
        val current by rememberUpdatedState(projection)

        if (bitmap == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val image = remember(bitmap) { bitmap.asImageBitmap() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(page.originalIndex, tool) {
                    pageGestures(
                        drawing = tool?.isDragTool == true,
                        onStrokeStart = {
                            live.clear()
                            val point = current.clamped(it)
                            live += point
                            if (tool == EditorTool.ERASER) {
                                onEraseStart()
                                onErase(point)
                            }
                        },
                        onStrokeMove = {
                            val point = current.clamped(it)
                            live += point
                            if (tool == EditorTool.ERASER) onErase(point)
                            if (tool == EditorTool.RETYPE && live.size >= 2) {
                                onSelectionDrag(live.first(), point)
                            }
                        },
                        onStrokeEnd = {
                            when (tool) {
                                EditorTool.INK -> onInk(live.toList())
                                EditorTool.HIGHLIGHT, EditorTool.UNDERLINE ->
                                    if (live.size >= 2) onTextMark(live.first(), live.last())
                                EditorTool.SHAPE ->
                                    if (live.size >= 2) {
                                        onShape(boxOf(live.first(), live.last()))
                                    }
                                EditorTool.WHITEOUT -> onWhiteout(live.toList())
                                EditorTool.ERASER -> onEraseEnd()
                                EditorTool.RETYPE ->
                                    if (live.size >= 2) onSelectionEnd(live.first(), live.last())
                                else -> Unit
                            }
                            live.clear()
                        },
                        // Clamped like a stroke: a tap in the grey beside the page would otherwise
                        // place text at negative coordinates, off the page and out of sight.
                        onTap = { onTap(current.clamped(it)) },
                        onTransform = { panChange, zoomChange ->
                            zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                            pan = if (zoom <= 1.01f) Offset.Zero else pan + panChange
                        }
                    )
                }
        ) {
            withPage(projection) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        page.widthPt.roundToInt().coerceAtLeast(1),
                        page.heightPt.roundToInt().coerceAtLeast(1)
                    )
                )
                markings.forEach { drawMarking(it, measurer, fonts, fontSizeOf, stampFor) }
                selectionQuads.forEach {
                    drawRect(SELECTION_TINT, it.topLeft, it.size)
                }
                if (live.isNotEmpty()) {
                    drawLivePreview(
                        points = live.toList(),
                        tool = tool,
                        color = color,
                        strokeWidthPt = strokeWidthPt,
                        eraserRadiusPt = eraserRadiusPt,
                        whiteoutColor = whiteoutColor,
                        whiteoutWidthPt = whiteoutWidthPt,
                        shape = shape
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLivePreview(
    points: List<Offset>,
    tool: EditorTool?,
    color: Color,
    strokeWidthPt: Float,
    eraserRadiusPt: Float,
    whiteoutColor: Color,
    whiteoutWidthPt: Float,
    shape: ShapeKind
) {
    // The eraser is the one tool that shows something on first touch: without a ring under the
    // finger there is no way to judge what the next centimetre of the drag will take with it.
    if (tool == EditorTool.ERASER) {
        drawCircle(
            color = ERASER_RING,
            radius = eraserRadiusPt,
            center = points.last(),
            style = Stroke(ERASER_RING_WIDTH_PT)
        )
        return
    }
    if (points.size < 2) return

    when (tool) {
        EditorTool.INK -> drawInkStroke(InkStroke(points, color, strokeWidthPt))
        EditorTool.WHITEOUT ->
            drawInkStroke(InkStroke(points, whiteoutColor, whiteoutWidthPt))
        EditorTool.HIGHLIGHT -> {
            val box = boxOf(points.first(), points.last())
            drawRect(
                color = color.copy(alpha = 0.3f),
                topLeft = Offset(box.left, box.top - LIVE_BAND_PT),
                size = Size(box.width, box.height + LIVE_BAND_PT * 2)
            )
        }
        EditorTool.UNDERLINE -> drawLine(
            color = color,
            start = points.first(),
            end = Offset(points.last().x, points.first().y),
            strokeWidth = 1.4f
        )
        EditorTool.SHAPE -> {
            val box = boxOf(points.first(), points.last())
            when (shape) {
                ShapeKind.ELLIPSE -> drawOval(
                    color,
                    box.topLeft,
                    box.size,
                    style = Stroke(strokeWidthPt)
                )
                ShapeKind.RECTANGLE -> drawRect(
                    color,
                    box.topLeft,
                    box.size,
                    style = Stroke(strokeWidthPt)
                )
                else -> drawLine(color, points.first(), points.last(), strokeWidth = strokeWidthPt)
            }
        }
        else -> Unit
    }
}

/**
 * One finger draws when a tool is armed, two fingers always zoom and pan, and a finger that
 * lands and lifts without travelling is a tap. Splitting on pointer count is what lets the
 * highlighter stay armed while the page can still be zoomed into.
 */
private suspend fun PointerInputScope.pageGestures(
    drawing: Boolean,
    onStrokeStart: (Offset) -> Unit,
    onStrokeMove: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
    onTap: (Offset) -> Unit,
    onTransform: (Offset, Float) -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var stroking = false
    var transforming = false
    var travelled = 0f

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.count { it.pressed }
        if (pressed == 0) break

        if (pressed >= 2) {
            if (stroking) {
                onStrokeEnd()
                stroking = false
            }
            transforming = true
            onTransform(event.calculatePan(), event.calculateZoom())
            event.changes.forEach { it.consume() }
            continue
        }

        if (transforming) continue

        val change = event.changes.firstOrNull { it.pressed } ?: continue
        if (!change.positionChanged()) continue
        travelled += (change.position - change.previousPosition).getDistance()
        if (!drawing) continue

        if (!stroking) {
            stroking = true
            onStrokeStart(down.position)
        }
        onStrokeMove(change.position)
        change.consume()
    }

    when {
        stroking -> onStrokeEnd()
        !transforming && travelled < TAP_SLOP_PX -> onTap(down.position)
    }
}

private fun PageProjection.clamped(offset: Offset): Offset = clampToPage(toPage(offset))

private fun boxOf(a: Offset, b: Offset) =
    Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

@Composable
private fun ToolRail(
    armed: EditorTool?,
    isPro: Boolean,
    onSelect: (EditorTool) -> Unit,
    onLocked: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        EditorTool.entries.forEach { tool ->
            val isLocked = tool.feature.requiresPro && !isPro
            ToolButton(
                tool = tool,
                isArmed = tool == armed,
                isLocked = isLocked,
                onClick = { if (isLocked) onLocked() else onSelect(tool) }
            )
        }
    }
}

@Composable
private fun ToolButton(
    tool: EditorTool,
    isArmed: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit
) {
    val background = if (isArmed) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val tint = when {
        isArmed -> MaterialTheme.colorScheme.onPrimary
        isLocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.title,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            if (isLocked) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Pro",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(11.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tool.title,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

private val EditorTool.icon: ImageVector
    get() = when (this) {
        EditorTool.INK -> Icons.Rounded.Draw
        EditorTool.HIGHLIGHT -> Icons.Rounded.Highlight
        EditorTool.UNDERLINE -> Icons.Rounded.FormatUnderlined
        EditorTool.TEXT -> Icons.Rounded.TextFields
        EditorTool.SIGNATURE -> Icons.Rounded.Gesture
        EditorTool.SHAPE -> Icons.Rounded.Rectangle
        EditorTool.RETYPE -> Icons.Rounded.Title
        EditorTool.INSERT -> Icons.Rounded.PostAdd
        EditorTool.ERASER -> Icons.Rounded.AutoFixNormal
        EditorTool.WHITEOUT -> Icons.Rounded.FormatColorReset
    }

@Composable
private fun ToolOptionStrip(
    state: PageEditorUiState,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onEraserRadius: (Float) -> Unit,
    onWhiteoutColor: (Color) -> Unit,
    onWhiteoutWidth: (Float) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onLineStyle: (LineStyle) -> Unit,
    onFont: (OfficialFont) -> Unit,
    onTextSize: (Float) -> Unit,
    onTextColor: (Color) -> Unit,
    onSignature: (String?) -> Unit,
    onCaptureSignature: () -> Unit,
    onTypeSignature: () -> Unit,
    onDeleteSignature: (String) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state.tool) {
                    EditorTool.INK, EditorTool.SHAPE -> {
                        ColorRow(state.color, onColor)
                        if (state.tool == EditorTool.SHAPE) ShapeRow(state.shape, onShape)
                        WidthSlider(state.strokeWidthPt, onWidth)
                    }
                    EditorTool.HIGHLIGHT -> ColorRow(state.color, onColor)
                    EditorTool.UNDERLINE -> {
                        ColorRow(state.color, onColor)
                        LineStyleRow(state.lineStyle, onLineStyle)
                    }
                    EditorTool.TEXT -> {
                        ColorRow(state.textColor, onTextColor, TextPalette)
                        FontRow(state.font, onFont)
                        SizeStepper(state.textSizePt, onTextSize)
                    }
                    // No font or size row: both are read off the page and offered in the dialog,
                    // where they can be seen next to the words they will apply to.
                    EditorTool.RETYPE -> {
                        ColorRow(state.textColor, onTextColor, TextPalette)
                        Hint("Drag across the words to replace, or tap for the whole line.")
                    }
                    EditorTool.INSERT -> {
                        ColorRow(state.textColor, onTextColor, TextPalette)
                        Hint("Tap a line where the new words should go.")
                    }
                    EditorTool.SIGNATURE -> SignatureRow(
                        state = state,
                        onSelect = onSignature,
                        onCapture = onCaptureSignature,
                        onType = onTypeSignature,
                        onDelete = onDeleteSignature
                    )
                    EditorTool.ERASER -> {
                        WidthSlider(state.eraserRadiusPt, onEraserRadius, ERASER_RANGE)
                        Hint("Drag to rub out marks you added.")
                    }
                    EditorTool.WHITEOUT -> {
                        ColorRow(state.whiteoutColor, onWhiteoutColor, WhiteoutPalette)
                        WidthSlider(state.whiteoutWidthPt, onWhiteoutWidth, WHITEOUT_RANGE)
                        Hint("Covers the page — it hides text, it does not remove it.")
                    }
                    null -> Hint("Pick a tool. It stays on until you tap it again.")
                }
            }
        }

        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous page")
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next page")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@Composable
private fun ColorRow(
    selected: Color,
    onSelect: (Color) -> Unit,
    palette: List<Color> = InkPalette
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        palette.forEach { color ->
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (color == selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) }
            )
        }
    }
}

@Composable
private fun WidthSlider(
    width: Float,
    onWidth: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 1f..12f
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${width.roundToInt()} pt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = width,
            onValueChange = onWidth,
            valueRange = range,
            modifier = Modifier.width(120.dp)
        )
    }
}

@Composable
private fun ShapeRow(selected: ShapeKind, onSelect: (ShapeKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShapeKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(kind.label) }
            )
        }
    }
}

private val ShapeKind.label: String
    get() = when (this) {
        ShapeKind.RECTANGLE -> "Box"
        ShapeKind.ELLIPSE -> "Oval"
        ShapeKind.LINE -> "Line"
        ShapeKind.ARROW -> "Arrow"
    }

@Composable
private fun LineStyleRow(selected: LineStyle, onSelect: (LineStyle) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LineStyle.entries.forEach { style ->
            FilterChip(
                selected = style == selected,
                onClick = { onSelect(style) },
                label = {
                    Text(
                        when (style) {
                            LineStyle.SOLID -> "Solid"
                            LineStyle.SQUIGGLY -> "Squiggly"
                            LineStyle.STRIKETHROUGH -> "Strike"
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun FontRow(selected: OfficialFont, onSelect: (OfficialFont) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OfficialFont.entries.forEach { font ->
            FilterChip(
                selected = font == selected,
                onClick = { onSelect(font) },
                label = { Text(font.displayName) }
            )
        }
    }
}

@Composable
private fun SizeStepper(size: Float, onSize: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSize((size - 1f).coerceAtLeast(6f)) }) {
            Text("-", style = MaterialTheme.typography.titleMedium)
        }
        Text("${size.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { onSize((size + 1f).coerceAtMost(72f)) }) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SignatureRow(
    state: PageEditorUiState,
    onSelect: (String?) -> Unit,
    onCapture: () -> Unit,
    onType: () -> Unit,
    onDelete: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onCapture),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Capture a signature")
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onType),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.signature_typed_open)
            )
        }

        state.signatures.forEach { signature ->
            val isActive = signature.id == state.activeSignatureId
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        width = if (isActive) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(if (isActive) null else signature.id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = signature.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                if (isActive) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Delete signature",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(14.dp)
                            .clickable { onDelete(signature.id) }
                    )
                }
            }
        }

        if (state.activeSignatureId != null) {
            Text(
                text = "Tap the page to place it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

private const val MAX_ZOOM = 6f
private const val TAP_SLOP_PX = 14f
private const val TEXT_BOX_WIDTH_PT = 220f
private const val TEXT_BOX_LINES = 3f
private const val LIVE_BAND_PT = 5f
private const val ERASER_RING_WIDTH_PT = 1.2f
private val ERASER_RING = Color(0xFF9E9E9E)
private val SELECTION_TINT = Color(0x552196F3)
private val ERASER_RANGE = 4f..40f
private val WHITEOUT_RANGE = 4f..48f
