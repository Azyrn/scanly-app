package com.skeler.scanely.docchat.presentation.screen

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.DesignStep
import com.skeler.scanely.docchat.data.StepRecord
import com.skeler.scanely.docchat.data.WorkingBody
import kotlinx.coroutines.delay

/**
 * A reply being made: what it is doing now, how long it has been at it, and — closed by default
 * — the steps so far with their durations. The model returns no reasoning summary on the
 * provider Scanly runs, so the timeline is what "Show thinking" shows; internal prompts never are.
 */
@Composable
internal fun WorkingReply(id: String, body: WorkingBody, isLive: Boolean, modifier: Modifier = Modifier) {
    val running = isLive || body.jobId != null
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    val elapsed = ((now - body.startedAt) / MS_PER_SECOND).coerceAtLeast(0)
    val current = body.steps.lastOrNull()?.step
    if (!body.log.isEmpty) {
        // The document agent's turn: its rows are the list's items above this one (MessageList); here
        // the CLI's status line and task list close it.
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LogStatusLine(body.log, elapsedMs = now - body.startedAt, running = running && !body.stopped)
            LogTasks(body.log.tasks)
            if (body.background || body.stopped) {
                BackgroundNote(if (body.stopped) R.string.chat_stopped_note else R.string.chat_background_note)
            }
        }
        return
    }
    Column(modifier.fillMaxWidth().padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            if (running && !body.stopped) PulsingDot()
            val label = body.note ?: stringResource(current?.label ?: R.string.chat_step_planning)
            AnimatedContent(
                targetState = label,
                transitionSpec = { ChatMotion.crossfade() },
                label = "step"
            ) { text ->
                Text(
                    stringResource(R.string.chat_status_elapsed, text, elapsed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (body.background || body.stopped) {
            BackgroundNote(if (body.stopped) R.string.chat_stopped_note else R.string.chat_background_note)
        }
        if (body.steps.isNotEmpty()) Thinking(id, body.steps, liveUntil = now.takeIf { running })
    }
}

/** On a finished reply: how long it took, with the same timeline behind it. */
@Composable
internal fun FinishedThinking(id: String, steps: List<StepRecord>) {
    if (steps.isEmpty()) return
    Thinking(id, steps, liveUntil = null, summary = true)
}

@Composable
private fun Thinking(
    id: String,
    steps: List<StepRecord>,
    liveUntil: Long?,
    summary: Boolean = false
) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val durations = stepDurations(steps, liveUntil)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Button) { open = !open }
                .padding(vertical = 2.dp)
        ) {
            val toggle = stringResource(if (open) R.string.chat_hide_thinking else R.string.chat_show_thinking)
            val text = if (summary) {
                stringResource(R.string.chat_took, durations.sum() / MS_PER_SECOND) + " · " + toggle
            } else {
                toggle
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Icon(
                painterResource(if (open) R.drawable.ic_sym_expand_less else R.drawable.ic_sym_expand_more),
                contentDescription = null,
                // Matches its label: the one toggle in the row, so it reads as active.
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = ChatMotion.expand(),
            exit = ChatMotion.shrink()
        ) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEachIndexed { index, record ->
                    val last = index == steps.lastIndex
                    StepRow(record.step, durations[index], inProgress = last && liveUntil != null)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: DesignStep, durationMs: Long, inProgress: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (inProgress) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(
                    painterResource(R.drawable.ic_sym_check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(stringResource(step.label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (step != DesignStep.DONE) {
            Text(
                if (durationMs < MS_PER_SECOND) {
                    stringResource(R.string.chat_under_a_second)
                } else {
                    stringResource(R.string.chat_seconds, durationMs / MS_PER_SECOND)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BackgroundNote(@StringRes text: Int) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_sym_notifications_active),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** The one animated thing on screen while a design runs; drawn as alpha, so it never re-lays out. */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = PULSE_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/**
 * Each step's duration: up to the next step, or its own measured [StepRecord.ms] (the OCR read,
 * which had the user's typing after it); the step in progress runs to [liveUntil].
 */
internal fun stepDurations(steps: List<StepRecord>, liveUntil: Long?): List<Long> = steps.mapIndexed { index, record ->
    val next = steps.getOrNull(index + 1)
    when {
        record.ms != null -> record.ms
        next != null -> next.at - record.at
        liveUntil != null -> liveUntil - record.at
        else -> 0L
    }.coerceAtLeast(0L)
}

@get:StringRes
internal val DesignStep.label: Int
    get() = when (this) {
        DesignStep.READING_FILE -> R.string.chat_step_reading
        DesignStep.EXTRACTING_TEXT -> R.string.chat_step_extracting
        DesignStep.PLANNING -> R.string.chat_step_planning
        DesignStep.WRITING -> R.string.chat_step_writing
        DesignStep.BUILDING_PDF -> R.string.chat_step_building
        DesignStep.CHECKING -> R.string.chat_step_checking
        DesignStep.FIXING_LAYOUT -> R.string.chat_step_fixing
        DesignStep.DONE -> R.string.chat_step_done
        DesignStep.THINKING -> R.string.chat_step_thinking
        DesignStep.READING_GUIDE -> R.string.chat_step_reading_guide
        DesignStep.WRITING_CODE -> R.string.chat_step_coding
        DesignStep.PREVIEWING -> R.string.chat_step_previewing
        DesignStep.DELIVERING -> R.string.chat_step_delivering
    }

private const val TICK_MS = 1_000L
private const val MS_PER_SECOND = 1_000L
private const val PULSE_MS = 700
private const val PULSE_MIN = 0.3f
