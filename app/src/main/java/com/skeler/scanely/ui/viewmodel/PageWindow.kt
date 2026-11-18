package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import com.skeler.scanely.core.image.ScanFilter

/** The picture work [PageWindow] needs; blocking calls, run through [background]. */
internal interface PageOps<B : Any> {
    /** The page's file at display size, upright; null when it can't be read. */
    fun decode(uri: Uri): B?
    fun rotateQuarter(picture: B): B
    fun filter(picture: B, filter: ScanFilter): B

    /** A small copy of [picture] for the filter strip; may return [picture] itself when it is already small. */
    fun thumbnail(picture: B): B
    fun recycle(picture: B)

    /** Runs [block] off the main thread. */
    suspend fun <T> background(block: () -> T): T
}

/** What review should show now. */
internal data class WindowTarget(val pages: List<ReviewPage>, val current: Int, val filter: ScanFilter)

/** What the window holds, for the screen. [version] goes up with every change. */
internal data class WindowView<B : Any>(
    val shown: Map<Long, B>,
    val previews: List<Pair<ScanFilter, B>>,
    val unreadable: Set<Long>,
    val version: Long
)

/**
 * Review's pictures in memory: the current page and its neighbours at display size, and the filter strip of
 * the current page. Memory follows what is on screen, not the page count; export makes its own pages.
 *
 * A picture the screen has shown is never recycled until the screen reports, through [drawn], that it has
 * composed a version without it. Pictures it never saw (decoded originals, intermediate steps) are recycled at
 * once. Not thread-safe: [sync] runs one at a time on the main thread and only [PageOps.background] leaves it.
 */
internal class PageWindow<B : Any>(private val ops: PageOps<B>) {

    private val sources = HashMap<Long, B>()
    private val shown = HashMap<Long, Pair<RenderKey, B>>()
    private var previewKey: Pair<Long, Int>? = null
    private var previews: List<Pair<ScanFilter, B>> = emptyList()
    private val unreadable = HashSet<Long>()
    private val retired = mutableListOf<Pair<Long, B>>()
    private var version = 0L

    /** Brings the window to what [target] says, re-reading it after each page; [publish] gets every change. */
    suspend fun sync(target: () -> WindowTarget, publish: (WindowView<B>) -> Unit) {
        if (dropOutside(target())) publish(view())
        for (id in windowIds(target().pages, target().current)) {
            if (renderShown(id, target)) publish(view())
        }
        if (renderPreviews(target())) publish(view())
    }

    /** The screen has composed [shownVersion]: pictures that left before it are no longer drawn. */
    fun drawn(shownVersion: Long) {
        val done = retired.filter { it.first <= shownVersion }
        retired.removeAll(done)
        done.forEach { ops.recycle(it.second) }
    }

    /** Everything held, for recycling once review is gone; the window is empty after this. */
    fun takeAll(): List<B> {
        val all = sources.values + shown.values.map { it.second } +
            previews.map { it.second } + retired.map { it.second }
        sources.clear()
        shown.clear()
        previews = emptyList()
        previewKey = null
        retired.clear()
        return all
    }

    private fun view() = WindowView(shown.mapValues { it.value.second }, previews, unreadable.toSet(), version)

    private fun retire(picture: B) {
        retired += (version + 1) to picture
    }

    private fun dropOutside(target: WindowTarget): Boolean {
        val keep = windowIds(target.pages, target.current).toSet()
        val sourceIt = sources.entries.iterator()
        while (sourceIt.hasNext()) {
            val (id, source) = sourceIt.next()
            if (id !in keep) {
                ops.recycle(source)
                sourceIt.remove()
            }
        }
        val leaving = shown.filterKeys { it !in keep }
        leaving.forEach { (id, entry) ->
            shown.remove(id)
            retire(entry.second)
        }
        unreadable.retainAll(target.pages.map { it.id }.toSet())
        if (leaving.isNotEmpty()) version++
        return leaving.isNotEmpty()
    }

    /** Makes page [id]'s picture match the target; true when it changed. */
    private suspend fun renderShown(id: Long, target: () -> WindowTarget): Boolean {
        val now = target()
        val page = now.pages.firstOrNull { it.id == id } ?: return false
        if (id in unreadable) return false
        val key = RenderKey(now.filter, page.turns)
        val have = shown[id]
        if (have?.first == key) return false
        val picture = if (have != null && have.first.turnsOnceInto(key)) {
            ops.background { ops.rotateQuarter(have.second) }
        } else {
            val source = source(page)
            if (source == null) {
                unreadable += id
                version++
                return true
            }
            ops.background {
                renderPage(source, page.turns, ops::rotateQuarter, { ops.filter(it, now.filter) }, ops::recycle)
            }
        }
        have?.let { retire(it.second) }
        shown[id] = key to picture
        version++
        return true
    }

    private suspend fun source(page: ReviewPage): B? =
        sources[page.id] ?: ops.background { ops.decode(page.uri) }?.also { sources[page.id] = it }

    /** The filter strip shows the current page as it is turned now. */
    private suspend fun renderPreviews(now: WindowTarget): Boolean {
        val page = now.pages.getOrNull(now.current.coerceIn(0, (now.pages.size - 1).coerceAtLeast(0)))
        val key = page?.let { it.id to it.turns.total }
        if (key == previewKey) return false
        val source = page?.let { sources[it.id] }
        val fresh = if (page == null || source == null) {
            emptyList()
        } else {
            ops.background { previewsOf(source, page.turns.total) }
        }
        previews.forEach { retire(it.second) }
        previews = fresh
        previewKey = key
        version++
        return true
    }

    /** Every filter on a small copy of [source] turned [quarters] times; only the copies made here are recycled. */
    private fun previewsOf(source: B, quarters: Int): List<Pair<ScanFilter, B>> {
        val thumb = ops.thumbnail(source)
        val turned = rotateQuarters(thumb, quarters, ops::rotateQuarter, ops::recycle)
        if (thumb !== source && thumb !== turned) ops.recycle(thumb)
        return ScanFilter.entries.map { it to ops.filter(turned, it) }
            .also { if (turned !== source) ops.recycle(turned) }
    }
}
