package com.skeler.scanely.history.presentation.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import com.skeler.scanely.ui.theme.GestureDefaults
import com.skeler.scanely.ui.theme.RubberBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private enum class RowSwipe { Settled, Deleted }

/**
 * A row that deletes when swiped towards the start (end-to-start; RTL mirrors it).
 *
 * The row is the finger while it is down. Released faster than [GestureDefaults.FlickVelocity] towards
 * the start, or slower but past [GestureDefaults.DISMISS_DISTANCE_FRACTION] of its width, it slides out
 * on a spring seeded with the finger's speed and [onDelete] runs; otherwise it springs back, stiffer.
 * Swiped the other way it rubber-bands instead of stopping dead. One haptic tick when the finger
 * crosses the delete point. [background] gets the reveal progress (0 to 1 at the delete point), read
 * from the same offset as the row, for anything it draws in a layout or draw lambda.
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    background: @Composable (progress: () -> Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val flick = with(LocalDensity.current) { GestureDefaults.FlickVelocity.toPx() }
    val state = remember { AnchoredDraggableState(RowSwipe.Settled) }
    val overscroll = remember(scope) { RubberBandOverscroll(scope) { GestureDefaults.reducedMotion(context) } }
    val fling = remember(state, flick) { DeleteFling(state, flick) { GestureDefaults.reducedMotion(context) } }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    var width by remember { mutableFloatStateOf(0f) }
    val offset = { state.offset.takeUnless { it.isNaN() } ?: 0f }
    val progress = {
        if (width == 0f) 0f else (-offset() / (width * GestureDefaults.DISMISS_DISTANCE_FRACTION)).coerceIn(0f, 1f)
    }
    val currentOnDelete by rememberUpdatedState(onDelete)

    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { if (it == RowSwipe.Deleted) currentOnDelete() }
    }
    LaunchedEffect(state) {
        snapshotFlow { dragged && progress() >= 1f }
            .distinctUntilChanged()
            .drop(1)
            .collect { past -> if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                width = it.width.toFloat()
                overscroll.dimension = width
                state.updateAnchors(
                    DraggableAnchors {
                        RowSwipe.Settled at 0f
                        RowSwipe.Deleted at -width
                    }
                )
            }
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                interactionSource = interaction,
                overscrollEffect = overscroll,
                flingBehavior = fling
            )
    ) {
        // Only a swipe towards delete reveals it; the rubber band the other way shows nothing behind.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = if (offset() < 0f) 1f else 0f }) { background(progress) }
        Box(Modifier.offset { IntOffset((offset() + overscroll.shown).roundToInt(), 0) }) { content() }
    }
}

/** Release rule for [SwipeToDeleteRow]: velocity or distance deletes, anything else springs back, stiffer. */
private class DeleteFling(
    private val state: AnchoredDraggableState<RowSwipe>,
    private val flick: Float,
    private val reducedMotion: () -> Boolean
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val start = state.offset.takeUnless { it.isNaN() } ?: return initialVelocity
        val width = -state.anchors.positionOf(RowSwipe.Deleted)
        val delete = initialVelocity <= -flick ||
            (initialVelocity < flick && -start >= width * GestureDefaults.DISMISS_DISTANCE_FRACTION)
        val reduced = reducedMotion()
        val spec = if (delete) GestureDefaults.release<Float>(reduced) else GestureDefaults.snapBack(reduced)
        // Each frame scrolls to the spring's value from where the row really is: the anchors clip the
        // spring's overshoot, and stepping by the spring's own deltas would then leave the row short of
        // the anchor, never settling (and never deleting).
        animate(
            initialValue = start,
            targetValue = if (delete) -width else 0f,
            initialVelocity = GestureDefaults.releaseVelocity(initialVelocity, reduced),
            animationSpec = spec
        ) { value, _ -> scrollBy(value - state.requireOffset()) }
        return 0f
    }
}

/**
 * Drag past the row's anchors (the disabled swipe direction) rubber-bands into [shown] instead of
 * stopping; it springs back on release. A drag back the other way takes the band up first.
 */
private class RubberBandOverscroll(
    private val scope: CoroutineScope,
    private val reducedMotion: () -> Boolean
) : OverscrollEffect {
    private val band = Animatable(0f)
    private var raw = 0f
    var dimension = 0f

    val shown: Float get() = band.value

    override val isInProgress: Boolean get() = band.value != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        var left = delta.x
        if (raw != 0f && sign(left) != sign(raw)) {
            val back = if (abs(left) > abs(raw)) -raw else left
            raw += back
            left -= back
        }
        val consumed = performScroll(Offset(left, 0f)).x
        if (source == NestedScrollSource.UserInput) raw += left - consumed
        val target = RubberBand.rubberBand(raw, dimension)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { band.snapTo(target) }
        return delta
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        raw = 0f
        if (band.value != 0f) scope.launch { band.animateTo(0f, GestureDefaults.snapBack(reducedMotion())) }
        performFling(velocity)
    }
}
