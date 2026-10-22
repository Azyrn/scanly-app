@file:OptIn(ExperimentalLayoutApi::class)

package com.skeler.scanely.ui.pdftools.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skeler.scanely.ui.pdftools.OPTIONS_ROW_GAP

/**
 * A titled single-choice group of chips, for the short option lists Page numbers and Watermark
 * have several of; chips wrap rather than scroll so every choice stays visible on a narrow phone.
 */
@Composable
internal fun <T> ChipChoice(
    @StringRes title: Int,
    choices: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    ChipChoiceText(
        title = title,
        choices = choices.map { (value, label) -> value to stringResource(label) },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier
    )
}

/** [ChipChoice] with labels that are already text, for chips that show a filled-in sample. */
@Composable
internal fun <T> ChipChoiceText(
    @StringRes title: Int,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TITLE_GAP)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(OPTIONS_ROW_GAP)
        ) {
            choices.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}

private val TITLE_GAP = 4.dp
