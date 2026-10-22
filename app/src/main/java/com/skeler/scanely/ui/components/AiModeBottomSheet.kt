package com.skeler.scanely.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.AiFeature
import com.skeler.scanely.core.ai.AiMode
import com.skeler.scanely.core.ai.feature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModeBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onModeSelected: (AiMode) -> Unit,
    /** Features switched off server-side are left out; see `FeatureSwitches`. */
    offeredFeatures: Set<AiFeature> = AiFeature.entries.toSet(),
    /** Extraction is paid-only and its presets come from the server; hidden until both are true. */
    showExtractData: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Text(
                text = "AI Scan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            if (AiMode.EXTRACT_TEXT.feature in offeredFeatures) {
                AiModeItem(
                    iconRes = R.drawable.ic_action_extract_text,
                    title = "Extract Text",
                    subtitle = "Extract visible text from image",
                    onClick = { onModeSelected(AiMode.EXTRACT_TEXT) }
                )
            }

            if (AiMode.EXTRACT_PDF_TEXT.feature in offeredFeatures) {
                AiModeItem(
                    iconRes = R.drawable.ic_action_extract_pdf,
                    title = "Extract Document",
                    subtitle = "PDF, Word, PowerPoint and text files",
                    onClick = { onModeSelected(AiMode.EXTRACT_PDF_TEXT) }
                )
            }

            if (showExtractData && AiMode.EXTRACT_DATA.feature in offeredFeatures) {
                AiModeItem(
                    icon = Icons.Rounded.DataObject,
                    title = "Extract Data",
                    subtitle = "Receipts, invoices and cards, as labelled fields",
                    onClick = { onModeSelected(AiMode.EXTRACT_DATA) }
                )
            }

            if (AiMode.TRANSCRIBE_AUDIO.feature in offeredFeatures) {
                AiModeItem(
                    iconRes = R.drawable.ic_action_waveform,
                    title = "Audio to Text",
                    subtitle = "Recordings, meetings and video, speaker by speaker",
                    onClick = { onModeSelected(AiMode.TRANSCRIBE_AUDIO) }
                )
            }

            // One entry for every AI-written document: describing one is the Custom tile, so
            // the old Create Document card would only duplicate it.
            if (AiMode.DESIGN_DOCUMENT.feature in offeredFeatures) {
                AiModeItem(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.ai_build_documents_title),
                    subtitle = stringResource(R.string.ai_build_documents_subtitle),
                    onClick = { onModeSelected(AiMode.DESIGN_DOCUMENT) }
                )
            }
        }
    }
}

@Composable
private fun AiModeItem(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) = AiModeItem(title, subtitle, onClick) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun AiModeItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) = AiModeItem(title, subtitle, onClick) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun AiModeItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            },
            supportingContent = {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = leading
        )
    }
}
