package com.skeler.scanely.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LanguageChipRow(
    cachedLanguages: List<String>,
    currentLanguage: String?,
    showLanguageMenu: Boolean,
    onShowLanguageMenu: () -> Unit,
    onDismissLanguageMenu: () -> Unit,
    onSelectOriginal: () -> Unit,
    onSelectCached: (String) -> Unit,
    allLanguages: List<String>,
    onNewLanguageSelected: (String) -> Unit,
    onComposeText: () -> Unit,
    modifier: Modifier = Modifier,
    showMarkdown: Boolean = false,
    markdownSelected: Boolean = false,
    onSelectMarkdown: () -> Unit = {},
    showJson: Boolean = false,
    jsonSelected: Boolean = false,
    onSelectJson: () -> Unit = {},
    /** False while translation is switched off server-side; cached translations still show. */
    showTranslate: Boolean = true
) {
    val structuredView = markdownSelected || jsonSelected
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentLanguage == null && !structuredView,
            onClick = onSelectOriginal,
            label = { Text("Original") }
        )

        if (showMarkdown) {
            FilterChip(
                selected = markdownSelected,
                onClick = onSelectMarkdown,
                label = { Text("Markdown") }
            )
        }

        if (showJson) {
            FilterChip(
                selected = jsonSelected,
                onClick = onSelectJson,
                label = { Text("JSON") }
            )
        }

        cachedLanguages.forEach { language ->
            FilterChip(
                selected = currentLanguage == language && !structuredView,
                onClick = { onSelectCached(language) },
                label = { Text(language) }
            )
        }

        if (showTranslate) {
            Box {
                FilterChip(
                    selected = false,
                    onClick = onShowLanguageMenu,
                    label = { Text("Translate") }
                )

                DropdownMenu(
                    expanded = showLanguageMenu,
                    onDismissRequest = onDismissLanguageMenu
                ) {
                    allLanguages.filterNot { it in cachedLanguages }.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language) },
                            onClick = { onNewLanguageSelected(language) }
                        )
                    }
                }
            }
        }

        AssistChip(
            onClick = onComposeText,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.PostAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            label = { Text("Text to PDF") }
        )
    }
}
