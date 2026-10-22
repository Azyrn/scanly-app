@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.skeler.scanely.ui.pdfeditor

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.navigation.OnScreenExit
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.components.rememberStorageAccess
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import kotlinx.coroutines.launch

/**
 * The page grid is the editor's home surface rather than a page viewer, because almost every
 * document-level intent — drop a page, straighten one, pull three out — is expressed by choosing
 * pages first. Long-press selects, the top bar becomes the actions for what is selected, and a
 * drag reorders; nothing here needs a mode to be chosen up front.
 */
@Composable
fun PdfStudioScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: PdfStudioViewModel = hiltViewModel(activity)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val thumbnails by vm.thumbnails.collectAsStateWithLifecycle()
    val withStorageAccess = rememberStorageAccess()

    val snackbarHostState = remember { SnackbarHostState() }
    var showTools by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var cropTarget by remember { mutableStateOf<Int?>(null) }

    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsStateWithLifecycle()

    // Saving can finish with "close when done"; leaving is then driven by the save, not by a tap.
    LaunchedEffect(state.isClosed) {
        if (state.isClosed) navigator.backWhenSettled()
    }

    // The pdfium session and its workspace are released on the one path every exit shares.
    OnScreenExit { vm.close() }

    // A tools-grid entry such as Sign PDF lands on the first page with its tool armed. It waits
    // for a loaded, unlocked document because the page editor has nothing to show before that,
    // and consuming the request keeps Back from this grid's page editor from re-opening it.
    val pageEditorVm: PageEditorViewModel = hiltViewModel(activity)
    val documentReady = !state.isLoading && !state.needsPassword && state.pages.isNotEmpty()
    LaunchedEffect(
        state.pendingTool,
        state.isLoading,
        state.needsPassword,
        state.pages.isNotEmpty()
    ) {
        val tool = state.pendingTool ?: return@LaunchedEffect
        if (!documentReady) return@LaunchedEffect
        pageEditorVm.startWith(tool)
        navigator.go(Destination.PageEditor(0))
        vm.consumePendingTool()
    }

    LaunchedEffect(state.message, state.savedUri) {
        val message = state.message ?: return@LaunchedEffect
        val saved = state.savedUri
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = saved?.let { "Open" },
            duration = if (saved != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && saved != null) {
            ScanExporter.openPdf(context, saved)
        }
        vm.consumeMessage()
        vm.consumeSaved()
    }

    // Enabled only while there is a selection to drop. Left always on, it would swallow the back
    // gesture before NavHost could seek the pop transition, and the screen would lose its
    // predictive-back preview entirely.
    BackHandler(enabled = state.isSelecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (state.isSelecting) {
                SelectionBar(
                    count = state.selection.size,
                    onClear = vm::clearSelection,
                    onDelete = vm::deleteSelected,
                    onRotate = { vm.rotateSelected(90) },
                    onCrop = { cropTarget = state.selection.minOrNull() },
                    onExtract = vm::extractSelected
                )
            } else {
                StudioBar(
                    title = state.displayName,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onBack = navigator::back,
                    onUndo = vm::undo,
                    onRedo = vm::redo,
                    onSelectAll = vm::selectAll
                )
            }
        },
        bottomBar = {
            StudioActionBar(
                canSave = state.canSave,
                onEdit = {
                    val start = state.pages.indexOfFirst { it.originalIndex in state.selection }
                    navigator.go(Destination.PageEditor(start.coerceAtLeast(0)))
                },
                onTools = { showTools = true },
                onSave = { withStorageAccess(vm::save) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                PageGrid(
                    pages = state.pages,
                    selection = state.selection,
                    thumbnails = thumbnails,
                    onRequestThumbnail = vm::requestThumbnail,
                    onOpen = { display ->
                        navigator.go(Destination.PageEditor(display))
                    },
                    onToggle = vm::toggleSelection,
                    onReorder = vm::reorder,
                    onClearSelection = vm::clearSelection
                )
            }

            if (state.isBusy) {
                BusyScrim(state.busyLabel)
            }
        }
    }

    if (state.needsPassword) {
        PasswordDialog(
            fileName = state.displayName,
            isRetry = state.passwordError,
            onConfirm = vm::unlock,
            onDismiss = vm::close
        )
    }

    if (state.signaturesAtRisk > 0) {
        SignatureAtRiskDialog(
            count = state.signaturesAtRisk,
            onConfirm = vm::saveConfirmed,
            onDismiss = vm::dismissSignatureWarning
        )
    }

    cropTarget?.let { originalIndex ->
        CropPageDialog(
            pageCount = state.selection.size,
            loadPage = { vm.renderPage(originalIndex, CROP_PREVIEW_WIDTH_PX) },
            onConfirm = { fraction ->
                cropTarget = null
                vm.cropSelected(fraction)
            },
            onDismiss = { cropTarget = null }
        )
    }

    if (showTools) {
        DocToolsSheet(
            isPro = state.isPro,
            onDismiss = { showTools = false },
            onRequestPro = {
                showTools = false
                showPaywall = true
            }
        )
    }

    if (showPaywall) {
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
            reason = "Compressing, encrypting and editing text are part of Scanly Pro.",
            isFeatureLock = true
        )
    }
}

