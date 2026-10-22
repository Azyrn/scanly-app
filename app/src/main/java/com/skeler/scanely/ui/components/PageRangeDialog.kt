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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                    text = "Scan which pages?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = if (mustChoose) {
                        "$fileName has $totalPages pages, and this scan reads at most $maxPages. " +
                            "Pick the ones that matter."
                    } else {
                        "$fileName has $totalPages pages. Every page you scan counts against " +
                            "your plan, so a range costs less than the whole file."
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
                    label = { Text("Pages") },
                    placeholder = { Text("e.g. 1-5, 8") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    supportingText = {
                        Text(
                            when {
                                invalid && mustChoose ->
                                    "Pick up to $maxPages pages between 1 and $totalPages."
                                invalid ->
                                    "Use page numbers between 1 and $totalPages, like 1-5 or 2,4."
                                selected != null -> "${selected.size} of $totalPages pages"
                                mustChoose -> "Required — this scan covers $maxPages pages"
                                else -> "Leave empty to scan all $totalPages pages"
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
                        Text("Cancel")
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
                        Text(if (selected != null) "Scan ${selected.size}" else "Scan all")
                    }
                }
            }
        }
    }
}
