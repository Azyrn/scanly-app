@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.PageRange

/**
 * Offered when a picked PDF has more than one page.
 *
 * Pages are the billing unit, so scanning five pages of a two-hundred-page contract should cost
 * five. [onConfirm] gets the range string, or null for the whole document.
 */
@Composable
fun PageRangeDialog(
    fileName: String,
    totalPages: Int,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** A hard ceiling on how many pages this scan may cover — extraction reads at most eight. */
    maxPages: Int? = null
) {
    var spec by remember { mutableStateOf("") }
    val selected = remember(spec, maxPages) {
        spec.takeIf { it.isNotBlank() }
            ?.let { PageRange.parse(it, totalPages) }
            ?.takeIf { maxPages == null || it.size <= maxPages }
    }
    val invalid = spec.isNotBlank() && selected == null
    // "Scan all" is not on offer when the whole file is over the ceiling.
    val mustChoose = maxPages != null && totalPages > maxPages

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.cmp_page_range_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = if (mustChoose) {
                        pluralStringResource(
                            R.plurals.cmp_page_range_over_ceiling,
                            totalPages,
                            fileName,
                            totalPages,
                            maxPages
                        )
                    } else {
                        pluralStringResource(R.plurals.cmp_page_range_intro, totalPages, fileName, totalPages)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = spec,
                    onValueChange = { spec = it },
                    singleLine = true,
                    isError = invalid,
                    label = { Text(stringResource(R.string.pdf_tool_split_ranges_label)) },
                    placeholder = { Text(stringResource(R.string.cmp_page_range_example)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    supportingText = {
                        Text(
                            when {
                                invalid && mustChoose ->
                                    pluralStringResource(
                                        R.plurals.cmp_page_range_pick_up_to,
                                        maxPages,
                                        maxPages,
                                        totalPages
                                    )
                                invalid ->
                                    stringResource(R.string.cmp_page_range_use_numbers, totalPages)
                                selected != null ->
                                    pluralStringResource(
                                        R.plurals.cmp_page_range_n_of_total,
                                        totalPages,
                                        selected.size,
                                        totalPages
                                    )
                                mustChoose -> pluralStringResource(
                                    R.plurals.cmp_page_range_required,
                                    maxPages,
                                    maxPages
                                )
                                else -> pluralStringResource(
                                    R.plurals.cmp_page_range_leave_empty,
                                    totalPages,
                                    totalPages
                                )
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(stringResource(R.string.chat_cancel))
                    }
                    Button(
                        onClick = {
                            val pages = selected
                            onConfirm(
                                when {
                                    pages == null -> null
                                    PageRange.isWholeDocument(pages, totalPages) -> null
                                    else -> pages.joinToString(",")
                                }
                            )
                        },
                        enabled = !invalid && (selected != null || !mustChoose),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(
                            if (selected != null) {
                                stringResource(R.string.cmp_page_range_scan_count, selected.size)
                            } else {
                                stringResource(R.string.cmp_page_range_scan_all)
                            }
                        )
                    }
                }
            }
        }
    }
}
