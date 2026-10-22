package com.skeler.scanely.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skeler.scanely.core.network.ExtractionPresetDto

/**
 * Which kind of document is being extracted.
 *
 * The list comes from the server ([ExtractionPresetDto] via `/v1/me`), not from the APK, so a new
 * preset reaches users without an app release — and so the app never holds a schema it could send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionPresetSheet(
    sheetState: SheetState,
    presets: List<ExtractionPresetDto>,
    pageCost: Int,
    /** False on a plan without extraction: say so here rather than after the file is picked. */
    included: Boolean,
    onDismiss: () -> Unit,
    onPresetSelected: (ExtractionPresetDto) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Extract data",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
            )
            Text(
                text = if (included) {
                    "Get labelled fields as well as the text. Reading a page as data costs " +
                        "$pageCost credits from your plan. Each document can have up to 8 pages."
                } else {
                    "Get labelled fields as well as the text. Included with Pro and above."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )

            presets.forEach { preset ->
                Card(
                    onClick = { onPresetSelected(preset) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.DataObject,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