@Composable
private fun StudioBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectAll: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onExtract: () -> Unit
) {
    TopAppBar(
        title = { Text("$count selected", style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onRotate) {
                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = "Rotate")
            }
            IconButton(onClick = onCrop) {
                Icon(Icons.Rounded.Crop, contentDescription = "Crop")
            }
            IconButton(onClick = onExtract) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Extract to a new PDF")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
private fun StudioActionBar(
    canSave: Boolean,
    onEdit: () -> Unit,
    onTools: () -> Unit,
    onSave: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BarAction(Icons.Rounded.Edit, "Edit", onClick = onEdit)
            BarAction(Icons.Rounded.Tune, "Tools", onClick = onTools)
            BarAction(Icons.Rounded.SaveAlt, "Save", enabled = canSave, onClick = onSave)
        }
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun PageGrid(
    pages: List<StudioPage>,
    selection: Set<Int>,
    thumbnails: Map<Int, PageThumbnail>,
    onRequestThumbnail: (Int, Int) -> Unit,
    onOpen: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    onClearSelection: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderState(
        order = pages.map { it.originalIndex },
        onCommit = onReorder,
        onClearSelection = onClearSelection
    )
    val order = reorder.order ?: pages.map { it.originalIndex }
    val byIndex = pages.associateBy { it.originalIndex }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(order, key = { _, original -> original }) { position, original ->
            val page = byIndex[original] ?: return@itemsIndexed
            val isDragging = reorder.draggingPosition == position

            PageTile(
                page = page,
                position = position,
                thumbnail = thumbnails[original],
                onRequestThumbnail = onRequestThumbnail,
                isSelected = original in selection,
                isDragging = isDragging,
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = reorder.dragOffset.x
                            translationY = reorder.dragOffset.y
                        }
                    }
                    // Before the gestures, so the press ripple takes the tile's shape instead of
                    // washing a grey rectangle over the whole cell.
                    .clip(TILE_SHAPE)
                    .tileGestures(
                        original = original,
                        position = position,
                        isIdle = selection.isEmpty(),
                        reorder = reorder,
                        gridState = gridState,
                        onOpen = onOpen,
                        onToggle = onToggle
                    )
            )
        }
    }
}

/**
 * Tap, long-press selection and drag-to-reorder all come out of one detector.
 *
 * Splitting them across `clickable` and a long-press drag detector looks equivalent and is not:
 * neither learns about the other, so the finger-up ending a long press is still a click, and it
 * toggles the page the long press just selected. A hold then reads as a no-op.
 */
