@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.navigation.OnScreenExit
import com.skeler.scanely.ui.theme.ScanTokens

/** The crop (spec 4): drag the four corners, then add the page or finish. */
@Composable
fun ScanCropScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val vm: ScanSessionViewModel = hiltViewModel(activity)
    OnScreenExit { vm.dropCrop() }
    CropContent(vm)
}

@Composable
private fun CropContent(vm: ScanSessionViewModel) {
    val navigator = LocalNavigator.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val crop = state.crop
    val event = state.event

    ScanEventHandler(vm, event, onBackToCamera = { navigator.back() })
    // Nothing to crop: the photo couldn't be read (its message shows first), or the session is gone.
    LaunchedEffect(event, crop == null) { if (crop == null && event == null) navigator.back() }
    val retake = {
        vm.dropCrop()
        navigator.back()
    }
    val ready = crop?.photo != null && !state.working

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(ScanTags.CROP_ROOT)
    ) {
        val loupe = rememberLoupeState()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            CropTopBar(onRetake = retake, onRotate = vm::rotate, rotateEnabled = ready)
            CropImageArea(
                crop = crop,
                loupe = loupe,
                onCorner = { i, p -> vm.edit { it.movedCorner(i, p) } },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            ToolRow(
                enabled = ready,
                onAuto = { vm.edit(CropSession::auto) },
                onFull = { vm.edit(CropSession::fullImage) }
            )
            Spacer(Modifier.height(ScanTokens.Space4))
            ActionRow(
                actions = state.cropActions,
                enabled = ready,
                onSecondary = {
                    if (state.cropActions == CropActions.ADD_PAGE_DONE) {
                        vm.confirm(
                            done = false
                        )
                    } else {
                        retake()
                    }
                },
                onPrimary = { vm.confirm(done = state.cropActions != CropActions.RETAKE_NEXT_BACK) }
            )
            Spacer(Modifier.height(ScanTokens.Space4))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        crop?.photo?.let { Loupe(loupe, it, crop.quad) }
    }
}

@Composable
private fun CropTopBar(onRetake: () -> Unit, onRotate: () -> Unit, rotateEnabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScanTokens.TopBarHeight)
            .testTag(ScanTags.CROP_TOP_BAR) // the whole bar, before its padding (spec: W x 64)
            .padding(horizontal = ScanTokens.TopBarPadding)
    ) {
        IconButton(
            onClick = onRetake,
            modifier = Modifier.align(Alignment.CenterStart).testTag(ScanTags.CROP_RETAKE)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.scan_retake),
                tint = colors.onSurface,
                modifier = Modifier.size(ScanTokens.IconSize)
            )
        }
        Text(
            text = stringResource(R.string.scan_crop_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = ScanTokens.TouchTarget)
                .testTag(ScanTags.CROP_TITLE)
        )
        IconButton(
            onClick = onRotate,
            enabled = rotateEnabled,
            modifier = Modifier.align(Alignment.CenterEnd).testTag(ScanTags.CROP_ROTATE)
        ) {
            Icon(
                Icons.Rounded.Rotate90DegreesCw,
                contentDescription = stringResource(R.string.scan_rotate),
                tint = colors.onSurface,
                modifier = Modifier.size(ScanTokens.IconSize)
            )
        }
    }
}

@Composable
private fun ToolRow(enabled: Boolean, onAuto: () -> Unit, onFull: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ScanTags.CROP_TOOLS)
            // One touch target tall as equal padding around the 40 dp buttons, so they stay exactly centred:
            // a fixed 48 dp height rounded differently from their touch targets and left them 1.5 px low.
            .padding(vertical = (ScanTokens.TouchTarget - ScanTokens.SmallControlHeight) / 2),
        horizontalArrangement = Arrangement.spacedBy(ScanTokens.Space2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Rounded.AutoFixHigh, R.string.scan_crop_auto, ScanTags.CROP_AUTO, enabled, onAuto)
        ToolButton(Icons.Rounded.CropFree, R.string.scan_crop_full, ScanTags.CROP_FULL, enabled, onFull)
    }
}

/** Button S tonal: 40 dp (48 dp target), 20 dp icon, 8 dp icon–label (spec 4 row 4). */
@Composable
private fun ToolButton(icon: ImageVector, label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ScanTokens.SmallControlHeight).testTag(tag),
        contentPadding = ButtonDefaults.SmallContentPadding
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ScanTokens.SmallButtonIconSize))
        Spacer(Modifier.width(ScanTokens.Space2))
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Two equal Button M (spec 4 row 5); labels by mode and ID side. */
@Composable
private fun ActionRow(actions: CropActions, enabled: Boolean, onSecondary: () -> Unit, onPrimary: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (secondary, primary) = when (actions) {
        CropActions.ADD_PAGE_DONE -> R.string.scan_add_page to R.string.scan_done
        CropActions.RETAKE_NEXT_BACK -> R.string.scan_retake to R.string.scan_next_back
        CropActions.RETAKE_MAKE_COPY -> R.string.scan_retake to R.string.scan_make_copy
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScanTokens.MediumControlSize)
            .padding(horizontal = ScanTokens.Space6)
            .testTag(ScanTags.CROP_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(ScanTokens.Space3)
    ) {
        OutlinedButton(
            onClick = onSecondary,
            enabled = enabled || actions != CropActions.ADD_PAGE_DONE,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(ScanTags.CROP_SECONDARY),
            border = BorderStroke(ScanTokens.HairStroke, colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
            contentPadding = ButtonDefaults.MediumContentPadding
        ) {
            Text(stringResource(secondary), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Button(
            onClick = onPrimary,
            enabled = enabled,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(ScanTags.CROP_PRIMARY),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            contentPadding = ButtonDefaults.MediumContentPadding
        ) {
            Text(stringResource(primary), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
