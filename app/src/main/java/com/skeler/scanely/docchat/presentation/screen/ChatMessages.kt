@file:OptIn(ExperimentalLayoutApi::class)

package com.skeler.scanely.docchat.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AiTextBody
import com.skeler.scanely.docchat.data.AskBody
import com.skeler.scanely.docchat.data.AttachmentKind
import com.skeler.scanely.docchat.data.ChatMessage
import com.skeler.scanely.docchat.data.ErrorBody
import com.skeler.scanely.docchat.data.FieldsBody
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.docchat.data.UnknownBody
import com.skeler.scanely.docchat.data.UserBody
import com.skeler.scanely.docchat.data.WorkingBody
import com.skeler.scanely.docchat.data.kind

/** Rows of one kind share a slot pool, so a file card is recycled as a file card. */
internal fun ChatMessage.contentType(): String = body.kind

@Composable
internal fun MessageRow(
    message: ChatMessage,
    isLive: Boolean,
    context: ReplyContext,
    actions: ChatActions,
    modifier: Modifier = Modifier
) {
    when (val body = message.body) {
        is UserBody -> UserBubble(body, modifier)
        is AiTextBody -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A log's own line ("Worked for …") is the list's item above this one (MessageList).
            if (body.log == null) FinishedThinking(message.id, body.steps)
            AiTextBubble(body.text)
            // Only the newest question offers its answers; an older one is history.
            if (body.choices.isNotEmpty() && context.isLatest && !context.chatIsWorking) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    body.choices.forEach { choice ->
                        SuggestionChip(onClick = { actions.sendChoice(choice) }, label = { Text(choice) })
                    }
                }
            }
        }
        is WorkingBody -> WorkingReply(message.id, body, isLive, modifier)
        is FileBody, is ErrorBody -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if ((body as? ErrorBody)?.log == null) {
                FinishedThinking(
                    message.id,
                    (body as? FileBody)?.steps ?: (body as ErrorBody).steps
                )
            }
            ReplyCard(message, context, actions)
        }
        is AskBody, is FieldsBody -> ReplyCard(message, context, actions, modifier)
        UnknownBody -> AiTextBubble(stringResource(R.string.chat_unknown_message), modifier)
    }
}

@Composable
private fun UserBubble(body: UserBody, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH)) {
            if (body.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    body.attachments.forEach { attachment ->
                        val icon = when (attachment.kind) {
                            AttachmentKind.DOCUMENT -> painterResource(R.drawable.ic_sym_description)
                            AttachmentKind.PHOTO -> painterResource(R.drawable.ic_sym_image)
                            AttachmentKind.SCAN -> painterResource(R.drawable.ic_sym_text_snippet)
                        }
                        AttachmentTag(icon, attachment.name)
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    body.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentTag(icon: Painter, label: String) {
    val description = stringResource(R.string.chat_you_attached, label)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = TAG_MAX_WIDTH)
            )
        }
    }
}

@Composable
internal fun AiTextBubble(text: String, modifier: Modifier = Modifier) {
    MarkdownText(text, modifier.fillMaxWidth().padding(end = 32.dp))
}

private val BUBBLE_MAX_WIDTH = 320.dp
private val TAG_MAX_WIDTH = 180.dp
