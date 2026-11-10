package com.skeler.scanely.docchat.presentation.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AgentEffort

@StringRes
private fun AgentEffort.label(): Int = when (this) {
    AgentEffort.LOW -> R.string.chat_effort_low
    AgentEffort.MEDIUM -> R.string.chat_effort_medium
    AgentEffort.HIGH -> R.string.chat_effort_high
}

/** What each level is for, and Deep's price (the Worker's AGENT_EFFORT_CREDITS: high=2). */
@StringRes
private fun AgentEffort.hint(): Int = when (this) {
    AgentEffort.LOW -> R.string.chat_effort_low_hint
    AgentEffort.MEDIUM -> R.string.chat_effort_medium_hint
    AgentEffort.HIGH -> R.string.chat_effort_high_hint
}

/**
 * How hard the assistant thinks on the next message: a pill beside attach showing the level, and a
 * menu of the three with what each is for. Chosen here, kept for later messages and sessions.
 */
@Composable
internal fun EffortPicker(effort: AgentEffort, enabled: Boolean, onPick: (AgentEffort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.chat_effort_a11y, stringResource(effort.label()))
    Box {
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainerHighest,
            modifier = Modifier
                .padding(start = 4.dp)
                .heightIn(min = 40.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = description) { open = true }
                .semantics { contentDescription = description }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Text(
                    stringResource(effort.label()),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    painterResource(R.drawable.ic_sym_keyboard_arrow_down),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AgentEffort.entries.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(stringResource(level.label()), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(level.hint()),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = if (level == effort) {
                        {
                            Icon(
                                painterResource(R.drawable.ic_sym_check_circle),
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onPick(level)
                    }
                )
            }
        }
    }
}
