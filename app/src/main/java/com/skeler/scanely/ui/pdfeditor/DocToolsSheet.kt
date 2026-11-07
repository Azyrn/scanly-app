@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.pdfeditor

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.R
import com.skeler.scanely.core.pdf.DocumentIntents
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

private enum class ToolPane(@StringRes val title: Int, val feature: PdfFeature?) {
    MENU(R.string.pe_pane_menu, null),
    MERGE(R.string.pe_pane_merge, PdfFeature.MERGE),
    COMPRESS(R.string.pe_pane_compress, PdfFeature.COMPRESS),
    ENCRYPT(R.string.pe_pane_password, PdfFeature.ENCRYPT),
    DECRYPT(R.string.pe_pane_remove_password, PdfFeature.DECRYPT),
    METADATA(R.string.pe_pane_metadata, PdfFeature.REMOVE_METADATA),
    SIGN(R.string.pe_pane_sign, PdfFeature.SIGNATURE),
    VALIDATE(R.string.pe_pane_signatures, PdfFeature.VALIDATE_SIGNATURES),
    LIBRARY(R.string.pe_pane_library, PdfFeature.SIGNATURE)
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
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

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
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.pdf_tools_back)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = stringResource(pane.title),
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
                        TextButton(onClick = { DocumentIntents.openPdf(activity, uri) }) {
                            stringResource(R.string.chat_file_open)
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
        Triple(ToolPane.MERGE, Icons.AutoMirrored.Rounded.MergeType, stringResource(R.string.pe_menu_merge_hint)),
        Triple(ToolPane.COMPRESS, Icons.Rounded.Compress, stringResource(R.string.pe_menu_compress_hint)),
        Triple(ToolPane.ENCRYPT, Icons.Rounded.Lock, stringResource(R.string.pe_menu_encrypt_hint)),
        Triple(ToolPane.DECRYPT, Icons.Rounded.LockOpen, stringResource(R.string.pe_menu_decrypt_hint)),
        Triple(ToolPane.METADATA, Icons.Rounded.Info, stringResource(R.string.pe_menu_metadata_hint)),
        Triple(ToolPane.SIGN, Icons.Rounded.HistoryEdu, stringResource(R.string.pe_menu_sign_hint)),
        Triple(ToolPane.VALIDATE, Icons.Rounded.VerifiedUser, stringResource(R.string.pe_menu_validate_hint)),
        Triple(ToolPane.LIBRARY, Icons.Rounded.Gesture, stringResource(R.string.pe_menu_library_hint))
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
                        text = stringResource(target.title),
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
            stringResource(R.string.pe_add_a_pdf)
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
                        text = pluralStringResource(
                            R.plurals.pe_merge_page_count,
                            candidate.pageCount,
                            candidate.pageCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.removeMerge(candidate) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.pe_remove))
                }
            }
        }

        if (state.merges.isNotEmpty()) {
            PreviewLine(
                stringResource(
                    R.string.pe_merge_page_change,
                    pluralStringResource(R.plurals.pe_merge_page_count, state.pageCount, state.pageCount),
                    pluralStringResource(R.plurals.pe_merge_page_count, state.mergedPageCount, state.mergedPageCount)
                )
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = vm::stageMerge, modifier = Modifier.fillMaxWidth()) {
                stringResource(R.string.pe_add_to_document)
            }
        }
    }
}

private enum class CompressMode(@StringRes val label: Int) {
    PRESETS(R.string.pe_compress_presets),
    CUSTOM(R.string.pe_compress_custom),
    TARGET(R.string.pe_compress_target)
}

