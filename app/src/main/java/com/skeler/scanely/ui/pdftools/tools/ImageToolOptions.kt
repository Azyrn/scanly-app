package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Small pieces the four image tools share, drawn like the PDF tools' own options (ToolOptions.kt)
 * so the grid feels like one app: bordered radio cards, a switch row, a full-width action.
 */

@Composable
internal fun ImageChoiceRow(title: String, body: String?, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CARD_SHAPE,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(BORDER_WIDTH, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = ROW_PADDING_H)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ImageSwitchRow(title: String, body: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = ROW_PADDING_H)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ImageActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Composable
internal fun ImageBodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal val IMAGE_SECTION_GAP = 12.dp

private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val BORDER_WIDTH = 1.dp
private val ROW_PADDING_H = 12.dp
private val ROW_PADDING_V = 12.dp
