package com.skeler.scanely.core.pdfeditor.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditStack(
    val applied: List<PdfEdit> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val isDirty: Boolean get() = applied.isNotEmpty()
}

/**
 * The staged edit stack for one open document.
 *
 * Edits are held in memory, which collapses a multi-step session into one save and makes undo free.
 * Undo works on whole states rather than popping the last edit: the eraser removes edits from the
 * middle of the list, and a pop-based stack cannot put those back.
 */
class EditSession {

    private val _stack = MutableStateFlow(EditStack())
    val stack: StateFlow<EditStack> = _stack.asStateFlow()

    private val past = ArrayDeque<List<PdfEdit>>()
    private val future = ArrayDeque<List<PdfEdit>>()

    /** Non-null while a drag is in flight; the state to undo back to when it ends. */
    private var gestureOrigin: List<PdfEdit>? = null

    fun push(edit: PdfEdit) = mutate { it + edit }

    fun remove(edit: PdfEdit) = mutate { edits ->
        val index = edits.indexOfLast { it == edit }
        if (index < 0) edits else edits.toMutableList().apply { removeAt(index) }
    }

    /** Rewrites the whole list at once, for edits that touch several entries — the eraser does. */
    fun replaceAll(transform: (List<PdfEdit>) -> List<PdfEdit>) = mutate(transform)

    /**
     * Collapses everything until [endGesture] into one undo step.
     *
     * An eraser drag mutates the stack on every touch sample, and without this a single wipe would
     * cost dozens of taps on undo to walk back.
     */
    fun beginGesture() {
        if (gestureOrigin == null) gestureOrigin = _stack.value.applied
    }

    fun endGesture() {
        val origin = gestureOrigin ?: return
        gestureOrigin = null
        if (origin == _stack.value.applied) return
        recordHistory(origin)
        publish(_stack.value.applied)
    }

    fun undo() {
        val previous = past.removeLastOrNull() ?: return
        future.addLast(_stack.value.applied)
        publish(previous)
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        past.addLast(_stack.value.applied)
        publish(next)
    }

    fun clear() {
        gestureOrigin = null
        past.clear()
        future.clear()
        publish(emptyList())
    }

    /**
     * Structural edits first, then markings.
     *
     * Order matters and is not the order the user made them in: a marking's page index refers to
     * the page the user was looking at, so deletions and reorders have to settle before anything
     * is painted, or a highlight lands on the wrong page.
     */
    fun plan(): EditPlan {
        val applied = _stack.value.applied
        return EditPlan(
            structural = applied.filterIsInstance<PdfEdit.Structural>(),
            markings = applied.filterIsInstance<PdfEdit.Marking>().groupBy { it.pageIndex }
        )
    }

    private fun mutate(transform: (List<PdfEdit>) -> List<PdfEdit>) {
        val current = _stack.value.applied
        val next = transform(current)
        if (next == current) return

        if (gestureOrigin == null) recordHistory(current)
        publish(next)
    }

    private fun recordHistory(state: List<PdfEdit>) {
        past.addLast(state)
        if (past.size > MAX_HISTORY) past.removeFirst()
        // A new edit invalidates the redo branch; keeping it would let redo resurrect an edit
        // whose page indices no longer exist.
        future.clear()
    }

    private fun publish(applied: List<PdfEdit>) {
        _stack.value = EditStack(applied, past.isNotEmpty(), future.isNotEmpty())
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}

data class EditPlan(
    val structural: List<PdfEdit.Structural>,
    val markings: Map<Int, List<PdfEdit.Marking>>
) {
    val isEmpty: Boolean get() = structural.isEmpty() && markings.isEmpty()
}
