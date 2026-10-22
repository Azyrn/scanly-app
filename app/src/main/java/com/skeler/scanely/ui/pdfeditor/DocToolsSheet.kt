@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.pdfeditor

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.CompressionResult
import com.skeler.scanely.core.pdfeditor.PdfFeature
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdfeditor.engine.CompressionSettings
import com.skeler.scanely.core.pdfeditor.engine.PdfPermissions
import com.skeler.scanely.core.pdfeditor.signature.Integrity
import com.skeler.scanely.core.pdfeditor.signature.SignatureInfo
import com.skeler.scanely.core.pdfeditor.signature.SignatureVerdict
import com.skeler.scanely.core.pdfeditor.signature.TimeSource
import com.skeler.scanely.core.pdfeditor.signature.TrustStatus
import com.skeler.scanely.ui.components.ConnectedGroup
import com.skeler.scanely.ui.components.ConnectedTile
import com.skeler.scanely.ui.components.OpenContent
import com.skeler.scanely.ui.components.groupPositionOf
import com.skeler.scanely.ui.components.rememberStorageAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Some file managers report .p12/.pfx as octet-stream, so the generic type has to be offered. */
private val CERTIFICATE_MIME_TYPES = arrayOf(
    "application/x-pkcs12",
    "application/pkcs12",
    "application/octet-stream"
)

private enum class ToolPane(val title: String, val feature: PdfFeature?) {
    MENU("Document tools", null),
    MERGE("Merge", PdfFeature.MERGE),
    COMPRESS("Compress", PdfFeature.COMPRESS),
    ENCRYPT("Password", PdfFeature.ENCRYPT),
    DECRYPT("Remove password", PdfFeature.DECRYPT),
    METADATA("Metadata", PdfFeature.REMOVE_METADATA),
    SIGN("Sign document", PdfFeature.SIGNATURE),
    VALIDATE("Signatures", PdfFeature.VALIDATE_SIGNATURES),
    LIBRARY("Signature library", PdfFeature.SIGNATURE)
}