@Composable
private fun CompressPane(vm: DocToolsViewModel, state: DocToolsUiState) {
    var mode by remember { mutableStateOf(CompressMode.PRESETS) }

    Column {
        Text(
            text = stringResource(R.string.pe_compress_now, formatBytes(state.sizeBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressMode.entries.forEach { entry ->
                FilterChip(
                    selected = entry == mode,
                    onClick = { mode = entry },
                    label = { Text(stringResource(entry.label)) }
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
            stringResource(R.string.pe_compress_trying_levels)
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
            stringResource(R.string.pe_compress_greyed_hint)
        )
    }
}

@Composable
private fun ResultFigure(result: CompressionResult?) {
    if (result == null) {
        Text(
            text = stringResource(R.string.pe_unavailable),
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
                stringResource(R.string.pe_percent_value, result.deltaPercent)
            } else {
                stringResource(R.string.pe_delta_no_saving, result.deltaPercent)
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
            label = stringResource(R.string.pe_compress_image_quality),
            value = custom.jpegQuality.toFloat(),
            range = CompressionSettings.MIN_QUALITY.toFloat()..CompressionSettings.MAX_QUALITY.toFloat(),
            display = stringResource(R.string.pe_percent_value, custom.jpegQuality),
            onChange = { vm.setCustomQuality(it.roundToInt()) }
        )
        SettingSlider(
            label = stringResource(R.string.pe_compress_min_edge_label),
            value = custom.minImageEdge.toFloat(),
            range = MIN_EDGE_RANGE,
            display = stringResource(R.string.pe_px_value, custom.minImageEdge),
            onChange = { vm.setCustomMinEdge(it.roundToInt()) }
        )

        Spacer(Modifier.height(8.dp))
        PreviewLine(
            stringResource(R.string.pe_compress_custom_hint)
        )
        Spacer(Modifier.height(14.dp))

        when {
            custom.isMeasuring -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                stringResource(R.string.pe_compressing_at, custom.jpegQuality)
            }

            custom.result == null -> Button(
                onClick = vm::measureCustom,
                modifier = Modifier.fillMaxWidth()
            ) {
                stringResource(R.string.pe_try_these_settings)
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.pe_size_to_result, formatBytes(state.sizeBytes)),
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
                    Text(
                        if (custom.result.helps) {
                            stringResource(R.string.pe_use_these_settings)
                        } else {
                            stringResource(R.string.pe_no_saving_at_these_settings)
                        }
                    )
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
        // The state follows the caller's value each composition; onChange still owns updates.
        val sliderState = remember(range) { SliderState(value, trackRange = range) }
        sliderState.value = value
        Slider(state = sliderState, onValueChange = onChange)
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
            text = stringResource(R.string.pe_get_this_under),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = megabytes,
            onValueChange = { entered ->
                megabytes = entered.filter { it.isDigit() || it == '.' }.take(6)
                vm.clearTargetSearch()
            },
            suffix = { stringResource(R.string.pe_unit_mb) },
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
                stringResource(R.string.pe_searching_pass, search.probes + 1)
            }

            search?.best != null -> {
                val best = search.best
                Text(
                    text = if (search.reachedTarget) {
                        stringResource(
                            R.string.pe_reached_size_at,
                            formatBytes(best.effectiveBytes),
                            qualityLabel(best)
                        )
                    } else {
                        stringResource(
                            R.string.pe_not_reached_smallest,
                            formatBytes(best.effectiveBytes),
                            qualityLabel(best)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                if (!search.reachedTarget) {
                    PreviewLine(
                        stringResource(R.string.pe_compress_leftover_hint)
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { vm.stageCompression(best.settings) },
                    enabled = best.helps,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    stringResource(R.string.pe_use_this)
                }
            }

            else -> Button(
                onClick = { targetBytes?.let(vm::searchForTarget) },
                enabled = targetBytes != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                stringResource(R.string.pe_find_the_settings)
            }
        }

        Spacer(Modifier.height(10.dp))
        PreviewLine(
            stringResource(R.string.pe_compress_target_hint)
        )
    }
}

private fun qualityLabel(result: CompressionResult): String =
    if (result.settings.touchesImages) {
        "${result.settings.jpegQuality}% image quality"
    } else {
        "no quality loss"
    }

private const val BYTES_PER_MB = 1_000_000.0
private const val BYTES_PER_KB = 1_000.0
private const val WHOLE_MB_FROM = 10.0
private const val TENTHS_MB_FROM = 0.1
private const val MIN_TARGET_MB = 0.01

/** Suggests a target below the current size so compression always has work to do. */
private fun suggestedTargetMb(sizeBytes: Long): String {
    val half = sizeBytes / (2 * BYTES_PER_MB)
    return when {
        half >= WHOLE_MB_FROM -> half.roundToInt().toString()
        half >= TENTHS_MB_FROM -> String.format(Locale.US, "%.1f", half)
        else -> String.format(Locale.US, "%.2f", half.coerceAtLeast(MIN_TARGET_MB))
    }
}

private val CompressionLevel.label: String
    @Composable get() = stringResource(
        when (this) {
            CompressionLevel.LOSSLESS -> R.string.pe_level_lossless
            CompressionLevel.BALANCED -> R.string.pe_level_balanced
            CompressionLevel.SMALLEST -> R.string.pe_level_smallest
        }
    )

private val CompressionLevel.explanation: String
    @Composable get() = stringResource(
        when (this) {
            CompressionLevel.LOSSLESS -> R.string.pe_level_lossless_detail
            CompressionLevel.BALANCED -> R.string.pe_level_balanced_detail
            CompressionLevel.SMALLEST -> R.string.pe_level_smallest_detail
        }
    )

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
            label = { stringResource(R.string.pdf_tool_password_label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { stringResource(R.string.pdf_tool_protect_confirm) },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = if (mismatch) {
                { Text(stringResource(R.string.pdf_tool_protect_mismatch)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        PermissionSwitch(stringResource(R.string.pe_allow_printing), allowPrinting) { allowPrinting = it }
        PermissionSwitch(stringResource(R.string.pe_allow_copying_text), allowCopying) { allowCopying = it }
        PermissionSwitch(stringResource(R.string.pe_allow_editing), allowModifying) { allowModifying = it }

        Spacer(Modifier.height(8.dp))

        PreviewLine(
            stringResource(R.string.pe_encrypt_hint)
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
            stringResource(R.string.pe_stage_encryption)
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
                text = stringResource(R.string.pe_not_password_protected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        PreviewLine(
            stringResource(R.string.pe_decrypt_hint)
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = vm::stageDecryption, modifier = Modifier.fillMaxWidth()) {
            stringResource(R.string.pe_remove_password)
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
                text = stringResource(R.string.pe_no_document_info),
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
        PermissionSwitch(stringResource(R.string.pe_also_remove_xmp), stripXmp) { stripXmp = it }
        Spacer(Modifier.height(8.dp))
        PreviewLine(stringResource(R.string.pe_metadata_hint))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.stageMetadataStrip(stripXmp) },
            modifier = Modifier.fillMaxWidth()
        ) {
            stringResource(R.string.pe_remove_metadata)
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
                text = stringResource(R.string.pe_sign_password_protected_hint),
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
                    text = stringResource(R.string.pe_sign_intro_hint),
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
                    stringResource(R.string.pe_choose_certificate)
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
            text = stringResource(R.string.pe_sign_disclaimer),
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
            label = { stringResource(R.string.pe_certificate_password) },
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
            text = stringResource(R.string.pe_asked_every_signature),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::clearCertificate) { stringResource(R.string.chat_cancel) }
            Button(
                onClick = { vm.unlockCertificate(password) },
                enabled = password.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                stringResource(R.string.pe_unlock)
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
        PreviewLine(stringResource(R.string.pe_issued_by, shortName(identity.issuer)))
        // Built here rather than shared: SimpleDateFormat is not thread-safe, and one held at file
        // scope also freezes the locale it was loaded with.
        val dates = SimpleDateFormat("d MMM yyyy", LocalLocale.current.platformLocale)
        PreviewLine(
            stringResource(R.string.pe_valid_range, dates.format(identity.notBefore), dates.format(identity.notAfter))
        )
        if (identity.chain.size > 1) {
            PreviewLine(pluralStringResource(R.plurals.pe_chain_count, identity.chain.size, identity.chain.size))
        }
        if (expired) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.pe_certificate_expired_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = signing.reason,
            onValueChange = vm::setSigningReason,
            label = { stringResource(R.string.pe_reason_optional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = signing.location,
            onValueChange = vm::setSigningLocation,
            label = { stringResource(R.string.pe_location_optional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.pe_visible_mark),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        if (state.storedSignatures.isEmpty()) {
            Text(
                text = stringResource(R.string.pe_no_saved_signatures),
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
                    label = { stringResource(R.string.pdf_tool_img2pdf_margin_none) }
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
            Text(
                if (signing.isSigning) {
                    stringResource(R.string.pe_signing)
                } else {
                    stringResource(R.string.pe_sign_document)
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pe_sign_staged_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = vm::clearCertificate) { stringResource(R.string.pe_use_different_certificate) }
    }
}

@Composable
private fun PageAndPosition(vm: DocToolsViewModel, state: DocToolsUiState) {
    val signing = state.signing

    Column {
        Text(
            text = stringResource(R.string.pdf_stamp_numbers_format_page_of, signing.pageIndex + 1, state.pageCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.pageCount > 1) {
            val pageSteps = (state.pageCount - 2).coerceAtLeast(0)
            val pageRange = 0f..(state.pageCount - 1).toFloat()
            val pageSlider = remember(pageSteps, pageRange) { SliderState(steps = pageSteps, trackRange = pageRange) }
            pageSlider.value = signing.pageIndex.toFloat()
            Slider(
                state = pageSlider,
                onValueChange = { vm.setSigningPage(it.roundToInt()) }
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
                    label = { Text(stringResource(position.label)) }
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
                    stringResource(R.string.pe_check_signatures)
                }
            }
            return@Column
        }

        if (state.signatures.isEmpty()) {
            Text(
                text = stringResource(R.string.pe_no_digital_signatures),
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
                            ?: info.fieldName.ifBlank { stringResource(R.string.pe_signature) }
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
                Integrity.INTACT -> stringResource(R.string.pe_integrity_intact)
                Integrity.TAMPERED -> stringResource(R.string.pe_integrity_tampered)
                Integrity.UNVERIFIABLE -> stringResource(R.string.pe_integrity_unverifiable)
            }
        )
        ReportLine(
            when (info.trust) {
                TrustStatus.TRUSTED -> stringResource(R.string.pe_trust_trusted)
                TrustStatus.UNTRUSTED_ISSUER ->
                    stringResource(R.string.pe_trust_untrusted_issuer)
                TrustStatus.EXPIRED ->
                    stringResource(R.string.pe_trust_expired)
                TrustStatus.NOT_YET_VALID -> stringResource(R.string.pe_trust_not_yet_valid)
                TrustStatus.UNKNOWN -> stringResource(R.string.pe_trust_unknown)
            }
        )
        if (!info.coversWholeDocument) {
            ReportLine(stringResource(R.string.pe_not_whole_document))
        }
        if (info.expiredSinceSigning) {
            ReportLine(
                stringResource(R.string.pe_expired_since_signing)
            )
        }
        info.signedAtMillis?.let {
            val at = SimpleDateFormat("d MMM yyyy, HH:mm", LocalLocale.current.platformLocale)
                .format(Date(it))
            ReportLine(
                when (info.timeSource) {
                    TimeSource.TIMESTAMP -> stringResource(R.string.pe_time_timestamp, at)
                    TimeSource.SIGNED_ATTRIBUTE -> stringResource(R.string.pe_time_signed_clock, at)
                    TimeSource.DOCUMENT_DATE -> stringResource(R.string.pe_time_document_date, at)
                    TimeSource.NONE -> stringResource(R.string.pe_time_signed, at)
                }
            )
        }
        info.certificates.firstOrNull()?.let { ReportLine(stringResource(R.string.pe_issued_by, shortName(it.issuer))) }
    }
}

private val SignatureVerdict.headline: String
    @Composable get() = stringResource(
        when (this) {
            SignatureVerdict.VALID -> R.string.pe_verdict_valid
            SignatureVerdict.UNKNOWN -> R.string.pe_verdict_unknown
            SignatureVerdict.INVALID -> R.string.pe_verdict_invalid
        }
    )

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
                text = stringResource(R.string.pe_no_signatures_saved),
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
                    stringResource(R.string.chat_delete_confirm)
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
    bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
    bytes >= BYTES_PER_KB -> String.format(Locale.US, "%.0f kB", bytes / BYTES_PER_KB)
    else -> "$bytes B"
}

/** X.500 names arrive as one long RDN string; the CN is the only part worth showing. */
private fun shortName(distinguishedName: String): String =
    distinguishedName.split(',')
        .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
        ?.trim()
        ?.removePrefix("CN=")
        ?: distinguishedName
