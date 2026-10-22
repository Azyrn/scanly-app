package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.history.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A past scan offered as the content of a design, named so one can be told from another. */
data class DesignSource(val id: String, val label: String, val text: String)

fun HistoryItem.toDesignSource(now: Long = System.currentTimeMillis()): DesignSource =
    DesignSource(id = id, label = designSourceLabel(text, timestamp, now), text = text)

/**
 * "Today · Invoice 402".
 *
 * A scan carries no title, so the first meaningful line stands in for one. The date leads because
 * it is what actually separates two scans of the same kind of document, and it is the part the
 * user remembers. Without this the chip showed 28 raw characters of OCR output, which named nothing.
 */
internal fun designSourceLabel(text: String, timestamp: Long, now: Long): String {
    val day = dayLabel(timestamp, now)
    val title = text.lineSequence()
        .map { it.trim().trim(*TITLE_NOISE).replace(WHITESPACE, " ").trim() }
        .firstOrNull { it.length >= MIN_TITLE_CHARS }
        ?: return day
    return "$day · ${title.ellipsize()}"
}

private fun String.ellipsize(): String =
    if (length <= MAX_TITLE_CHARS) this else take(MAX_TITLE_CHARS).trimEnd() + "…"

private fun dayLabel(timestamp: Long, now: Long): String {
    val then = calendarAt(timestamp)
    val today = calendarAt(now)
    val yesterday = calendarAt(now).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        isSameDay(then, today) -> "Today"
        isSameDay(then, yesterday) -> "Yesterday"
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> format("d MMM", timestamp)
        else -> format("d MMM yyyy", timestamp)
    }
}

private fun calendarAt(millis: Long): Calendar =
    Calendar.getInstance().apply { timeInMillis = millis }

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun format(pattern: String, millis: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

private val WHITESPACE = Regex("\\s+")
private val TITLE_NOISE = charArrayOf('#', '*', '|', '-', '_', '=', '>', '•', ' ')
private const val MIN_TITLE_CHARS = 3
private const val MAX_TITLE_CHARS = 26
