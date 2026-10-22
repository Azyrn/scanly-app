package com.skeler.scanely.docchat.presentation.screen

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AgentLog
import com.skeler.scanely.docchat.data.AgentRow
import com.skeler.scanely.docchat.data.AgentTask
import com.skeler.scanely.docchat.data.RowOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The document agent's log as Claude Code's CLI shows it (checked against claude 2.1.301): a row per
 * tool call, "● Tool(argument)", with its result as an indented "⎿" line; a status line with a
 * spinner while it works; the task list; when done, "Worked for 3m 51s · 14 steps". Every text is
 * the agent's or a tool's and is shown as plain text in its own direction (Arabic rows read right to
 * left); nothing here is parsed as markup.
 */

/** Each line follows its own text's direction: an Arabic note reads right to left in an English app. */
private val ContentDirection = TextStyle(textDirection = TextDirection.Content)
private val Mono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    textDirection = TextDirection.Content
)
private val ROW_MIN_HEIGHT = 48.dp
private val INDENT = 16.dp
private val RESULT_INDENT = 20.dp
private const val SPINNER = "·✢✳✶✻✽"
private const val SPINNER_MS = 120L

/** One row of the log. Tap it (the whole row, at least [ROW_MIN_HEIGHT] tall) to open or close its output. */
@Composable
internal fun AgentRowItem(
    row: AgentRow,
    depth: Int,
    turnId: String?,
    actions: ChatActions,
    modifier: Modifier = Modifier
) {
    var open by rememberSaveable(row.row) { mutableStateOf(false) }
    val expandable = row.text != null || row.cut != null
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = INDENT * depth)
            .then(
                // Only a row that opens is a tap target, and then at least ROW_MIN_HEIGHT tall.
                if (expandable) {
                    Modifier.heightIn(
                        min = ROW_MIN_HEIGHT
                    ).clickable(role = Role.Button) { open = !open }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        when (row.kind) {
            AgentRow.TOOL -> {
                ToolHeader(row)
                ResultText(row, open)
            }
            AgentRow.THINKING -> DimText(
                "✻ " + if (row.ms != null) {
                    stringResource(
                        R.string.chat_log_thought,
                        duration(row.ms)
                    )
                } else {
                    stringResource(R.string.chat_log_thinking)
                }
            )
            else -> if (!open) DimText(row.text.orEmpty().firstLine(), more = row.moreLines())
        }
        if (open) {
            if (row.kind == AgentRow.TOOL) {
                OutputBlock(row, turnId, actions)
            } else {
                Text(
                    row.text.orEmpty(),
                    style = ContentDirection,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CutNotice(row, turnId, actions, mono = false)
            }
        }
    }
}

@Composable
private fun ToolHeader(row: AgentRow) {
    val dot = when {
        row.failed -> MaterialTheme.colorScheme.error
        row.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    // One text, so the dot, the tool and its argument share a baseline.
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = dot)) { append("● ") }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(row.tool.orEmpty()) }
            if (!row.arg.isNullOrEmpty()) append("(${row.arg})")
        },
        style = ContentDirection,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** The "⎿" line: what the call gave, its first line and how many more. */
