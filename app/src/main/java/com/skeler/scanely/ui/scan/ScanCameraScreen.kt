@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import android.Manifest
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.R
import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.scan.ScanMode
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.navigation.OnScreenExit
import com.skeler.scanely.ui.theme.ScanTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "ScanCamera"
private const val FOCUS_SECONDS = 3L
private const val FPS_FILE = "scan_fps.txt"

/**
 * The scan camera (spec 3). [sessionKey] identifies this screen's back-stack entry, so a rotation or a
 * return from the crop keeps the session; the other arguments come from [Destination.ScanCamera].
 */
@Composable
fun ScanCameraScreen(sessionKey: String, mode: ScanMode?, kind: IdDocumentKind?, replaceSide: Int?) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: ScanSessionViewModel = hiltViewModel(activity)
    LaunchedEffect(sessionKey) { vm.begin(sessionKey, mode, kind, replaceSide) }
    OnScreenExit { vm.end() }
    CameraContent(vm)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraContent(vm: ScanSessionViewModel) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) { if (!permission.status.isGranted) permission.launchPermissionRequest() }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var auto by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val captureFailed = stringResource(R.string.scr_camera_capture_failed)
    val live = remember { MutableStateFlow(LiveView()) }
    val liveView by live.collectAsStateWithLifecycle()
    val analyzer = remember {
        val log = if (BuildConfig.DEBUG) FpsLog(File(context.filesDir, FPS_FILE)) else null
        LiveAnalyzer(log) { live.value = it }
    }
    val ring = remember { Animatable(0f) }

    ScanEventHandler(vm, state.event)

    val openCrop: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            vm.openPhotos(uris)
            navigator.go(Destination.ScanCrop)
        }
    }
    val shoot: () -> Unit = shoot@{
        val bound = capture ?: return@shoot
        if (capturing) return@shoot
        capturing = true
        scope.launch {
            flash.animateTo(
                ScanTokens.CAPTURE_FLASH_ALPHA,
                tween(ScanTokens.CAPTURE_FLASH_MILLIS, easing = LinearEasing)
            )
            flash.animateTo(0f, tween(ScanTokens.CAPTURE_FLASH_MILLIS, easing = LinearEasing))
        }
        analyzer.restartSteadiness()
        takePhoto(context, bound, vm.files.newCaptureFile(), onSaved = { file ->
            capturing = false
            openCrop(listOf(Uri.fromFile(file)))
        }, onError = { e ->
            capturing = false
            Log.e(TAG, "Capture failed", e)
            Toast.makeText(context, captureFailed, Toast.LENGTH_SHORT).show()
        })
    }

    AutoCapture(
        ring = ring,
        armed = auto && capture != null && !capturing,
        steady = liveView.steady,
        windowMillis = analyzer.windowMillis,
        onCapture = shoot
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ScanTags.CAMERA_ROOT)
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CameraTopBar(
            auto = auto,
            onAuto = { auto = it },
            torch = torch,
            hasTorch = camera?.cameraInfo?.hasFlashUnit() == true,
            onTorch = { on ->
                torch = on
                camera?.cameraControl?.enableTorch(on)
            },
            onClose = { navigator.back() }
        )
        Viewfinder(
            state = state,
            granted = permission.status.isGranted,
            flashAlpha = flash.value,
            liveView = liveView,
            analyzer = analyzer,
            onBound = { c, cap ->
                camera = c
                capture = cap
                c.cameraControl.enableTorch(torch)
            },
            onFocus = { previewView, x, y ->
                val point = previewView.meteringPointFactory.createPoint(x, y)
                val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(FOCUS_SECONDS, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            },
            onMode = vm::setMode,
            onKind = vm::setKind
        )
        Spacer(Modifier.height(ScanTokens.Space2))
        ScanModeSelector(selected = state.mode, onSelect = vm::setMode)
        Spacer(Modifier.height(ScanTokens.Space2))
        BottomBar(
            state = state,
            ring = ring.value,
            canShoot = capture != null && !capturing,
            onShutter = shoot,
            onImport = openCrop,
            onReview = vm::finishNow
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun CameraTopBar(
    auto: Boolean,
    onAuto: (Boolean) -> Unit,
    torch: Boolean,
    hasTorch: Boolean,
    onTorch: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScanTokens.TopBarHeight)
            .testTag(ScanTags.CAMERA_TOP_BAR) // the whole bar, before its padding (spec: W x 64)
            .padding(horizontal = ScanTokens.TopBarPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(ScanTags.CAMERA_CLOSE)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.scan_close),
                tint = colors.onSurface,
                modifier = Modifier.size(ScanTokens.IconSize)
            )
        }
        Spacer(Modifier.weight(1f))
        ToggleButton(
            checked = auto,
            onCheckedChange = onAuto,
            modifier = Modifier.height(ScanTokens.SmallControlHeight).testTag(ScanTags.CAMERA_AUTO),
            colors = ToggleButtonDefaults.colors(
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                checkedContainerColor = colors.secondaryContainer,
                checkedContentColor = colors.onSecondaryContainer
            ),
            contentPadding = PaddingValues(horizontal = ScanTokens.Space4)
        ) {
            Text(stringResource(R.string.scan_auto_capture), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Spacer(Modifier.width(ScanTokens.Space2))
        IconButton(
            onClick = { onTorch(!torch) },
            enabled = hasTorch,
            modifier = Modifier.testTag(ScanTags.CAMERA_FLASH)
        ) {
            Icon(
                if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = stringResource(R.string.scan_flash),
                tint = if (torch) colors.primary else colors.onSurface,
                modifier = Modifier.size(ScanTokens.IconSize)
            )
        }
    }
}

