package com.skeler.scanely.docchat.presentation.screen

import com.skeler.scanely.docchat.data.AgentRow

/**
 * The agent's log as Claude Code's CLI words it, before strings: each row's "⎿" line, how deep a
 * subagent's row sits, and what the status line says is happening. No Compose here.
 */
internal sealed interface ResultLine {
    data object Running : ResultLine
    data class Read(val lines: Int) : ResultLine
    data class Image(val bytes: Long) : ResultLine
    data class Wrote(val lines: Int) : ResultLine
    data object NoOutput : ResultLine
    data object Denied : ResultLine

    /** The first line of the result (red when [failed]) and how many more lines it has. */
    data class Text(val first: String, val more: Int, val failed: Boolean) : ResultLine
}

internal fun AgentRow.resultLine(): ResultLine = when {
    state == AgentRow.DENIED -> ResultLine.Denied
    running && text == null -> ResultLine.Running
    else -> counted() ?: textLine()
}

/** A read or a write that went through, as its line count. */
private fun AgentRow.counted(): ResultLine? {
    val lines = count?.takeUnless { failed } ?: return null
    return when (outcome) {
        AgentRow.OUTCOME_READ -> ResultLine.Read(lines)
        AgentRow.OUTCOME_IMAGE -> ResultLine.Image(lines.toLong())
        AgentRow.OUTCOME_WRITE -> ResultLine.Wrote(lines)
        else -> null
    }
}

private fun AgentRow.textLine(): ResultLine = when {
    text != null -> ResultLine.Text(text.firstLine(), moreLines(), failed)
    cut != null -> ResultLine.Text("", moreLines(), failed)
    else -> ResultLine.NoOutput
}

/** Lines of the whole text after its first. */
internal fun AgentRow.moreLines(): Int = ((lines ?: text?.lines()?.size ?: 0) - 1).coerceAtLeast(0)

internal fun String.firstLine(): String = lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** How many Agent calls each row ran under (0 for the main conversation's), by row id. */
internal fun rowDepths(rows: List<AgentRow>): Map<String, Int> {
    val parents = rows.associate { it.row to it.parent }
    return rows.associate { row ->
        var depth = 0
        var parent = row.parent
        val seen = HashSet<String>()
        while (parent != null && parent in parents && seen.add(parent)) {
            depth++
            parent = parents[parent]
        }
        row.row to depth
    }
}

/** What the status line names: the newest tool call still running, else null (the agent is thinking). */
internal fun currentCall(rows: List<AgentRow>): AgentRow? = rows.lastOrNull { it.kind == AgentRow.TOOL && it.running }

/** A tool call in the CLI's form, `Tool(short argument)`. */
internal fun AgentRow.call(): String = if (arg.isNullOrEmpty()) tool.orEmpty() else "${tool.orEmpty()}($arg)"

/** Minutes and seconds of [ms], for "3m 51s"; minutes is 0 under a minute. */
internal fun minutesAndSeconds(ms: Long): Pair<Long, Long> {
    val seconds = (ms / MS_PER_SECOND).coerceAtLeast(0)
    return seconds / SECONDS_PER_MINUTE to seconds % SECONDS_PER_MINUTE
}

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
