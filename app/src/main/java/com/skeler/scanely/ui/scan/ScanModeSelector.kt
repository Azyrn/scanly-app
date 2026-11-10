@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.skeler.scanely.R
import com.skeler.scanely.core.scan.ScanMode
import com.skeler.scanely.ui.theme.GestureDefaults
import com.skeler.scanely.ui.theme.RubberBand
import com.skeler.scanely.ui.theme.ScanTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

@StringRes
fun ScanMode.labelRes(): Int = when (this) {
    ScanMode.DOCUMENT -> R.string.scan_mode_document
    ScanMode.ID_CARD -> R.string.scan_mode_id
    ScanMode.WHITEBOARD -> R.string.scan_mode_whiteboard
}

/**
 * The mode row's position, shared by the row and the viewfinder so a swipe on either drags the same row.
 * [position] is fractional while a finger or spring moves it: 1.5 is halfway between ID card and Whiteboard.
 */
@Stable
class ModeRowState internal constructor(initial: ScanMode) {
    internal val position = Animatable(initial.ordinal.toFloat())

    /** Item centres along the row, in px from its start; written by the layout pass. */
    internal var centres: List<Float> = emptyList()
    internal var width = 0f
    internal var selected: ScanMode = initial
    internal var onSelect: (ScanMode) -> Unit = {}
}

/**
 * The selected mode drives the row; a tap or code change settles there without overshoot. A swipe moves
 * the row itself ([modeSwipe]) and reports the mode it lands on, so this effect then has nothing to do.
 */
@Composable
fun rememberModeRowState(selected: ScanMode, onSelect: (ScanMode) -> Unit): ModeRowState {
    val state = remember { ModeRowState(selected) }
    SideEffect {
        state.selected = selected
        state.onSelect = onSelect
    }
    LaunchedEffect(selected) {
        val target = selected.ordinal.toFloat()
        if (state.position.targetValue != target) state.position.animateTo(target, GestureDefaults.settle())
    }
    return state
}

/**
 * Drags the mode row with the finger (spec 3.3). The row follows 1:1 from touch-down and rubber-bands
 * past the first and last mode; a touch on a moving row catches it where it is. On release it springs to
 * [modeReleaseTarget] from the finger's speed. One tick each time the mode under the centre changes.
 * RTL mirrors the direction.
 */
internal suspend fun PointerInputScope.modeSwipe(
    state: ModeRowState,
    rtl: Boolean,
    haptics: HapticFeedback,
    reducedMotion: () -> Boolean
) = coroutineScope {
    val scope = this
    val flick = GestureDefaults.FlickVelocity.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val caught = state.position.isRunning
        if (caught) scope.launch(start = CoroutineStart.UNDISPATCHED) { state.position.stop() }
        val drag = if (state.centres.size < 2) {
            null
        } else {
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        }
        if (drag == null) {
            if (caught) {
                scope.launch { state.position.animateTo(state.selected.ordinal.toFloat(), GestureDefaults.settle()) }
            }
            return@awaitEachGesture
        }
        val row = ModeRowDrag(state, rtl, haptics, scope)
        row.begin(down, drag)
        val completed = horizontalDrag(drag.id) { change ->
            row.move(change)
            change.consume()
        }
        row.release(completed, flick, reducedMotion())
    }
}

