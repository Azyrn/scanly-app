package com.skeler.scanely.ui.pdftools

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import java.text.NumberFormat

/**
 * Every output gets its own row and Open action, since a split or a conversion can hand back many
 * files of different types; Save and Share always act on all of them.
 */
@Composable
fun ToolDoneContent(
    result: ToolResult,
    isSaved: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP)
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ICON_SIZE)
        )
        Text(stringResource(R.string.pdf_tool_done), style = MaterialTheme.typography.headlineSmall)

        result.outputs.forEachIndexed { index, output ->
            OutputRow(output = output, onOpen = { onOpen(index) })
        }

        if (result.showSizeChange && result.outputs.size == 1 && result.inputBytes > 0) {
            SizeChange(before = result.inputBytes, after = result.outputs.single().sizeBytes)
        }

        result.notes.forEach { note ->
            Text(
                text = if (note.plural) {
                    pluralStringResource(note.res, note.count, note.count)
                } else {
                    stringResource(note.res)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onSave,
            enabled = !isSaved && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(BUTTON_SPINNER), strokeWidth = SPINNER_STROKE)
            } else {
                Text(stringResource(if (isSaved) R.string.pdf_tool_saved else R.string.pdf_tool_save))
            }
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (result.outputs.size > 1) R.string.pdf_tool_flow_share_all else R.string.pdf_tool_share
                )
            )
        }
    }
}

@Composable
private fun OutputRow(output: ToolOutput, onOpen: () -> Unit) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.pdf_tool_flow_open_output, output.fileName)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = CARD_PADDING)
                .padding(start = CARD_PADDING, end = OPEN_END),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = output.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = Formatter.formatShortFileSize(context, output.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.semantics { contentDescription = openLabel }
            ) {
                Text(stringResource(R.string.pdf_tool_open))
            }
        }
    }
}

@Composable
private fun SizeChange(before: Long, after: Long) {
    val context = LocalContext.current
    val change = after.toDouble() / before - 1.0
    Text(
        text = stringResource(
            R.string.pdf_tool_size_change,
            Formatter.formatShortFileSize(context, before),
            Formatter.formatShortFileSize(context, after),
            NumberFormat.getPercentInstance().format(change)
        ),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun ToolErrorContent(
    failure: ToolFailure,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onPickAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP)
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(ICON_SIZE)
        )
        Text(
            text = stringResource(R.string.pdf_tool_error_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(failure.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pdf_tool_try_again))
            }
        }
        OutlinedButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pdf_tool_another_file))
        }
    }
}

/** Only these failures can go differently on a second attempt with the same file. */
val ToolFailure.isRetryable: Boolean
    get() = this == ToolFailure.LowStorage || this == ToolFailure.NoGain || this is ToolFailure.Failed

@get:StringRes
private val ToolFailure.messageRes: Int
    get() = when (this) {
        ToolFailure.Unreadable -> R.string.pdf_tool_error_unreadable
        ToolFailure.Damaged -> R.string.pdf_tool_error_damaged
        ToolFailure.LowStorage -> R.string.pdf_tool_error_low_storage
        ToolFailure.WrongPassword -> R.string.pdf_tool_error_wrong_password
        ToolFailure.NotEncrypted -> R.string.pdf_tool_error_not_encrypted
        ToolFailure.NoGain -> R.string.pdf_tool_error_no_gain
        ToolFailure.NothingFound -> R.string.pdf_tool_error_nothing_found
        is ToolFailure.Failed -> R.string.pdf_tool_error_failed
    }

private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val CARD_PADDING = 16.dp
private val GAP = 12.dp
private val OPEN_END = 4.dp
private val ICON_SIZE = 56.dp
private val BUTTON_SPINNER = 18.dp
private val SPINNER_STROKE = 2.dp
