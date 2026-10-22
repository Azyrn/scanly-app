@file:OptIn(ExperimentalFoundationApi::class)

package com.skeler.scanely.docchat.presentation.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.ChatEntity
import com.skeler.scanely.ui.viewmodel.PaywallState
import com.skeler.scanely.ui.viewmodel.planRow
import java.util.TimeZone

/** How the drawer groups chats, by the day they last changed. */
internal enum class ChatAge(@param:StringRes val label: Int) {
    TODAY(R.string.chat_group_today),
    YESTERDAY(R.string.chat_group_yesterday),
    WEEK(R.string.chat_group_week),
    OLDER(R.string.chat_group_older)
}

/** By calendar day in [zone], not by 24-hour spans. (java.time needs API 26; minSdk is 24.) */
internal fun chatAge(updatedAt: Long, now: Long, zone: TimeZone): ChatAge {
    val days = localDay(now, zone) - localDay(updatedAt, zone)
    return when {
        days <= 0 -> ChatAge.TODAY
        days == 1L -> ChatAge.YESTERDAY
        days < WEEK_DAYS -> ChatAge.WEEK
        else -> ChatAge.OLDER
    }
}

private fun localDay(millis: Long, zone: TimeZone): Long = Math.floorDiv(millis + zone.getOffset(millis), DAY_MS)

/** What the drawer lists, and what it can do with it. */
internal class DrawerCallbacks(
    val onNewChat: () -> Unit,
    val onOpen: (String) -> Unit,
    val onRename: (ChatEntity) -> Unit,
    val onDelete: (ChatEntity) -> Unit,
    val onPlan: () -> Unit
)

/**
 * The chat history as a drawer: New chat, search, the chats grouped by age (newest first, as the
 * repository orders them), and the plan with its credits at the bottom. Long-press a chat to
 * rename or delete it.
 */
@Composable
internal fun ChatDrawer(
    chats: List<ChatEntity>?,
    query: String,
    onQuery: (String) -> Unit,
    currentChatId: String?,
    paywall: PaywallState,
    callbacks: DrawerCallbacks
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
            FilledTonalButton(
                onClick = callbacks.onNewChat,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                // The drawer moves focus to its first focusable on open; without this that was the
                // search field, and the keyboard came up over the list.
                modifier = Modifier.fillMaxWidth().focusable()
            ) {
                Icon(painterResource(R.drawable.ic_sym_edit_square), contentDescription = null, Modifier.size(20.dp))
                Text(
                    stringResource(R.string.chat_new),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            DrawerSearch(query, onQuery, Modifier.padding(vertical = 12.dp))
        }
        val groups = remember(chats) {
            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            chats.orEmpty().groupBy { chatAge(it.updatedAt, now, zone) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            if (chats != null && chats.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            if (query.isBlank()) R.string.chat_history_empty else R.string.chat_search_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            ChatAge.entries.forEach { age ->
                val inGroup = groups[age].orEmpty()
                if (inGroup.isEmpty()) return@forEach
                item(key = age.name, contentType = "header") {
                    Text(
                        stringResource(age.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )
                }
                items(inGroup, key = { it.id }, contentType = { "chat" }) { chat ->
                    DrawerChatRow(chat, selected = chat.id == currentChatId, callbacks)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PlanFooter(paywall, callbacks.onPlan)
    }
}

@Composable
private fun DrawerSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Icon(
                painterResource(R.drawable.ic_sym_search),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        painterResource(R.drawable.ic_sym_close),
                        contentDescription = stringResource(R.string.chat_search_clear),
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerChatRow(chat: ChatEntity, selected: Boolean, callbacks: DrawerCallbacks) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.chat_more_actions),
                    onLongClick = { menu = true },
                    onClick = { callbacks.onOpen(chat.id) }
                )
        ) {
            Text(
                chat.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_rename_action)) },
                onClick = {
                    menu = false
                    callbacks.onRename(chat)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_delete_confirm)) },
                onClick = {
                    menu = false
                    callbacks.onDelete(chat)
                }
            )
        }
    }
}

/** The plan and what is left of it, as Settings names them; tapping opens the plans. */
@Composable
private fun PlanFooter(paywall: PaywallState, onPlan: () -> Unit) {
    val row = planRow(paywall)
    Surface(
        onClick = onPlan,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RenameChatDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(RENAME_LIMIT) },
                label = { Text(stringResource(R.string.chat_rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.chat_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } }
    )
}

@Composable
internal fun DeleteChatDialog(chat: ChatEntity, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_title)) },
        text = { Text(stringResource(R.string.chat_delete_body, chat.title)) },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.chat_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } }
    )
}

private const val RENAME_LIMIT = 80
private const val WEEK_DAYS = 7
private const val DAY_MS = 24L * 60 * 60 * 1000
