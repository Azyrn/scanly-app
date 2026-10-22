package com.skeler.scanely.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.core.ai.ScanExtraction

/** Shown at a glance; the rest are one tap away. */
private const val PREVIEW_ROWS = 3

/**
 * The OCR model reports how confident it was in each block it read. Where that confidence is low
 * the text may be wrong in ways that read perfectly — an invented word is still a word — so the
 * passages are named rather than silently folded into the result.
 */
@Composable
fun UnclearPassagesNotice(passages: List<String>, modifier: Modifier = Modifier) {
    if (passages.isEmpty()) return

    var expanded by remember(passages) { mutableStateOf(false) }
    val shown = if (expanded) passages else passages.take(PREVIEW_ROWS)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (passages.size == 1) {
                        "One passage was hard to read"
                    } else {
                        "${passages.size} passages were hard to read"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(Modifier.height(8.dp))

            shown.forEach { passage ->
                Text(
                    text = "“$passage”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Text(
                text = "Check these against the original before you rely on them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            if (passages.size > PREVIEW_ROWS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${passages.size}")
                }
            }
        }
    }
}

/**
 * The document as fields rather than prose — what an extraction preset was asked for.
 *
 * [onCopyJson] hands over the same data as JSON, which is the form anyone feeding a spreadsheet
 * or an accounting tool actually wants.
 */
@Composable
fun ExtractedFieldsCard(
    extraction: ScanExtraction,
    onCopyJson: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (extraction.fields.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.DataObject,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = extraction.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onCopyJson(extraction.json) }) {
                    Text("Copy JSON")
                }
            }

            Spacer(Modifier.height(4.dp))

            extraction.fields.forEach { (label, value) ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        }
    }
}

/** Links the document itself carries, which plain extracted text would have flattened away. */
@Composable
fun DocumentLinksCard(links: List<String>, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return

    val uriHandler = LocalUriHandler.current
    var expanded by remember(links) { mutableStateOf(false) }
    val shown = if (expanded) links else links.take(PREVIEW_ROWS)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (links.size == 1) "1 link" else "${links.size} links",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))

            shown.forEach { link ->
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(link) } }
                        .padding(vertical = 6.dp)
                )
            }

            if (links.size > PREVIEW_ROWS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${links.size}")
                }
            }
        }
    }
}
