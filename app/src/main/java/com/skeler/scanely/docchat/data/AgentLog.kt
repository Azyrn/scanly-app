package com.skeler.scanely.docchat.data

import kotlinx.serialization.Serializable

/**
 * What the document agent did in one turn, as the chat shows it (worker/src/agent-log.ts, made by
 * runner/agent/rows.py): one row per tool call, the agent's thinking and notes, a subagent's rows
 * under its Agent call, and its task list. Every text in it is what a tool printed or the model
 * wrote, already redacted by the gateway, and is only ever shown as plain text.
 */
@Serializable
data class AgentLog(
    val rows: List<AgentRow> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    /** The turn's oldest rows not kept here, and how many of them were tool calls. */
    val droppedRows: Int = 0,
    val droppedSteps: Int = 0
) {
    /** Tool calls in the whole turn: the "14 steps". */
    val steps: Int get() = droppedSteps + rows.count { it.kind == AgentRow.TOOL }

    val isEmpty: Boolean get() = rows.isEmpty() && tasks.isEmpty() && droppedRows == 0

    /** [update] in place of the rows with the same ids (or all of them when [replace]), new ones after, in first-seen order. */
    fun merged(update: List<AgentRow>, replace: Boolean): AgentLog {
        if (replace) return copy(rows = update.distinctBy { it.row }, droppedRows = 0, droppedSteps = 0)
        val byId = LinkedHashMap<String, AgentRow>(rows.size + update.size)
        rows.forEach { byId[it.row] = it }
        update.forEach { byId[it.row] = it }
        return copy(rows = byId.values.toList())
    }

    /**
     * This log in at most [maxChars] of JSON: the oldest texts go first (each then cut as not kept),
     * then the oldest rows (counted in [droppedRows] and [droppedSteps]). The task list stays.
     */
    fun kept(maxChars: Int): AgentLog {
        var size = encodedLength(this)
        if (size <= maxChars) return this
        val trimmed = rows.toMutableList()
        for (index in trimmed.indices) {
            val row = trimmed[index]
            if (size <= maxChars) break
            if (row.text != null) {
                val bare = row.withoutText()
                size -= encodedLength(row) - encodedLength(bare)
                trimmed[index] = bare
            }
        }
        var dropped = 0
        while (size > maxChars && dropped < trimmed.size) {
            size -= encodedLength(trimmed[dropped]) + 1
            dropped++
        }
        val gone = trimmed.take(dropped)
        return copy(
            rows = trimmed.drop(dropped),
            droppedRows = droppedRows + dropped,
            droppedSteps = droppedSteps + gone.count { it.kind == AgentRow.TOOL }
        )
    }
}

/** One row. The fields are the Worker's; the words around them are this app's strings. */
@Serializable
data class AgentRow(
    val row: String,
    /** The Agent call this row ran under, or null. */
    val parent: String? = null,
    val kind: String,
    val tool: String? = null,
    /** The short form: a command's first line, a path, a query. Never the raw tool input. */
    val arg: String? = null,
    /** What the agent said the call is for. */
    val description: String? = null,
    val state: String,
    val ms: Long? = null,
    val outcome: String? = null,
    /** Lines read or written; an image's bytes. */
    val count: Int? = null,
    /** The preview: whole lines of the result, note or thinking. */
    val text: String? = null,
    /** Of the whole text. */
    val lines: Int? = null,
    val chars: Int? = null,
    /** What the preview leaves out; [full] when the whole text can be fetched (while the turn is kept). */
    val cut: RowCut? = null,
    val full: Boolean = false
) {
    val failed: Boolean get() = state == ERROR || state == DENIED
    val running: Boolean get() = state == RUNNING

    /** Without its preview: all of it then counts as cut (and stays fetchable if it was). */
    fun withoutText(): AgentRow = copy(
        text = null,
        cut = RowCut(lines ?: text?.lines()?.size ?: 0, chars ?: text?.length ?: 0)
    )

    companion object {
        const val TOOL = "tool"
        const val THINKING = "thinking"
        const val NOTE = "note"

        const val RUNNING = "running"
        const val DONE = "done"
        const val ERROR = "error"
        const val DENIED = "denied"

        const val OUTCOME_READ = "read"
        const val OUTCOME_IMAGE = "image"
        const val OUTCOME_WRITE = "write"
        const val OUTCOME_EDIT = "edit"
        const val OUTCOME_AGENT = "agent"
    }
}

@Serializable
data class RowCut(val lines: Int, val chars: Int)

@Serializable
data class AgentTask(val id: String, val subject: String, val state: String) {
    companion object {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val COMPLETED = "completed"
    }
}

/** A row's whole output from the server (GET /v1/agent/turns/{id}/outputs/{row}); [cut] is what not even it holds. */
@Serializable
data class RowOutputDto(val text: String, val cut: Int = 0)

private fun encodedLength(log: AgentLog): Int = messageJson.encodeToString(AgentLog.serializer(), log).length

private fun encodedLength(row: AgentRow): Int = messageJson.encodeToString(AgentRow.serializer(), row).length