@Composable
private fun Modifier.tileGestures(
    original: Int,
    position: Int,
    isIdle: Boolean,
    reorder: GridReorderState,
    gridState: LazyGridState,
    onOpen: (Int) -> Unit,
    onToggle: (Int) -> Unit
): Modifier {
    val currentPosition by rememberUpdatedState(position)
    val idle by rememberUpdatedState(isIdle)
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    return this
        .indication(interactionSource, ripple())
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick {
                if (idle) onOpen(currentPosition) else onToggle(original)
                true
            }
            onLongClick {
                onToggle(original)
                true
            }
        }
        // Keyed on the page alone: keying on the selection too would tear the detector down in the
        // middle of the very gesture that changed the selection.
        .pointerInput(original) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                scope.launch { interactionSource.emit(press) }

                var longPressed = false
                var dragging = false
                try {
                    val tapUp = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPressed = true
                        null
                    }

                    if (!longPressed) {
                        if (tapUp == null) {
                            scope.launch { interactionSource.emit(PressInteraction.Cancel(press)) }
                            return@awaitEachGesture
                        }
                        tapUp.consume()
                        scope.launch { interactionSource.emit(PressInteraction.Release(press)) }
                        if (idle) onOpen(currentPosition) else onToggle(original)
                        return@awaitEachGesture
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    reorder.onDragStart(currentPosition, idle, onToggle)
                    dragging = true

                    val dropped = drag(down.id) { change ->
                        reorder.onDrag(change.positionChange(), gridState)
                        change.consume()
                    }
                    dragging = false

                    if (dropped) reorder.onDragEnd() else reorder.onDragCancel()
                    scope.launch {
                        interactionSource.emit(
                            if (dropped) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            }
                        )
                    }
                } finally {
                    if (dragging) {
                        reorder.onDragCancel()
                        scope.launch { interactionSource.emit(PressInteraction.Cancel(press)) }
                    }
                }
            }
        }
}

@Composable
private fun PageTile(
    page: StudioPage,
    position: Int,
    thumbnail: PageThumbnail?,
    onRequestThumbnail: (Int, Int) -> Unit,
    isSelected: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier
) {
    val border = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(page.aspectRatio.coerceIn(0.4f, 2.5f))
                .clip(TILE_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, border, TILE_SHAPE)
                .graphicsLayer { alpha = if (isDragging) 0.85f else 1f }
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val upright = page.rotation % 180 == 0
                // A quarter-turned page is drawn across the tile's height, so that is the edge the
                // rasterised width has to cover.
                val targetWidthPx = if (upright) constraints.maxWidth else constraints.maxHeight

                // Keyed on the resolution in hand, not just the page: saving drops every thumbnail
                // so the tiles re-render from the new file, and a page-only key would never
                // re-request them — the grid would sit on its placeholders forever.
                LaunchedEffect(page.originalIndex, targetWidthPx, thumbnail?.widthPx) {
                    onRequestThumbnail(page.originalIndex, targetWidthPx)
                }

                if (thumbnail == null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.Center),
                        strokeWidth = 2.dp
                    )
                } else {
                    RotatedPage(thumbnail.bitmap, page.rotation, upright)
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (page.markingCount > 0 || page.isCropped) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (page.isCropped) "Cropped" else "Edited",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "${position + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Keeps the page upright inside a tile whose aspect already accounts for the rotation. */
@Composable
private fun BoxWithConstraintsScope.RotatedPage(bitmap: Bitmap, rotation: Int, upright: Boolean) {
    val width = if (upright) maxWidth else maxHeight
    val height = if (upright) maxHeight else maxWidth

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        filterQuality = FilterQuality.High,
        modifier = Modifier
            .align(Alignment.Center)
            .width(width)
            .height(height)
            .graphicsLayer { rotationZ = rotation.toFloat() }
    )
}

@Composable
private fun BusyScrim(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(18.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(label, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private val TILE_SHAPE = RoundedCornerShape(10.dp)

private const val CROP_PREVIEW_WIDTH_PX = 1000
