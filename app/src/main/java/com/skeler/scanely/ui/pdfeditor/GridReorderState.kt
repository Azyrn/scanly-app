package com.skeler.scanely.ui.pdfeditor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.skeler.scanely.ui.theme.GestureDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Long-press drag-to-reorder for the page grid.
 *
 * One gesture serves both jobs the grid needs: a long press that ends where it started selects the
 * page, and a long press that moves reorders it. The selection made at press time is taken back
 * once the finger travels far enough to be a drag, so the user never has to choose a mode first.
 *
 * The lifted tile is the finger while it is down ([offsetOf], read in a graphicsLayer). When the
 * finger lifts, the tile springs into its slot from the finger's speed; a long press on it while it
 * settles catches it where it is. Its lift ([liftOf]) is derived from that same offset on the way
 * down, so scale, shadow and alpha land exactly when the tile does.
 */
@Stable
class GridReorderState internal constructor(
    private val onCommit: (List<Int>) -> Unit,
    private val onClearSelection: () -> Unit,
    private val scope: CoroutineScope,
    private val slopPx: Float
) {
    /** Non-null only while a drag is in flight; the grid renders this order instead. */
    var order by mutableStateOf<List<Int>?>(null)
        internal set

    var draggingPosition by mutableStateOf<Int?>(null)
        private set

    /** The page (original index) that is lifted or still settling into its slot. */
    var lifted by mutableStateOf<Int?>(null)
        private set

    internal var source: List<Int> = emptyList()
    internal var haptics: HapticFeedback? = null

    private val offset = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val pickUp = Animatable(0f)
    private var dropDistance = 0f
    private var travelled = Offset.Zero
    private var selectedByThisGesture = false
    private var isReordering = false

    /** The lifted tile's offset from its slot; read it in a draw-phase lambda. */
    fun offsetOf(page: Int): Offset = if (page == lifted) offset.value else Offset.Zero

    /** 0 at rest, 1 fully lifted; follows the offset while the tile settles. */
    fun liftOf(page: Int): Float = when {
        page != lifted -> 0f
        draggingPosition != null -> pickUp.value
        dropDistance > 0f -> (offset.value.getDistance() / dropDistance).coerceIn(0f, 1f)
        else -> pickUp.value
    }

    fun onDragStart(position: Int, isIdle: Boolean, onToggle: (Int) -> Unit) {
        val page = source.getOrNull(position)
        // A tile still settling from the last drop is caught where it is, not snapped home.
        val caught = page != null && page == lifted
        order = source
        draggingPosition = position
        lifted = page
        dropDistance = 0f
        travelled = Offset.Zero
        isReordering = false
        selectedByThisGesture = isIdle
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!caught) offset.snapTo(Offset.Zero) else offset.stop()
        }
        scope.launch { pickUp.animateTo(1f, GestureDefaults.press()) }
        if (isIdle) page?.let(onToggle)
    }

    fun onDrag(delta: Offset, gridState: LazyGridState) {
        travelled += delta
        if (!isReordering) {
            if (travelled.getDistance() < slopPx) return
            isReordering = true
            if (selectedByThisGesture) onClearSelection()
        }
        moveBy(delta)
        moveDraggedTile(gridState.layoutInfo.visibleItemsInfo)
    }

    private fun moveBy(delta: Offset) {
        val next = offset.value + delta
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    /** Swaps the dragged tile into whichever visible tile its centre is now over. */
    private fun moveDraggedTile(items: List<LazyGridItemInfo>) {
        val dragged = draggingPosition?.let { position -> items.firstOrNull { it.index == position } } ?: return
        val current = dragged.index
        val centre = Offset(
            dragged.offset.x + dragged.size.width / 2f,
            dragged.offset.y + dragged.size.height / 2f
        ) + offset.value

        val target = items.firstOrNull { it.index != current && it.contains(centre) } ?: return
        val moved = order?.toMutableList()?.takeIf { current in it.indices && target.index in it.indices } ?: return
        moved.add(target.index, moved.removeAt(current))
        order = moved
        // The tile has swapped into the target's slot, so the visual offset that carried it there
        // has to be given back or it would jump a full cell.
        moveBy(
            -Offset(
                (target.offset.x - dragged.offset.x).toFloat(),
                (target.offset.y - dragged.offset.y).toFloat()
            )
        )
        draggingPosition = target.index
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    /** The finger lifted after a drag; [velocity] is its speed in px/s. */
    fun onDragEnd(velocity: Offset, reducedMotion: Boolean) {
        val finished = order.takeIf { isReordering }
        settle(velocity, reducedMotion)
        if (finished != null && finished != source) {
            onCommit(finished)
        } else {
            order = null
        }
    }

    fun onDragCancel() {
        order = null
        settle(Offset.Zero, reducedMotion = false)
    }

    private fun settle(velocity: Offset, reducedMotion: Boolean) {
        val page = lifted
        draggingPosition = null
        travelled = Offset.Zero
        selectedByThisGesture = false
        isReordering = false
        dropDistance = offset.value.getDistance()
        val start = if (reducedMotion) Offset.Zero else velocity
        scope.launch {
            if (dropDistance > 0f) {
                offset.animateTo(
                    Offset.Zero,
                    GestureDefaults.release(reducedMotion, Offset.VisibilityThreshold),
                    initialVelocity = start
                )
            } else {
                pickUp.animateTo(0f, GestureDefaults.press())
            }
            if (lifted == page && draggingPosition == null) {
                lifted = null
                pickUp.snapTo(0f)
            }
        }
    }
}

@Composable
fun rememberReorderState(
    order: List<Int>,
    onCommit: (List<Int>) -> Unit,
    onClearSelection: () -> Unit
): GridReorderState {
    val scope = rememberCoroutineScope()
    val slop = with(LocalDensity.current) { GestureDefaults.ReorderSlop.toPx() }
    val state = remember { GridReorderState(onCommit, onClearSelection, scope, slop) }
    state.source = order
    state.haptics = LocalHapticFeedback.current
    // The dragged order is held past the drop so the grid doesn't flash back to the old order
    // while the staged reorder makes its way through the edit stack.
    LaunchedEffect(order) { if (state.order == order) state.order = null }
    return state
}

private fun LazyGridItemInfo.contains(point: Offset): Boolean =
    point.x in offset.x.toFloat()..(offset.x + size.width).toFloat() &&
        point.y in offset.y.toFloat()..(offset.y + size.height).toFloat()
