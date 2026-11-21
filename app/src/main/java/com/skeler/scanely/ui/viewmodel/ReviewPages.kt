package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import com.skeler.scanely.core.image.ScanFilter

private const val QUARTERS = 4

/**
 * How a page's picture is made from its file, as quarter turns before and after the filter.
 *
 * The AUTO and Whiteboard filters work on a block grid anchored at the top-left corner, so filter-then-turn
 * and turn-then-filter differ by a few pixels. Splitting the turns around the filter replays the edits in the
 * order they were made: a rotation turns the filtered picture, a filter change re-filters the turned original.
 */
data class PageTurns(val beforeFilter: Int = 0, val afterFilter: Int = 0) {

    /** The page turned a quarter clockwise: the filtered picture turns, the filter is not run again. */
    fun rotated() = copy(afterFilter = (afterFilter + 1) % QUARTERS)

    /** A filter change re-filters the original as it is turned now. */
    fun refiltered() = PageTurns((beforeFilter + afterFilter) % QUARTERS, 0)

    val total: Int get() = (beforeFilter + afterFilter) % QUARTERS
}

/** One page under review: its straightened file (written by the scan session) and the edits on it. */
data class ReviewPage(val id: Long, val uri: Uri, val turns: PageTurns = PageTurns())

/** What a page's picture shows: re-rendered when either changes. */
internal data class RenderKey(val filter: ScanFilter, val turns: PageTurns) {
    /** True when [next] is this picture turned once more, which a cheap turn of the picture gives exactly. */
    fun turnsOnceInto(next: RenderKey) =
        next.filter == filter && next.turns.beforeFilter == turns.beforeFilter && next.turns == turns.rotated()
}

/** [source] turned [quarters] times; the pictures made on the way are [recycle]d, [source] is never. */
internal fun <B : Any> rotateQuarters(source: B, quarters: Int, rotateQuarter: (B) -> B, recycle: (B) -> Unit): B {
    var current = source
    repeat(quarters) {
        val next = rotateQuarter(current)
        if (current !== source) recycle(current)
        current = next
    }
    return current
}

/**
 * Renders [source] the way [turns] says: turned, filtered, turned. Every picture made on the way is
 * [recycle]d except the result; [source] stays the caller's.
 */
internal fun <B : Any> renderPage(
    source: B,
    turns: PageTurns,
    rotateQuarter: (B) -> B,
    filter: (B) -> B,
    recycle: (B) -> Unit
): B {
    val turned = rotateQuarters(source, turns.beforeFilter, rotateQuarter, recycle)
    val filtered = filter(turned)
    if (turned !== source && turned !== filtered) recycle(turned)
    val result = rotateQuarters(filtered, turns.afterFilter, rotateQuarter, recycle)
    if (result !== filtered && filtered !== source) recycle(filtered)
    return result
}

/** Ids of the pages held in memory for display: the current one first, then its neighbours. */
internal fun windowIds(pages: List<ReviewPage>, current: Int): List<Long> {
    if (pages.isEmpty()) return emptyList()
    val at = current.coerceIn(0, pages.lastIndex)
    return listOf(at, at + 1, at - 1).filter { it in pages.indices }.map { pages[it].id }
}