@Composable
fun DocToolsSheet(
    isPro: Boolean,
    onDismiss: () -> Unit,
    onRequestPro: () -> Unit
) {
    val activity = LocalActivity.current as ComponentActivity
    val vm: DocToolsViewModel = hiltViewModel(activity)
    val state by vm.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pane by remember { mutableStateOf(ToolPane.MENU) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pane != ToolPane.MENU) {
                    IconButton(onClick = { pane = ToolPane.MENU }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = pane.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            state.message?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    state.savedUri?.let { uri ->
                        TextButton(onClick = { ScanExporter.openPdf(activity, uri) }) {
                            Text("Open")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            when (pane) {
                ToolPane.MENU -> ToolMenu(
                    isPro = isPro,
                    onOpen = { target ->
                        vm.consumeMessage()
                        if (target.feature?.requiresPro == true && !isPro) {
                            onRequestPro()
                        } else {
                            pane = target
                        }
                    }
                )
                ToolPane.MERGE -> MergePane(vm, state)
                ToolPane.COMPRESS -> CompressPane(vm, state)
                ToolPane.ENCRYPT -> EncryptPane(vm)
                ToolPane.DECRYPT -> DecryptPane(vm, state)
                ToolPane.METADATA -> MetadataPane(vm, state)
                ToolPane.SIGN -> SignPane(vm, state)
                ToolPane.VALIDATE -> ValidatePane(vm, state)
                ToolPane.LIBRARY -> LibraryPane(vm)
            }
        }
    }
}

@Composable
private fun ToolMenu(isPro: Boolean, onOpen: (ToolPane) -> Unit) {
    val entries = listOf(
        Triple(ToolPane.MERGE, Icons.AutoMirrored.Rounded.MergeType, "Add pages from another PDF"),
        Triple(ToolPane.COMPRESS, Icons.Rounded.Compress, "Presets, custom quality, or a size target"),
        Triple(ToolPane.ENCRYPT, Icons.Rounded.Lock, "AES-256, with per-action permissions"),
        Triple(ToolPane.DECRYPT, Icons.Rounded.LockOpen, "Remove the password from a copy"),
        Triple(ToolPane.METADATA, Icons.Rounded.Info, "See and strip document info"),
        Triple(ToolPane.SIGN, Icons.Rounded.HistoryEdu, "Sign with a certificate you already have"),
        Triple(ToolPane.VALIDATE, Icons.Rounded.VerifiedUser, "Check embedded digital signatures"),
        Triple(ToolPane.LIBRARY, Icons.Rounded.Gesture, "Reuse a captured signature")
    )

    ConnectedGroup {
        entries.forEachIndexed { index, (target, icon, subtitle) ->
            val locked = target.feature?.requiresPro == true && !isPro
            ConnectedTile(
                position = groupPositionOf(index, entries.size),
                onClick = { onOpen(target) }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (locked) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Pro",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MergePane(vm: DocToolsViewModel, state: DocToolsUiState) {
    val picker = rememberLauncherForActivityResult(OpenContent()) { uri ->
        uri?.let { vm.addMerge(it) }
    }

    state.mergePassword?.let { prompt ->
        PasswordDialog(
            fileName = prompt.fileName,
            isRetry = prompt.isRetry,
            onConfirm = vm::submitMergePassword,
            onDismiss = vm::dismissMergePassword
        )
    }

    Column {
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add a PDF")
        }

        Spacer(Modifier.height(12.dp))

        state.merges.forEach { candidate ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${candidate.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.removeMerge(candidate) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                }
            }
        }

        if (state.merges.isNotEmpty()) {
            PreviewLine("${state.pageCount} pages → ${state.mergedPageCount} pages")
            Spacer(Modifier.height(12.dp))
            Button(onClick = vm::stageMerge, modifier = Modifier.fillMaxWidth()) {
                Text("Add to document")
            }
        }
    }
}

private enum class CompressMode(val label: String) {
    PRESETS("Presets"),
    CUSTOM("Custom"),
    TARGET("Target size")
}

@Composable
private fun CompressPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    var mode by remember { mutableStateOf(CompressMode.PRESETS) }

    Column {
        Text(
            text = "Now ${formatBytes(state.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressMode.entries.forEach { entry ->
                FilterChip(
                    selected = entry == mode,
                    onClick = { mode = entry },
                    label = { Text(entry.label) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (mode) {
            CompressMode.PRESETS -> PresetCompression(vm, state)
            CompressMode.CUSTOM -> CustomCompressionPane(vm, state)
            CompressMode.TARGET -> TargetSizePane(vm, state)
        }
    }
}

@Composable
private fun PresetCompression(vm: DocToolsViewModel, state: DocToolsUiState) {
    LaunchedEffect(Unit) { vm.estimateCompression() }

    if (state.isEstimating) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Trying each level on this document…")
        }
        return
    }

    ConnectedGroup {
        state.estimates.forEachIndexed { index, estimate ->
            ConnectedTile(
                position = groupPositionOf(index, state.estimates.size),
                enabled = estimate.result?.helps == true,
                onClick = {
                    estimate.result?.let { vm.stageCompression(it.settings) }
                }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = estimate.level.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = estimate.level.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ResultFigure(estimate.result)
            }
        }
    }

    if (state.estimates.any { it.result?.helps == false }) {
        Spacer(Modifier.height(10.dp))
        PreviewLine(
            "A level that can't beat the original is greyed out. Some documents are already packed " +
                "as tightly as they go, and re-saving one only adds bytes."
        )
    }
}

@Composable
private fun ResultFigure(result: CompressionResult?) {
    if (result == null) {
        Text(
            text = "Unavailable",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = formatBytes(result.bytes),
            style = MaterialTheme.typography.labelLarge,
            color = if (result.helps) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = if (result.helps) {
                "${result.deltaPercent}%"
            } else {
                "+${result.deltaPercent}% — no saving"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CustomCompressionPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    val custom = state.custom

    Column {
        SettingSlider(
            label = "Image quality",
            value = custom.jpegQuality.toFloat(),
            range = CompressionSettings.MIN_QUALITY.toFloat()..CompressionSettings.MAX_QUALITY.toFloat(),
            display = "${custom.jpegQuality}%",
            onChange = { vm.setCustomQuality(it.roundToInt()) }
        )
        SettingSlider(
            label = "Only images wider than",
            value = custom.minImageEdge.toFloat(),
            range = MIN_EDGE_RANGE,
            display = "${custom.minImageEdge} px",
            onChange = { vm.setCustomMinEdge(it.roundToInt()) }
        )

        Spacer(Modifier.height(8.dp))
        PreviewLine(
            "Below that width an image is a logo or a rule, where re-encoding costs quality and " +
                "saves nothing. Text and vector art are never touched at any setting."
        )
        Spacer(Modifier.height(14.dp))

        when {
            custom.isMeasuring -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Compressing at ${custom.jpegQuality}%…")
            }

            custom.result == null -> Button(
                onClick = vm::measureCustom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try these settings")
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatBytes(state.sizeBytes)} → ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ResultFigure(custom.result)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.stageCompression(custom.settings) },
                    enabled = custom.result.helps,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (custom.result.helps) "Use these settings" else "No saving at these settings")
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun TargetSizePane(vm: DocToolsViewModel, state: DocToolsUiState) {
    var megabytes by remember(state.sizeBytes) {
        mutableStateOf(suggestedTargetMb(state.sizeBytes))
    }
    val search = state.targetSearch

    Column {
        Text(
            text = "Get this under",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = megabytes,
            onValueChange = { entered ->
                megabytes = entered.filter { it.isDigit() || it == '.' }.take(6)
                vm.clearTargetSearch()
            },
            suffix = { Text("MB") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        val targetBytes = megabytes.toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?.let { (it * 1_000_000).toLong() }

        when {
            search?.isRunning == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Searching — pass ${search.probes + 1}…")
            }

            search?.best != null -> {
                val best = search.best
                Text(
                    text = if (search.reachedTarget) {
                        "${formatBytes(best.effectiveBytes)} at ${qualityLabel(best)}."
                    } else {
                        "Couldn't reach that. The smallest this document goes is " +
                            "${formatBytes(best.effectiveBytes)}, at ${qualityLabel(best)}."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                if (!search.reachedTarget) {
                    PreviewLine(
                        "Most of what's left is text and page structure, which compression can " +
                            "repack but not shrink much further."
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { vm.stageCompression(best.settings) },
                    enabled = best.helps,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use this")
                }
            }

            else -> Button(
                onClick = { targetBytes?.let(vm::searchForTarget) },
                enabled = targetBytes != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Find the settings")
            }
        }

        Spacer(Modifier.height(10.dp))
        PreviewLine(
            "Each attempt compresses the document for real, so this takes a few seconds and the " +
                "size it reports is the size you get."
        )
    }
}

private fun qualityLabel(result: CompressionResult): String =
    if (result.settings.touchesImages) {
        "${result.settings.jpegQuality}% image quality"
    } else {
        "no quality loss"
    }

/** Suggests a target below the current size so compression always has work to do. */
private fun suggestedTargetMb(sizeBytes: Long): String {
    val half = sizeBytes / 2_000_000.0
    return when {
        half >= 10 -> half.roundToInt().toString()
        half >= 0.1 -> String.format(Locale.US, "%.1f", half)
        else -> String.format(Locale.US, "%.2f", half.coerceAtLeast(0.01))
    }
}

private val CompressionLevel.label: String
    get() = when (this) {
        CompressionLevel.LOSSLESS -> "Lossless"
        CompressionLevel.BALANCED -> "Balanced"
        CompressionLevel.SMALLEST -> "Smallest"
    }

private val CompressionLevel.explanation: String
    get() = when (this) {
        CompressionLevel.LOSSLESS -> "Structure only — pixel-identical"
        CompressionLevel.BALANCED -> "Images re-encoded at 75% quality"
        CompressionLevel.SMALLEST -> "Images re-encoded at 45% quality"
    }

private val MIN_EDGE_RANGE = 16f..800f

@Composable
private fun EncryptPane(vm: DocToolsViewModel) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var allowPrinting by remember { mutableStateOf(true) }
    var allowCopying by remember { mutableStateOf(false) }
    var allowModifying by remember { mutableStateOf(false) }

    val mismatch = confirm.isNotEmpty() && confirm != password

    Column {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = if (mismatch) {
                { Text("The passwords don't match.") }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        PermissionSwitch("Allow printing", allowPrinting) { allowPrinting = it }
        PermissionSwitch("Allow copying text", allowCopying) { allowCopying = it }
        PermissionSwitch("Allow editing", allowModifying) { allowModifying = it }

        Spacer(Modifier.height(8.dp))

        PreviewLine(
            "AES-256. The saved copy will ask for this password to open. " +
                "Scanly cannot recover it for you."
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                vm.stageEncryption(
                    userPassword = password,
                    ownerPassword = password,
                    permissions = PdfPermissions(
                        printing = allowPrinting,
                        highResPrinting = allowPrinting,
                        modifying = allowModifying,
                        extracting = allowCopying,
                        annotating = allowModifying,
                        fillingForms = true,
                        assembling = allowModifying
                    )
                )
            },
            enabled = password.isNotEmpty() && password == confirm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stage encryption")
        }
    }
}

@Composable
private fun PermissionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DecryptPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    Column {
        if (!state.isEncrypted) {
            Text(
                text = "This document isn't password protected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        PreviewLine(
            "The saved copy will open without a password. The original file on your device is " +
                "left untouched."
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = vm::stageDecryption, modifier = Modifier.fillMaxWidth()) {
            Text("Remove password")
        }
    }
}

@Composable
private fun MetadataPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    var stripXmp by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { vm.loadMetadata() }

    Column {
        val entries = state.metadata?.entries.orEmpty()
        if (entries.isEmpty()) {
            Text(
                text = "No document info is set on this file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            entries.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PermissionSwitch("Also remove XMP metadata", stripXmp) { stripXmp = it }
        Spacer(Modifier.height(8.dp))
        PreviewLine("Everything listed above is removed from the saved copy.")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.stageMetadataStrip(stripXmp) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Remove metadata")
        }
    }
}

@Composable
private fun SignPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    val signing = state.signing
    val bitmaps by vm.signatureBitmaps.collectAsState()
    val picker = rememberLauncherForActivityResult(OpenContent()) { uri ->
        uri?.let(vm::pickCertificate)
    }

    Column {
        if (!state.canSign) {
            Text(
                text = "This document is password-protected, so it can't be signed. Remove the " +
                    "password first — encrypting rewrites the whole file, which would break any " +
                    "signature on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        when {
            signing.identity != null -> UnlockedCertificate(vm, state, bitmaps)
            signing.awaitingPassword -> CertificatePassword(vm, signing)
            else -> {
                Text(
                    text = "Sign with a PKCS#12 certificate (.p12 or .pfx) that you already have. " +
                        "Scanly doesn't issue certificates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { picker.launch(CERTIFICATE_MIME_TYPES) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose certificate")
                }
            }
        }

        signing.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "This proves the document hasn't changed since you signed it, and that the " +
                "signature came from your certificate. It isn't a government-accredited DSC or " +
                "an eIDAS qualified signature — those need a hardware token from a licensed CA.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CertificatePassword(vm: DocToolsViewModel, signing: SigningState) {
    var password by remember { mutableStateOf("") }

    Column {
        Text(
            text = signing.fileName.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Certificate password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Asked for every signature. Scanly never stores it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::clearCertificate) { Text("Cancel") }
            Button(
                onClick = { vm.unlockCertificate(password) },
                enabled = password.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
private fun UnlockedCertificate(
    vm: DocToolsViewModel,
    state: DocToolsUiState,
    bitmaps: Map<String, android.graphics.Bitmap>
) {
    val signing = state.signing
    val identity = signing.identity ?: return
    val expired = !identity.isValidAt(Date())

    Column {
        Text(
            text = shortName(identity.subject),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        PreviewLine("Issued by ${shortName(identity.issuer)}")
        // Built here rather than shared: SimpleDateFormat is not thread-safe, and one held at file
        // scope also freezes the locale it was loaded with.
        val dates = SimpleDateFormat("d MMM yyyy", LocalLocale.current.platformLocale)
        PreviewLine(
            "Valid ${dates.format(identity.notBefore)} to ${dates.format(identity.notAfter)}"
        )
        if (identity.chain.size > 1) {
            PreviewLine("${identity.chain.size} certificates in the chain")
        }
        if (expired) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This certificate is outside its validity window. It can still sign, but " +
                    "readers will flag the signature.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = signing.reason,
            onValueChange = vm::setSigningReason,
            label = { Text("Reason (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = signing.location,
            onValueChange = vm::setSigningLocation,
            label = { Text("Location (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Visible mark",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        if (state.storedSignatures.isEmpty()) {
            Text(
                text = "No saved signatures. The document will be signed without a visible mark.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = signing.appearanceId == null,
                    onClick = { vm.setSigningAppearance(null) },
                    label = { Text("None") }
                )
                state.storedSignatures.forEach { stored ->
                    FilterChip(
                        selected = signing.appearanceId == stored.id,
                        onClick = { vm.setSigningAppearance(stored.id) },
                        label = {
                            bitmaps[stored.id]?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stored.label,
                                    modifier = Modifier.size(width = 56.dp, height = 22.dp)
                                )
                            } ?: Text(stored.label)
                        }
                    )
                }
            }

            if (signing.appearanceId != null) {
                Spacer(Modifier.height(12.dp))
                PageAndPosition(vm, state)
            }
        }

        Spacer(Modifier.height(24.dp))
        val withStorageAccess = rememberStorageAccess()
        Button(
            onClick = { withStorageAccess(vm::signDocument) },
            enabled = !signing.isSigning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (signing.isSigning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (signing.isSigning) "Signing" else "Sign document")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Any staged edits are applied first, so the document can't be changed after " +
                "it is signed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = vm::clearCertificate) { Text("Use a different certificate") }
    }
}

@Composable
private fun PageAndPosition(vm: DocToolsViewModel, state: DocToolsUiState) {
    val signing = state.signing

    Column {
        Text(
            text = "Page ${signing.pageIndex + 1} of ${state.pageCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.pageCount > 1) {
            Slider(
                value = signing.pageIndex.toFloat(),
                onValueChange = { vm.setSigningPage(it.roundToInt()) },
                valueRange = 0f..(state.pageCount - 1).toFloat(),
                steps = (state.pageCount - 2).coerceAtLeast(0)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignaturePosition.entries.forEach { position ->
                FilterChip(
                    selected = signing.position == position,
                    onClick = { vm.setSigningPosition(position) },
                    label = { Text(position.label) }
                )
            }
        }
    }
}

@Composable
private fun ValidatePane(vm: DocToolsViewModel, state: DocToolsUiState) {
    Column {
        if (!state.hasValidated) {
            Button(
                onClick = vm::validateSignatures,
                enabled = !state.isValidating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check signatures")
                }
            }
            return@Column
        }

        if (state.signatures.isEmpty()) {
            Text(
                text = "This document has no digital signatures.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        state.signatures.forEach { SignatureReport(it) }
    }
}

@Composable
private fun SignatureReport(info: SignatureInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = info.verdict.icon,
                contentDescription = null,
                tint = info.verdict.tint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.verdict.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = info.verdict.tint
                )
                Text(
                    text = info.signerName.ifBlank {
                        info.certificates.firstOrNull()?.let { shortName(it.subject) }
                            ?: info.fieldName.ifBlank { "Signature" }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        ReportLine(
            when (info.integrity) {
                Integrity.INTACT -> "Content unchanged since signing"
                Integrity.TAMPERED -> "Content has changed since signing"
                Integrity.UNVERIFIABLE -> "Signature couldn't be read"
            }
        )
        ReportLine(
            when (info.trust) {
                TrustStatus.TRUSTED -> "Issuer chains to a trusted root"
                TrustStatus.UNTRUSTED_ISSUER ->
                    "Issuer isn't in Scanly's trust store — the signature is cryptographically " +
                        "valid, but its issuer can't be confirmed on this device"
                TrustStatus.EXPIRED ->
                    "The signing certificate had already expired when this was signed"
                TrustStatus.NOT_YET_VALID -> "The signing certificate wasn't valid yet"
                TrustStatus.UNKNOWN -> "Issuer could not be checked"
            }
        )
        if (!info.coversWholeDocument) {
            ReportLine("The signature doesn't cover the whole file — it was changed after signing")
        }
        if (info.expiredSinceSigning) {
            ReportLine(
                "The certificate has expired since — normal for an older document, and it " +
                    "doesn't weaken the signature"
            )
        }
        info.signedAtMillis?.let {
            val at = SimpleDateFormat("d MMM yyyy, HH:mm", LocalLocale.current.platformLocale)
                .format(Date(it))
            ReportLine(
                when (info.timeSource) {
                    TimeSource.TIMESTAMP -> "Timestamped $at by a timestamping authority"
                    TimeSource.SIGNED_ATTRIBUTE -> "Signed $at (signer's own clock)"
                    TimeSource.DOCUMENT_DATE -> "Dated $at — unauthenticated"
                    TimeSource.NONE -> "Signed $at"
                }
            )
        }
        info.certificates.firstOrNull()?.let { ReportLine("Issued by ${shortName(it.issuer)}") }
    }
}

private val SignatureVerdict.headline: String
    get() = when (this) {
        SignatureVerdict.VALID -> "Signature valid"
        SignatureVerdict.UNKNOWN -> "Validity unknown"
        SignatureVerdict.INVALID -> "Signature invalid"
    }

private val SignatureVerdict.icon: ImageVector
    get() = when (this) {
        SignatureVerdict.VALID -> Icons.Rounded.CheckCircle
        SignatureVerdict.UNKNOWN -> Icons.AutoMirrored.Rounded.HelpOutline
        SignatureVerdict.INVALID -> Icons.Rounded.Cancel
    }

private val SignatureVerdict.tint: Color
    @Composable get() = when (this) {
        SignatureVerdict.VALID -> VerdictGreen
        SignatureVerdict.UNKNOWN -> VerdictAmber
        SignatureVerdict.INVALID -> MaterialTheme.colorScheme.error
    }

// Fixed status color preserves the familiar PDF-reader verification cue.
private val VerdictGreen = Color(0xFF2E7D32)
private val VerdictAmber = Color(0xFFB26A00)

@Composable
private fun ReportLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

@Composable
private fun LibraryPane(vm: DocToolsViewModel) {
    val state by vm.uiState.collectAsState()
    val bitmaps by vm.signatureBitmaps.collectAsState()

    Column {
        if (state.storedSignatures.isEmpty()) {
            Text(
                text = "No signatures saved yet. Capture one from the page editor's Sign tool.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        state.storedSignatures.forEach { signature ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 88.dp, height = 40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    bitmaps[signature.id]?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = signature.label,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
                Text(
                    text = signature.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.deleteStoredSignature(signature.id) }) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** X.500 names arrive as one long RDN string; the CN is the only part worth showing. */
private fun shortName(distinguishedName: String): String =
    distinguishedName.split(',')
        .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
        ?.trim()
        ?.removePrefix("CN=")
        ?: distinguishedName
