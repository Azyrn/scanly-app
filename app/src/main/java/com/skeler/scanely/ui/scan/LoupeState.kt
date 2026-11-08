@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.skeler.scanely.ui.theme.ScanTokens
import kotlin.math.roundToInt

/** The finger on a corner, for the loupe: where it is on screen and which corner it holds. */
@Stable
class LoupeState {
    var active by mutableStateOf(false)
    var finger by mutableStateOf(Offset.Zero)
    var corner by mutableIntStateOf(0)

    /** Screen pixels per photo pixel as the photo is shown. */
    var photoScale by mutableFloatStateOf(1f)
}

@Composable
fun rememberLoupeState(): LoupeState = remember { LoupeState() }

/**
 * The photo fitted in the image area, its outline, scrim and four corner handles (spec 4 rows 3–3b). The
 * 24 dp top/bottom padding is half a touch target, so a handle's 48 dp target stays inside the area there.
 * The sides use [ScanTokens.CropPadSide] (14): the photo is width-bound on phones, so a thinner side margin
 * makes it bigger; the 24 dp visible handle still sits fully on screen with 2 dp to spare, and a touch at
 * the screen edge still lands within the handle's target.
 */
@Composable
fun CropImageArea(crop: CropSession?, loupe: LoupeState, onCorner: (Int, NormPoint) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val pad = with(density) { ScanTokens.Space6.toPx() }
    val padSide = with(density) { ScanTokens.CropPadSide.toPx() }
    val target = with(density) { ScanTokens.TouchTarget.toPx() }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val photo = crop?.photo
    val rect = photo?.let {
        placeImage(it.width.toFloat() / it.height, area.width - 2 * padSide, area.height - 2 * pad, fill = false)
    }
    val quad = crop?.quad ?: NormQuad.FULL
    val currentRect by rememberUpdatedState(rect)
    val currentQuad by rememberUpdatedState(quad)
    val currentOnCorner by rememberUpdatedState(onCorner)
    SideEffect { if (photo != null && rect != null) loupe.photoScale = rect.width / photo.width }

    Box(
        modifier = modifier
            .testTag(ScanTags.CROP_IMAGE_AREA)
            .onGloballyPositioned {
                area = it.size
                origin = it.positionInRoot()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = currentRect ?: return@awaitEachGesture
                    val at = down.position - Offset(padSide, pad)
                    val corner = r.cornerAt(currentQuad, at.x, at.y, target) ?: return@awaitEachGesture
                    val (cx, cy) = r.toView(currentQuad.corners[corner])
                    val grab = Offset(cx, cy) - at
                    down.consume()
                    loupe.corner = corner
                    loupe.finger = origin + down.position
                    loupe.active = true
                    drag(down.id) { change ->
                        val p = change.position - Offset(padSide, pad) + grab
                        currentOnCorner(corner, r.toNorm(p.x, p.y))
                        loupe.finger = origin + change.position
                        change.consume()
                    }
                    loupe.active = false
                }
            }
            .padding(horizontal = ScanTokens.CropPadSide, vertical = ScanTokens.Space6)
    ) {
        if (photo == null || rect == null) return@Box
        val image = remember(photo) { photo.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .testTag(ScanTags.CROP_PHOTO)
        )
        QuadAndScrim(rect, quad)
        quad.corners.forEachIndexed { i, corner -> Handle(i, rect.toView(corner), target) }
    }
}

@Composable
private fun QuadAndScrim(rect: ViewRect, quad: NormQuad) {
    val colors = MaterialTheme.colorScheme
    val stroke = with(LocalDensity.current) { ScanTokens.Stroke.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
            quad.corners.map {
                rect.toView(
                    it
                )
            }.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
            close()
        }
        clipPath(path, ClipOp.Difference) {
            drawRect(
                colors.scrim.copy(alpha = ScanTokens.SCRIM_ALPHA),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height)
            )
        }
        drawPath(path, colors.primary, style = Stroke(stroke))
    }
}

/** A 24 dp `surface` circle with a 2 dp `primary` ring, centred in its 48 dp target on the corner. */
@Composable
private fun Handle(index: Int, at: Pair<Float, Float>, target: Float) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val radius = with(density) { ScanTokens.HandleSize.toPx() } / 2f
    val stroke = with(density) { ScanTokens.Stroke.toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.first - target / 2f).roundToInt(), (at.second - target / 2f).roundToInt()) }
            .size(ScanTokens.TouchTarget)
            // A handle sits 14 dp from the screen edge, inside the back-gesture zone: claim just its target so
            // dragging it moves the corner instead of leaving the screen. The rest of the edge still goes back.
            .systemGestureExclusion()
            .testTag(ScanTags.handle(index))
            .drawBehind {
                drawCircle(colors.surface, radius)
                drawCircle(colors.primary, radius - stroke / 2f, style = Stroke(stroke))
            }
    )
}

/**
 * The loupe (spec 4 row 3c): the photo at 3× around the held corner, with a crosshair, above the finger —
 * below it near the top — and kept 16 dp from the screen's sides. Fades and grows in with the fast effects spec.
 */
@Composable
fun Loupe(state: LoupeState, photo: Bitmap, quad: NormQuad) {
    val shown by animateFloatAsState(
        if (state.active) 1f else 0f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "loupe"
    )
    if (shown <= 0f) return
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val radius = with(density) { ScanTokens.LoupeSize.toPx() } / 2f
    val lift = with(density) { ScanTokens.LoupeLift.toPx() }
    val margin = with(density) { ScanTokens.Space4.toPx() }
    val hair = with(density) { ScanTokens.HairStroke.toPx() }
    val screenWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    val (cx, cy) = loupeCentre(state.finger.x, state.finger.y, screenWidth, radius, lift, margin)
    val image = remember(photo) { photo.asImageBitmap() }
    val focus = quad.corners[state.corner]

    Box(
        Modifier
            .offset { IntOffset((cx - radius).roundToInt(), (cy - radius).roundToInt()) }
            .size(ScanTokens.LoupeSize)
            .graphicsLayer {
                alpha = shown
                val scale = ScanTokens.LOUPE_START_SCALE + (1f - ScanTokens.LOUPE_START_SCALE) * shown
                scaleX = scale
                scaleY = scale
            }
            .shadow(ScanTokens.LoupeElevation, CircleShape)
            .background(colors.surface, CircleShape)
            .border(ScanTokens.Stroke, colors.primary, CircleShape)
            .testTag(ScanTags.CROP_LOUPE)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val zoom = ScanTokens.LOUPE_ZOOM * state.photoScale
            val left = center.x - focus.x * photo.width * zoom
            val top = center.y - focus.y * photo.height * zoom
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((photo.width * zoom).roundToInt(), (photo.height * zoom).roundToInt())
            )
            drawLine(colors.primary, Offset(0f, center.y), Offset(size.width, center.y), hair)
            drawLine(colors.primary, Offset(center.x, 0f), Offset(center.x, size.height), hair)
        }
    }
}
