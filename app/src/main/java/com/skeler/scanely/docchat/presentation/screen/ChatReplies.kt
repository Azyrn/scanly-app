@file:OptIn(ExperimentalLayoutApi::class)

package com.skeler.scanely.docchat.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AskAnswer
import com.skeler.scanely.docchat.data.AskBody
import com.skeler.scanely.docchat.data.ChatMessage
import com.skeler.scanely.docchat.data.ErrorBody
import com.skeler.scanely.docchat.data.FieldsBody
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.docchat.data.chatThumbnail

@Composable
internal fun ReplyCard(
    message: ChatMessage,
    context: ReplyContext,
    actions: ChatActions,
    modifier: Modifier = Modifier
) {
    when (val body = message.body) {
        is FileBody -> FileCard(message.id, body, context, actions, modifier)
        is AskBody -> AskCard(message.id, body, actions, modifier)
        is FieldsBody -> FieldsCard(message.id, body, actions, modifier)
        is ErrorBody -> ErrorCard(message.id, body, context, actions, modifier)
        else -> Unit
    }
}

@Composable
private fun ReplySurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(end = 24.dp),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/**
 * A produced file: page 1, what it is, what it cost, and what can be done with it. The format
 * badge and icon come from [FileBody.format], so a DOCX or a deck lands in the same card.
 */
@Composable
private fun FileCard(
    id: String,
    body: FileBody,
    context: ReplyContext,
    actions: ChatActions,
    modifier: Modifier
) {
    ReplySurface(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Thumbnail(body, context.hasThumbnail, onFetch = { actions.restore(id, body) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    body.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Badge(body.format.extension.uppercase(), MaterialTheme.colorScheme.primaryContainer)
                    // Unknown (an Office file the server could not count) says nothing rather than "0 pages".
                    if (body.pages > 0) Meta(pluralStringResource(R.plurals.chat_pages, body.pages, body.pages))
                    if (body.credits > 0) {
                        Meta(pluralStringResource(R.plurals.chat_credits_used, body.credits, body.credits))
                    }
                    if (context.isRevisionTarget) Badge(stringResource(R.string.chat_file_editing), null)
                }
                if (body.expired) {
                    Text(
                        stringResource(R.string.chat_expired_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        FileActions(id, body, context.canRevise, actions)
    }
}

/**
 * Page 1, loaded lazily from the thumbnail rendered when the file arrived. A file restored from
 * the server but not yet fetched shows its format icon; tapping it fetches the file.
 */
@Composable
private fun Thumbnail(body: FileBody, available: Boolean, onFetch: () -> Unit) {
    val filesDir = LocalContext.current.filesDir
    val description = stringResource(R.string.chat_thumbnail_description, body.name)
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .width(THUMB_WIDTH)
            .aspectRatio(A4_PORTRAIT)
            .clip(shape)
            .background(Color.White, shape)
            .then(if (available || body.expired) Modifier else Modifier.clickable(onClick = onFetch)),
        contentAlignment = Alignment.Center
    ) {
        if (available) {
            val file = remember(body.designId) { chatThumbnail(filesDir, body.designId) }
            // AsyncImage, not the subcomposing variant: no extra composition per row while scrolling.
            AsyncImage(
                model = file,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            FormatIcon()
        }
    }
}

@Composable
private fun FormatIcon() {
    Box(Modifier.fillMaxWidth().aspectRatio(A4_PORTRAIT), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_sym_draft),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun FileActions(id: String, body: FileBody, canRevise: Boolean, actions: ChatActions) {
    var menu by remember { mutableStateOf(false) }
    // Wraps on a narrow screen with large text rather than pushing the overflow button off the card.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        if (body.expired) {
            FilledTonalButton(onClick = { actions.rebuild(id) }) { Text(stringResource(R.string.chat_expired)) }
        } else {
            FilledTonalButton(onClick = { actions.open(id, body) }) {
                Icon(
                    painterResource(R.drawable.ic_sym_open_in_new),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.chat_file_open), modifier = Modifier.padding(start = 6.dp))
            }
        }
        IconButton(onClick = { actions.share(id, body) }) {
            Icon(
                painterResource(R.drawable.ic_sym_share),
                contentDescription = stringResource(R.string.chat_file_share),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { actions.download(id, body) }) {
            Icon(
                painterResource(R.drawable.ic_sym_download),
                contentDescription = stringResource(R.string.chat_file_download),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(R.drawable.ic_sym_more_vert),
                    contentDescription = stringResource(R.string.chat_file_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_file_save_images)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sym_image), contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.saveImages(id, body)
                    }
                )
                if (!body.expired && canRevise) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_file_revise)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sym_tune), contentDescription = null) },
                        onClick = {
                            menu = false
                            actions.reviseFrom(body.designId)
                        }
                    )
                }
            }
        }
    }
}