@Composable
private fun ColumnScope.Viewfinder(
    state: ScanSessionUiState,
    granted: Boolean,
    flashAlpha: Float,
    liveView: LiveView,
    analyzer: LiveAnalyzer,
    onBound: (Camera, ImageCapture) -> Unit,
    onFocus: (PreviewView, Float, Float) -> Unit,
    onMode: (ScanMode) -> Unit,
    onKind: (IdDocumentKind) -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val swipe = with(LocalDensity.current) { ScanTokens.ModeSwipeDistance.toPx() }
    // Dark theme: the preview runs edge to edge, as in the spec. Light theme: it sits as an inset card with
    // large corners and a soft shadow tinted with the app colour, so the black feed reads as a window
    // set into the light screen rather than a hole cut through it.
    val light = isLightScan()
    val shape = if (light) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.medium
    val inset = if (light) Modifier.padding(horizontal = ScanTokens.Space3) else Modifier
    val card = if (light) {
        Modifier
            .shadow(
                ScanTokens.ViewfinderElevationLight,
                shape,
                ambientColor = colors.primary,
                spotColor = colors.primary
            )
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .then(inset) // before the ratio, so the inset card keeps the sensor's 3:4
            .aspectRatio(ScanTokens.VIEWFINDER_ASPECT)
            .then(card)
            .clip(shape)
            .background(colors.surfaceContainerLowest)
            .onSizeChanged { size = it }
            .testTag(ScanTags.CAMERA_VIEWFINDER)
            .pointerInput(granted) { detectTapGestures { onFocus(previewView, it.x, it.y) } }
            .pointerInput(state.mode, rtl) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = {
                        if (abs(travel) >= swipe) {
                            val forward = (travel < 0) != rtl
                            val next = state.mode.ordinal + if (forward) 1 else -1
                            ScanMode.entries.getOrNull(next)?.let(onMode)
                        }
                    }
                ) { _, dx -> travel += dx }
            },
        contentAlignment = Alignment.Center
    ) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            BindScanCamera(previewView, analyzer, onBound)
        } else {
            Text(
                text = stringResource(R.string.scr_camera_permission_required),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(ScanTokens.Space6).testTag(ScanTags.CAMERA_PERMISSION)
            )
        }
        val side = state.side
        val viewfinderWidth = maxWidth
        Crossfade(
            targetState = Triple(state.mode, side?.kind, side?.index),
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "modeOverlay"
        ) { (mode, kind, index) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (mode == ScanMode.ID_CARD && kind != null) {
                    IdFrameOverlay(kind, viewfinderWidth)
                } else {
                    LiveQuadOverlay(liveView, size)
                }
                GuidancePill(
                    text = guidanceText(mode, kind, index),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ScanTokens.Space4)
                )
            }
        }
        if (state.goal is ScanGoal.IdCopy) {
            KindToggle(
                kind = state.goal.kind,
                onKind = onKind,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = ScanTokens.Space4)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flashAlpha }
                .background(colors.surface)
        )
    }
}

/** The pill's text; an ID side reads "Front — 1 of 2", its numbers formatted for the locale. */
@Composable
private fun guidanceText(mode: ScanMode, kind: IdDocumentKind?, index: Int?): String = when {
    mode == ScanMode.WHITEBOARD -> stringResource(R.string.scan_guide_whiteboard)
    mode != ScanMode.ID_CARD -> stringResource(R.string.scan_guide_document)
    kind == null || kind == IdDocumentKind.PASSPORT -> stringResource(R.string.scan_side_photo_page)
    else -> {
        val side = (index ?: 0) + 1
        stringResource(if (index == 1) R.string.scan_side_back else R.string.scan_side_front, side, kind.sides)
    }
}

/**
 * Auto-capture (spec 3.4): while armed and the outline holds still, the shutter ring fills over the
 * steadiness window (linear), then the photo is taken; movement drains it with the fast effects spec.
 */
@Composable
private fun AutoCapture(
    ring: Animatable<Float, *>,
    armed: Boolean,
    steady: Boolean,
    windowMillis: Long,
    onCapture: () -> Unit
) {
    val drain = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(armed, steady) {
        if (armed && steady) {
            val left = ((1f - ring.value) * windowMillis).toInt()
            ring.animateTo(1f, tween(left, easing = LinearEasing))
            onCapture()
            ring.snapTo(0f)
        } else {
            ring.animateTo(0f, drain)
        }
    }
}

/** True when the app is in a light scheme: the scan screens follow the app theme and adapt a few accents. */
@Composable
@ReadOnlyComposable
internal fun isLightScan(): Boolean = MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND

/** The same threshold the app theme uses to pick dark system-bar icons. */
private const val LIGHT_BACKGROUND = 0.5f
