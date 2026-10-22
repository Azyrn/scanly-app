package com.skeler.scanely.docchat.presentation.screen

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AgentApprovalDto
import kotlinx.coroutines.delay

private const val SECOND_MS = 1000L
private const val URL_LINES = 4

/**
 * A web turn's question: may the assistant open this site? The site's name large, the whole link
 * small below it, a warning when the link carries data, and a countdown: no answer is a no. Swiping
 * the sheet away is a no too. The server holds the page until the answer, and decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalSheet(
    approval: AgentApprovalDto,
    onAnswer: (allow: Boolean) -> Unit,
    onExpired: () -> Unit
) {
    val left by produceState(approval.expiresInMs, approval.id) {
        while (value > 0) {
            delay(SECOND_MS)
            value = (value - SECOND_MS).coerceAtLeast(0)
        }
    }
    LaunchedEffect(approval.id, left) {
        if (left <= 0) onExpired()
    }
    ModalBottomSheet(onDismissRequest = { onAnswer(false) }) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.chat_approval_title), style = MaterialTheme.typography.titleMedium)
            Text(approval.host, style = MaterialTheme.typography.headlineSmall)
            Text(
                approval.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = URL_LINES,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(
                    if (approval.why == "page_link") {
                        R.string.chat_approval_page_link
                    } else {
                        R.string.chat_approval_new_url
                    }
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            when (approval.warn) {
                "query" -> R.string.chat_approval_warn_query
                "long" -> R.string.chat_approval_warn_long
                else -> null
            }?.let { warning ->
                Text(
                    stringResource(warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                stringResource(R.string.chat_approval_countdown, DateUtils.formatElapsedTime(left / SECOND_MS)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_approval_deny))
                }
                Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_approval_allow_once))
                }
            }
        }
    }
}
