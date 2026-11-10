package com.skeler.scanely.ui.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.skeler.scanely.R
import com.skeler.scanely.ui.components.pressScale
import com.skeler.scanely.ui.theme.ScanTokens

private const val FULL_CIRCLE = 360f
private const val TWELVE_O_CLOCK = -90f

/** The most photos one gallery pick hands over; the session takes as many as its goal needs. */
private const val GALLERY_PICK_MAX = 20

/** Gallery · shutter · page stack, in equal thirds (spec 3.1 row 5, R8) on one centre line (R9). */
@Composable
fun BottomBar(
    state: ScanSessionUiState,
    ring: () -> Float,
    canShoot: Boolean,
    onShutter: () -> Unit,
    onImport: (List<Uri>) -> Unit,
    onReview: () -> Unit
) {
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImport(listOf(uri))
    }
    val pickMany = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(GALLERY_PICK_MAX)
    ) { uris -> onImport(uris) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScanTokens.BottomBarHeight)
            .testTag(ScanTags.CAMERA_BOTTOM_BAR),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Subtle glass: a faint fill and hairline rim in the content colour (white on the dark camera,
            // onSurface in a light theme, where white would vanish), so it doesn't compete with the shutter.
            val ink = if (isLightScan()) MaterialTheme.colorScheme.onSurface else Color.White
            IconButton(
                onClick = { if (state.importLimit > 1) pickMany.launch(request) else pickOne.launch(request) },
                enabled = state.importLimit > 0,
                modifier = Modifier
                    .size(ScanTokens.GlassButtonSize)
                    .border(ScanTokens.GlassRim, ink.copy(alpha = GLASS_RIM_ALPHA), CircleShape)
                    .testTag(ScanTags.CAMERA_GALLERY),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = ink.copy(alpha = GLASS_FILL_ALPHA),
                    contentColor = ink
                )
            ) {
                Icon(
                    painterResource(R.drawable.ic_sym_photo_library),
                    contentDescription = stringResource(R.string.scan_import),
                    modifier = Modifier.size(ScanTokens.GlassIconSize)
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Shutter(ring = ring, enabled = canShoot, onClick = onShutter)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PageStack(state.stack, onReview)
        }
    }
}

/** 80 dp: a 4 dp `onSurface` ring, an 8 dp gap and a 64 dp `primary` disc; [ring] fills it clockwise. */
@Composable
private fun Shutter(ring: () -> Float, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ringPx = with(density) { ScanTokens.ShutterRing.toPx() }
    val discInset = with(density) { ScanTokens.ShutterDiscInset.toPx() }
    val label = stringResource(R.string.scan_take_photo)
    // Light theme: a soft ring in the app tint instead of a heavy dark one; the fill arc stays primary.
    val ringColour = if (isLightScan()) colors.primaryContainer else colors.onSurface
    val press = remember { MutableInteractionSource() }
    Canvas(
        modifier = Modifier
            .size(ScanTokens.ShutterSize)
            // The most-pressed control on the screen gives way under the finger, not just a ripple.
            .pressScale(press)
            .clip(CircleShape)
            .clickable(
                interactionSource = press,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = label }
            .testTag(ScanTags.CAMERA_SHUTTER)
    ) {
        val half = ringPx / 2f
        val arcSize = Size(size.width - ringPx, size.height - ringPx)
        drawCircle(ringColour, radius = size.minDimension / 2f - half, style = Stroke(ringPx))
        val filled = ring()
        if (filled > 0f) {
            drawArc(
                color = colors.primary,
                startAngle = TWELVE_O_CLOCK,
                sweepAngle = FULL_CIRCLE * filled.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(half, half),
                size = arcSize,
                style = Stroke(ringPx)
            )
        }
        drawCircle(colors.primary, radius = size.minDimension / 2f - discInset)
    }
}

/** The last page's thumbnail with a count badge; an empty slot of the same size before the first page. */
@Composable
private fun PageStack(pages: List<ScanPage>, onReview: () -> Unit) {
    val last = pages.lastOrNull()
    if (last == null) {
        Box(Modifier.size(ScanTokens.MediumControlSize))
        return
    }
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val label = pluralStringResource(R.plurals.scan_pages_review, pages.size, pages.size)
    Box(
        modifier = Modifier
            .size(ScanTokens.MediumControlSize)
            .clickable(role = Role.Button, onClick = onReview)
            .semantics { contentDescription = label }
            .testTag(ScanTags.CAMERA_PAGES)
    ) {
        Image(
            bitmap = last.thumbnail.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(ScanTokens.Stroke, if (isLightScan()) colors.outlineVariant else colors.onSurface, shape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .sizeIn(minWidth = ScanTokens.BadgeSize, minHeight = ScanTokens.BadgeSize)
                .background(colors.primary, CircleShape)
                .testTag(ScanTags.CAMERA_PAGES_BADGE),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = pages.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimary
            )
        }
    }
}

/** Gallery glass button: fill and rim strength over the content colour. */
private const val GLASS_FILL_ALPHA = 0.10f
private const val GLASS_RIM_ALPHA = 0.18f
