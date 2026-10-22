package com.skeler.scanely.ui.pdfeditor

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Long-press drag-to-reorder for the page grid.
 *
 * One gesture serves both jobs the grid needs: a long press that ends where it started selects the
 * page, and a long press that moves reorders it. The selection made at press time is taken back
 * once the finger travels far enough to be a drag, so the user never has to choose a mode first.
 */
@Stable
class GridReorderState(
    private val onCommit: (List<Int>) -> Unit,
    private val onClearSelection: () -> Unit
) {
    /** Non-null only while a drag is in flight; the grid renders this order instead. */
    var order by mutableStateOf<List<Int>?>(null)
        internal set

    var draggingPosition by mutableStateOf<Int?>(null)
        private set

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    internal var source: List<Int> = emptyList()

    private var travelled = Offset.Zero
    private var selectedByThisGesture = false
    private var isReordering = false

    fun onDragStart(position: Int, isIdle: Boolean, onToggle: (Int) -> Unit) {
        order = source
        draggingPosition = position
        dragOffset = Offset.Zero
        travelled = Offset.Zero
        isReordering = false
        selectedByThisGesture = isIdle
        if (isIdle) source.getOrNull(position)?.let(onToggle)
    }

    fun onDrag(delta: Offset, gridState: LazyGridState) {
        travelled += delta
        if (!isReordering) {
            if (travelled.getDistance() < DRAG_THRESHOLD_PX) return
            isReordering = true
            if (selectedByThisGesture) onClearSelection()
        }

        dragOffset += delta

        val current = draggingPosition ?: return
        val items = gridState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.index == current } ?: return
        val centre = Offset(
            dragged.offset.x + dragged.size.width / 2f,
            dragged.offset.y + dragged.size.height / 2f
        ) + dragOffset

        val target = items.firstOrNull { item ->
            item.index != current &&
                centre.x >= item.offset.x &&
                centre.x <= item.offset.x + item.size.width &&
                centre.y >= item.offset.y &&
                centre.y <= item.offset.y + item.size.height
        } ?: return

        val moved = (order ?: return).toMutableList()
        if (current !in moved.indices || target.index !in moved.indices) return
        moved.add(target.index, moved.removeAt(current))
        order = moved
        // The tile has swapped into the target's slot, so the visual offset that carried it there
        // has to be given back or it would jump a full cell.
        dragOffset -= Offset(
            (target.offset.x - dragged.offset.x).toFloat(),
            (target.offset.y - dragged.offset.y).toFloat()
        )
        draggingPosition = target.index
    }

    fun onDragEnd() {
        val finished = order.takeIf { isReordering }
        reset()
        if (finished != null && finished != source) {
            onCommit(finished)
        } else {
            order = null
        }
    }

    fun onDragCancel() {
        order = null
        reset()
    }

    private fun reset() {
        draggingPosition = null
        dragOffset = Offset.Zero
        travelled = Offset.Zero
        selectedByThisGesture = false
        isReordering = false
    }

    private companion object {
        const val DRAG_THRESHOLD_PX = 24f
    }
}

@Composable
fun rememberReorderState(
    order: List<Int>,
    onCommit: (List<Int>) -> Unit,
    onClearSelection: () -> Unit
): GridReorderState {
    val state = remember { GridReorderState(onCommit, onClearSelection) }
    state.source = order
    // The dragged order is held past the drop so the grid doesn't flash back to the old order
    // while the staged reorder makes its way through the edit stack.
    LaunchedEffect(order) { if (state.order == order) state.order = null }
    return state
}