@Composable
private fun ResultText(row: AgentRow, open: Boolean) {
    val line = row.resultLine()
    val failed = line is ResultLine.Denied || (line is ResultLine.Text && line.failed)
    val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val text = when (line) {
        ResultLine.Running -> stringResource(R.string.chat_log_running)
        is ResultLine.Read -> pluralStringResource(R.plurals.chat_log_read, line.lines, line.lines)
        is ResultLine.Image -> stringResource(
            R.string.chat_log_read_image,
            Formatter.formatShortFileSize(LocalContext.current, line.bytes)
        )
        is ResultLine.Wrote -> pluralStringResource(R.plurals.chat_log_wrote, line.lines, line.lines)
        ResultLine.NoOutput -> stringResource(R.string.chat_log_no_output)
        ResultLine.Denied -> stringResource(R.string.chat_log_denied)
        is ResultLine.Text -> if (open) "" else line.first
    }
    val more = (line as? ResultLine.Text)?.more?.takeIf { it > 0 && !open }
    if (text.isEmpty() && more == null) return
    Row(Modifier.padding(start = RESULT_INDENT)) {
        Text(
            "⎿  ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            text,
            style = ContentDirection,
            fontSize = 13.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline()
        )
        if (more != null) {
            Text(
                "  " + pluralStringResource(R.plurals.chat_log_more_lines, more, more),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

@Composable
private fun DimText(text: String, more: Int = 0) {
    Row {
        Text(
            text,
            style = ContentDirection,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline()
        )
        if (more > 0) {
            Text(
                "  " + pluralStringResource(R.plurals.chat_log_more_lines, more, more),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * A tool's output in a monospace block with its own horizontal scroll: the preview up to
 * agent_output_preview_lines with "Show more", then exactly what the preview leaves out.
 */
@Composable
private fun OutputBlock(row: AgentRow, turnId: String?, actions: ChatActions) {
    val text = row.text ?: return CutNotice(row, turnId, actions, mono = true)
    val previewLines = integerResource(R.integer.agent_output_preview_lines)
    var more by rememberSaveable(row.row + ":more") { mutableStateOf(false) }
    val long = text.lines().size > previewLines
    Column(Modifier.padding(start = RESULT_INDENT, top = 2.dp)) {
        CodeSurface {
            Text(
                text,
                style = Mono,
                softWrap = false,
                maxLines = if (more || !long) Int.MAX_VALUE else previewLines,
                color = if (row.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
        if (long) {
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = ROW_MIN_HEIGHT)) {
                Text(stringResource(if (more) R.string.chat_log_show_less else R.string.chat_log_show_more))
            }
        }
        CutNotice(row, turnId, actions, mono = true)
    }
}

@Composable
private fun CodeSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
    ) { content() }
}

/** What the preview leaves out, said exactly; "Show all" fetches the whole text while the server keeps it. */
@Composable
private fun CutNotice(row: AgentRow, turnId: String?, actions: ChatActions, mono: Boolean) {
    val cut = row.cut ?: return
    var output by remember(row.row) { mutableStateOf<RowOutput?>(null) }
    var loading by remember(row.row) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loaded = output as? RowOutput.Text
    if (loaded != null) {
        FullOutput(loaded.text, failed = row.failed, mono = mono)
        if (loaded.cut > 0) DimText(stringResource(R.string.chat_log_past_limit, loaded.cut))
        return
    }
    val canFetch = row.full && turnId != null
    val notice = if (canFetch) {
        pluralStringResource(R.plurals.chat_log_not_shown, cut.lines, cut.lines, cut.chars)
    } else {
        pluralStringResource(R.plurals.chat_log_not_kept, cut.lines, cut.lines, cut.chars)
    }
    Column(Modifier.padding(start = if (mono) 0.dp else RESULT_INDENT, top = 2.dp)) {
        Text(notice, style = ContentDirection, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !canFetch -> Unit
            loading -> DimText(stringResource(R.string.chat_log_loading))
            output == RowOutput.Expired -> DimText(stringResource(R.string.chat_log_expired))
            else -> TextButton(
                onClick = {
                    loading = true
                    scope.launch {
                        output = actions.output(turnId!!, row.row)
                        loading = false
                    }
                },
                modifier = Modifier.heightIn(min = ROW_MIN_HEIGHT)
            ) {
                val retry = output == RowOutput.Unavailable
                Text(stringResource(if (retry) R.string.chat_log_unavailable else R.string.chat_log_show_all))
            }
        }
    }
}

/**
 * A whole output (up to the server's limit, thousands of lines): a box agent_output_full_lines tall
 * that scrolls by itself, its lines laid out only as they come into view, one width for all of them
 * (the longest line, in monospace) so the box scrolls sideways as one.
 */
@Composable
private fun FullOutput(text: String, failed: Boolean, mono: Boolean) {
    val lines = remember(text) { text.split('\n') }
    val style = if (mono) Mono else Mono.copy(fontFamily = FontFamily.Default)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(measurer, style) { measurer.measure("0", style).size.width }
    val longest = remember(lines) { lines.maxOf { it.length } }
    val width = with(density) { (charWidth * longest).toDp() } + 20.dp
    val height = with(density) { style.lineHeight.toDp() } * integerResource(R.integer.agent_output_full_lines)
    val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    CodeSurface {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(width).height(height).padding(horizontal = 10.dp)) {
                items(lines.size) { index -> Text(lines[index], style = style, softWrap = !mono, color = color) }
            }
        }
    }
}

/** The status line under a working log: spinner, what it is doing, for how long, how many steps. */
@Composable
internal fun LogStatusLine(log: AgentLog, elapsedMs: Long, running: Boolean) {
    val frame by produceState(0, running) {
        while (running) {
            delay(SPINNER_MS)
            value = (value + 1) % SPINNER.length
        }
    }
    val call = currentCall(log.rows)
    val action = call?.description ?: call?.call() ?: stringResource(R.string.chat_log_thinking)
    val steps = pluralStringResource(R.plurals.chat_log_steps, log.steps, log.steps)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            if (running) SPINNER[frame].toString() + " " else "✻ ",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier.width(18.dp)
        )
        Text(
            stringResource(R.string.chat_log_status, action, duration(elapsedMs), steps),
            style = ContentDirection,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The agent's task list: done, in progress, pending, as the CLI marks them. */
@Composable
internal fun LogTasks(tasks: List<AgentTask>) {
    if (tasks.isEmpty()) return
    Column(Modifier.padding(start = RESULT_INDENT), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tasks.forEachIndexed { index, task ->
            val (mark, color, weight) = when (task.state) {
                AgentTask.COMPLETED -> Triple("✔", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal)
                AgentTask.IN_PROGRESS -> Triple("◼", MaterialTheme.colorScheme.primary, FontWeight.SemiBold)
                else -> Triple("◻", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal)
            }
            // On one baseline: the marks are drawn from another font and sat low.
            Row {
                Text(
                    if (index == 0) "⎿  " else "    ",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline()
                )
                Text("$mark ", fontSize = 13.sp, color = color, modifier = Modifier.alignByBaseline())
                Text(
                    task.subject,
                    style = ContentDirection,
                    fontSize = 13.sp,
                    fontWeight = weight,
                    color = color,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}

/** A finished log, folded into one line: "Worked for 3m 51s · 14 steps". */
@Composable
internal fun LogSummaryLine(log: AgentLog, ms: Long, open: Boolean, onToggle: () -> Unit) {
    val steps = pluralStringResource(R.plurals.chat_log_steps, log.steps, log.steps)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (open) R.string.chat_log_hide_steps else R.string.chat_log_show_steps)
            ) {
                onToggle()
            }
    ) {
        Text(
            stringResource(R.string.chat_log_worked, duration(ms), steps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            painterResource(if (open) R.drawable.ic_sym_expand_less else R.drawable.ic_sym_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The oldest rows the phone did not keep, counted. */
@Composable
internal fun DroppedRows(count: Int) {
    if (count > 0) DimText(pluralStringResource(R.plurals.chat_log_dropped, count, count))
}

@Composable
private fun duration(ms: Long): String {
    val (minutes, seconds) = minutesAndSeconds(ms)
    return if (minutes > 0) {
        stringResource(R.string.chat_log_minutes, minutes.toInt(), seconds.toInt())
    } else {
        stringResource(R.string.chat_log_seconds, seconds.toInt())
    }
}