/** The designer asked before building (409): the attachment is not what was asked for. */
@Composable
private fun AskCard(id: String, body: AskBody, actions: ChatActions, modifier: Modifier) {
    ReplySurface(modifier) {
        CardHeader(painterResource(R.drawable.ic_sym_help), stringResource(R.string.chat_ask_title), notCharged = true)
        Text(
            body.reason.takeIf { it.isNotBlank() }?.let { stringResource(R.string.chat_ask_reason, it) }
                ?: stringResource(R.string.chat_ask_fallback),
            style = MaterialTheme.typography.bodyMedium
        )
        when (body.answer) {
            null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.answer(id, AskAnswer.CONTINUE) }) {
                    Text(stringResource(R.string.chat_ask_continue))
                }
                TextButton(onClick = { actions.answer(id, AskAnswer.DISMISS) }) {
                    Text(stringResource(R.string.chat_ask_dismiss))
                }
            }
            // Old chats only: answered by picking a template, which the app no longer offers.
            AskAnswer.USE_TEMPLATE -> Answered(stringResource(R.string.chat_ask_answered))
            AskAnswer.CONTINUE -> Answered(stringResource(R.string.chat_ask_answered_continue))
            AskAnswer.DISMISS -> Answered(stringResource(R.string.chat_ask_answered_dismiss))
        }
    }
}

/**
 * Required details are missing (422). Compact here: which details, and a button that opens the
 * form as a sheet ([FieldsSheet]); once answered, what was given.
 */
@Composable
private fun FieldsCard(id: String, body: FieldsBody, actions: ChatActions, modifier: Modifier) {
    val submitted = body.submitted
    ReplySurface(modifier) {
        CardHeader(
            painterResource(R.drawable.ic_sym_tune),
            stringResource(R.string.chat_fields_title),
            notCharged = true
        )
        if (submitted != null) {
            body.fields.forEach { field ->
                Text(
                    "${field.label}: ${submitted[field.id].orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@ReplySurface
        }
        Text(
            body.fields.joinToString(" · ") { it.label },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(onClick = { actions.openFields(id) }) { Text(stringResource(R.string.chat_fields_open)) }
    }
}

/** A failure, in the server's words; "Not charged" only when the server said so. */
@Composable
private fun ErrorCard(id: String, body: ErrorBody, context: ReplyContext, actions: ChatActions, modifier: Modifier) {
    ReplySurface(modifier) {
        CardHeader(
            painterResource(R.drawable.ic_sym_error),
            title = null,
            notCharged = body.notCharged,
            tint = MaterialTheme.colorScheme.error
        )
        Text(body.message, style = MaterialTheme.typography.bodyMedium)
        val upgrade = body.code in UPGRADE_CODES
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (upgrade) {
                Button(onClick = { actions.upgrade(body.message, body.code) }) {
                    Text(stringResource(R.string.chat_see_plans))
                }
            }
            if (body.retryable && context.isLatest && !context.chatIsWorking) {
                OutlinedButton(onClick = { actions.retry(id) }) { Text(stringResource(R.string.chat_retry)) }
            }
        }
    }
}

@Composable
private fun CardHeader(
    icon: Painter,
    title: String?,
    notCharged: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(Modifier.weight(1f))
        }
        if (notCharged) NotChargedBadge()
    }
}

@Composable
internal fun NotChargedBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_sym_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(R.string.chat_not_charged),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun Answered(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Badge(text: String, color: Color?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color ?: MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun Meta(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The codes the paywall answers; see AiResult.Error.isUpgradePath. */
private val UPGRADE_CODES = setOf("quota_exceeded", "format_not_in_plan", "budget_exhausted")
private val THUMB_WIDTH = 76.dp
private const val A4_PORTRAIT = 210f / 297f

/** The server refuses longer answers; the field stops there instead. */
