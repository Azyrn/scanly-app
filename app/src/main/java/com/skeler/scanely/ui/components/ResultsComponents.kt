@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.skeler.scanely.core.ai.AI_PROVIDER_NAME
import com.skeler.scanely.core.common.LocalReadingTextScale
import kotlin.math.roundToInt

@Stable
class ExtractedTextState internal constructor(initial: String) {
    var editing by mutableStateOf(false)
        internal set
    var draft by mutableStateOf(initial)
}

/**
 * Section title for a results screen. Kept deliberately short so the header costs little vertical
 * space and more of the extracted text lands above the fold.
 */
@Composable
fun ResultsSectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun rememberExtractedTextState(text: String): ExtractedTextState =
    remember(text) { ExtractedTextState(text) }

@Composable
fun RowScope.ExtractedTextActions(
    state: ExtractedTextState,
    text: String,
    onCopy: () -> Unit,
    onExport: ((TextExportFormat) -> Unit)? = null,
    exportFormats: List<TextExportFormat> = TEXT_EXPORT_FORMATS,
    disabledFormats: Set<TextExportFormat> = emptySet(),
    onSaveEdit: ((String) -> Unit)? = null,
    onPrint: (() -> Unit)? = null,
    previewContent: ExportContent? = null,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null
) {
    val btnModifier = if (compact) Modifier.size(34.dp) else Modifier
    val iconSize = if (compact) 18.dp else 20.dp
    val gap = if (compact) 6.dp else 8.dp
    var previewOpen by remember { mutableStateOf(false) }

    if (previewOpen && previewContent != null && onExport != null) {
        FilePreviewOverlay(
            content = previewContent,
            formats = exportFormats,
            disabledFormats = disabledFormats,
            onExport = onExport,
            onDismiss = { previewOpen = false }
        )
    }

    if (state.editing) {
        FilledTonalIconButton(
            onClick = {
                state.draft = text
                state.editing = false
            },
            modifier = btnModifier,
            shape = RoundedCornerShape(14.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(iconSize))
        }
        Spacer(modifier = Modifier.size(gap))
        FilledTonalIconButton(
            onClick = {
                state.editing = false
                if (state.draft != text) onSaveEdit?.invoke(state.draft)
            },
            modifier = btnModifier,
            shape = RoundedCornerShape(14.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Rounded.Check, contentDescription = "Save", modifier = Modifier.size(iconSize))
        }
    } else {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.size(gap))
        }
        if (onSaveEdit != null) {
            FilledTonalIconButton(
                onClick = {
                    state.draft = text
                    state.editing = true
                },
                modifier = btnModifier,
                shape = RoundedCornerShape(14.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit text", modifier = Modifier.size(iconSize))
            }
            Spacer(modifier = Modifier.size(gap))
        }
        if (onPrint != null) {
            FilledTonalIconButton(
                onClick = onPrint,
                modifier = btnModifier,
                shape = RoundedCornerShape(14.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Rounded.Print, contentDescription = "Print", modifier = Modifier.size(iconSize))
            }
            Spacer(modifier = Modifier.size(gap))
        }
        if (onExport != null) {
            Box {
                var exportMenuOpen by remember { mutableStateOf(false) }
                FilledTonalIconButton(
                    onClick = { exportMenuOpen = true },
                    modifier = btnModifier,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = "Export", modifier = Modifier.size(iconSize))
                }
                DropdownMenu(
                    expanded = exportMenuOpen,
                    onDismissRequest = { exportMenuOpen = false }
                ) {
                    exportFormats.forEach { format ->
                        ExportMenuItem(
                            format = format,
                            enabled = format !in disabledFormats,
                            onClick = {
                                exportMenuOpen = false
                                onExport(format)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(gap))
        }
        FilledTonalIconButton(
            onClick = onCopy,
            modifier = btnModifier,
            shape = RoundedCornerShape(14.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy text", modifier = Modifier.size(iconSize))
        }
        if (!state.editing) {
            Spacer(modifier = Modifier.size(gap))
            Box {
                var readingMenuOpen by remember { mutableStateOf(false) }
                FilledTonalIconButton(
                    onClick = { readingMenuOpen = true },
                    modifier = btnModifier,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Reading options",
                        modifier = Modifier.size(iconSize)
                    )
                }
                DropdownMenu(
                    expanded = readingMenuOpen,
                    onDismissRequest = { readingMenuOpen = false }
                ) {
                    TextSizeMenuItem()
                    if (previewContent != null && onExport != null) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Show preview") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.FindInPage,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                readingMenuOpen = false
                                previewOpen = true
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Steps the reading font scale. Stays open so the reader can settle on a size by eye. */
@Composable
private fun TextSizeMenuItem() {
    val scale = LocalReadingTextScale.current

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Text size",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = scale::shrink,
                enabled = scale.canShrink,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Remove,
                    contentDescription = "Smaller text",
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = "${(scale.value * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp)
            )
            FilledTonalIconButton(
                onClick = scale::grow,
                enabled = scale.canGrow,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Bigger text",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = scale::reset, enabled = scale.value != 1f) {
                Text("Reset")
            }
        }
    }
}

/** A format the preview can't produce stays visible, but says why it is off. */
@Composable
fun ExportMenuItem(
    format: TextExportFormat,
    enabled: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(format.label) },
        enabled = enabled,
        trailingIcon = if (enabled) {
            null
        } else {
            {
                Text(
                    text = "No table",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
}

@Composable
fun ExtractedTextSection(
    state: ExtractedTextState,
    text: String,
    modifier: Modifier = Modifier,
    credential: (@Composable () -> Unit)? = null,
    markdown: Boolean = false,
    mono: Boolean = false,
    images: Map<String, String> = emptyMap()
) {
    val shown = if (state.editing) state.draft else text
    val counts = remember(shown) {
        val words = shown.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        words to shown.length
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (credential != null && !state.editing) {
            credential()
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            state.editing ->
                EditableReadableText(value = state.draft, onValueChange = { state.draft = it })
            markdown -> MarkdownContent(text = text, images = images)
            mono -> CodeTextContent(text = text)
            else -> ReadableTextContent(text = text)
        }

        Text(
            text = "${counts.first} words · ${counts.second} characters",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun CredentialBadge(
    model: String,
    modifier: Modifier = Modifier
) {
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Cloud,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = "$AI_PROVIDER_NAME · $model",
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ExportMenu(
    expanded: Boolean,
    formats: List<TextExportFormat>,
    disabledFormats: Set<TextExportFormat>,
    onSelect: (TextExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(52.dp, 8.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.width(184.dp)
    ) {
        Text(
            text = "Save as",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
        )
        formats.forEach { format ->
            val enabled = format !in disabledFormats
            DropdownMenuItem(
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 20.dp),
                text = {
                    Text(
                        text = format.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                },
                trailingIcon = if (enabled) {
                    null
                } else {
                    {
                        Text(
                            text = "No table",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = { onSelect(format) }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