/** One drag of the mode row, from slop to release; see [modeSwipe]. */
private class ModeRowDrag(
    private val state: ModeRowState,
    private val rtl: Boolean,
    private val haptics: HapticFeedback,
    private val scope: CoroutineScope
) {
    private val centres = state.centres
    private val first = centres.first()
    private val last = centres.last()
    private val dimension = state.width
    private val tracker = VelocityTracker()

    // Finger travel in row px; the band is undone so a row caught past its end resumes without a jump.
    private var raw = RubberBand.unbanded(centreAt(centres, state.position.value), first, last, dimension)
    private var tick = state.position.value.roundToInt()

    fun begin(down: PointerInputChange, slop: PointerInputChange) {
        tracker.addPointerInputChange(down)
        tracker.addPointerInputChange(slop)
        follow(slop.position.x - down.position.x)
    }

    fun move(change: PointerInputChange) {
        tracker.addPointerInputChange(change)
        follow(change.positionChange().x)
    }

    private fun follow(dx: Float) {
        raw += if (rtl) dx else -dx
        val shown = positionAt(centres, RubberBand.banded(raw, first, last, dimension))
        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.position.snapTo(shown) }
        val nearest = shown.roundToInt().coerceIn(0, centres.lastIndex)
        if (nearest != tick) {
            tick = nearest
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    fun release(completed: Boolean, flick: Float, reducedMotion: Boolean) {
        val start = state.selected.ordinal
        val fingerVelocity = if (completed) tracker.calculateVelocity().x else 0f
        val rowVelocity = if (rtl) fingerVelocity else -fingerVelocity
        val target = if (completed) {
            modeReleaseTarget(start, state.position.value, rowVelocity, flick, centres.lastIndex)
        } else {
            start
        }
        val segment = ((last - first) / centres.lastIndex).coerceAtLeast(1f)
        val spec = if (target == start) {
            GestureDefaults.snapBack<Float>(reducedMotion)
        } else {
            GestureDefaults.release(reducedMotion)
        }
        val velocity = GestureDefaults.releaseVelocity(rowVelocity / segment, reducedMotion)
        scope.launch { state.position.animateTo(target.toFloat(), spec, initialVelocity = velocity) }
        if (target != start) state.onSelect(ScanMode.entries[target])
    }
}

/**
 * Where the mode row goes when the finger lifts at fractional [position], having started on [start]:
 * faster than [flick] px/s ([velocity] is along the row, positive towards later modes) it goes to the next
 * mode in that direction, like a pager fling; slower, to the mode it was dragged at least
 * [GestureDefaults.DISMISS_DISTANCE_FRACTION] of the way to, else back to [start].
 */
internal fun modeReleaseTarget(start: Int, position: Float, velocity: Float, flick: Float, lastIndex: Int): Int {
    val target = when {
        velocity >= flick -> floor(position).toInt() + 1
        velocity <= -flick -> ceil(position).toInt() - 1
        else -> {
            val travelled = position - start
            val steps = floor(abs(travelled) + 1f - GestureDefaults.DISMISS_DISTANCE_FRACTION).toInt()
            start + sign(travelled).toInt() * steps
        }
    }
    return target.coerceIn(0, lastIndex)
}

/**
 * The mode row (spec 3.3): Document, ID card, Whiteboard from start; the selected one sits at the centre of
 * the screen. The row follows a swipe and springs to a mode on release ([modeSwipe]); a tap settles it.
 * Placed with placeRelative, so it mirrors in RTL. The position is read only in the layout pass, so a
 * drag frame re-places the items without recomposing them.
 */
@Composable
fun ScanModeSelector(state: ModeRowState, selected: ScanMode, modifier: Modifier = Modifier) {
    val gap = ScanTokens.Space2
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(ScanTokens.TouchTarget)
            .selectableGroup()
            .testTag(ScanTags.CAMERA_MODES)
            .pointerInput(state, rtl) { modeSwipe(state, rtl, haptics) { GestureDefaults.reducedMotion(context) } },
        content = { ScanMode.entries.forEach { ModeItem(it, it == selected) { state.onSelect(it) } } }
    ) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val gapPx = gap.roundToPx()
        val starts = items.runningFold(0) { x, item -> x + item.width + gapPx }
        val centres = items.indices.map { starts[it] + items[it].width / 2f }
        val width = constraints.maxWidth
        state.centres = centres
        state.width = width.toFloat()
        layout(width, constraints.maxHeight) {
            val shift = width / 2f - centreAt(centres, state.position.value)
            items.forEachIndexed { i, item -> item.placeRelative((starts[i] + shift).roundToInt(), 0) }
        }
    }
}

/** The centre the row brings to the middle for a fractional (springing) [position]; overshoot extrapolates. */
private fun centreAt(centres: List<Float>, position: Float): Float {
    val i = floor(position).toInt().coerceIn(0, centres.size - 2)
    return centres[i] + (centres[i + 1] - centres[i]) * (position - i)
}

/** Inverse of [centreAt]: the fractional position that brings [centre] to the middle. */
private fun positionAt(centres: List<Float>, centre: Float): Float {
    val i = (centres.indexOfLast { it <= centre }).coerceIn(0, centres.size - 2)
    return i + (centre - centres[i]) / (centres[i + 1] - centres[i])
}

@Composable
private fun ModeItem(mode: ScanMode, selected: Boolean, onClick: () -> Unit) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
        effects,
        label = "modeContainer"
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        effects,
        label = "modeContent"
    )
    // The whole 48 dp slot takes the tap, but the ripple is drawn on the pill only, so the press
    // feedback has the same shape as the selected highlight instead of a grey rectangle.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(ScanTokens.TouchTarget)
            .widthIn(min = ScanTokens.TouchTarget)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .testTag(ScanTags.mode(mode)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(ScanTokens.PillHeight)
                .clip(CircleShape)
                .background(container)
                .indication(interaction, ripple())
                .padding(horizontal = ScanTokens.Space4),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(mode.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
