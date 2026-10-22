@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.docchat.presentation.screen

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.ui.viewmodel.DesignSource

/** Where a file comes from — nothing about what it is for; the composer asks that. One row of four. */
@Composable
internal fun AttachSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onScans: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val pick = { action: () -> Unit ->
            onDismiss()
            action()
        }
        Text(
            stringResource(R.string.chat_attach_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)
        ) {
            AttachTile(R.drawable.ic_sym_photo_camera, R.string.chat_attach_camera) { pick(onCamera) }
            AttachTile(R.drawable.ic_sym_image, R.string.chat_attach_photos) { pick(onPhotos) }
            AttachTile(R.drawable.ic_sym_folder_open, R.string.chat_attach_files) { pick(onFiles) }
            AttachTile(R.drawable.ic_sym_document_scanner, R.string.chat_attach_scans) { pick(onScans) }
        }
    }
}

@Composable
private fun RowScope.AttachTile(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ScansSheet(scans: List<DesignSource>, onPick: (DesignSource) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chat_scans_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        if (scans.isEmpty()) {
            Text(
                stringResource(R.string.chat_no_scans),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 16.dp)) {
                items(scans, key = { it.id }) { scan ->
                    ListItem(
                        headlineContent = { Text(scan.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(scan.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(scan) }
                    )
                }
            }
        }
    }
}
