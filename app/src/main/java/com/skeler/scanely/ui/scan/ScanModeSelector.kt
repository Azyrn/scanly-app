@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.skeler.scanely.R
import com.skeler.scanely.core.scan.ScanMode
import com.skeler.scanely.ui.theme.ScanTokens
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@StringRes
fun ScanMode.labelRes(): Int = when (this) {
    ScanMode.DOCUMENT -> R.string.scan_mode_document
    ScanMode.ID_CARD -> R.string.scan_mode_id
    ScanMode.WHITEBOARD -> R.string.scan_mode_whiteboard
}

/**
 * The mode row (spec 3.3): Document, ID card, Whiteboard from start; the selected one sits at the centre of
 * the screen and the row slides with the default spatial spring. Placed with placeRelative, so it mirrors in RTL.
 */
@Composable
fun ScanModeSelector(selected: ScanMode, onSelect: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    val position = remember { Animatable(selected.ordinal.toFloat()) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(selected) { position.animateTo(selected.ordinal.toFloat(), spatial) }
    val gap = ScanTokens.Space2
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = with(LocalDensity.current) { ScanTokens.ModeSwipeDistance.toPx() }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(ScanTokens.TouchTarget)
            .selectableGroup()
            .testTag(ScanTags.CAMERA_MODES)
            .pointerInput(selected, rtl) {
                // Same rule as the viewfinder: a horizontal swipe of at least 48 dp steps one mode.
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = {
                        if (abs(travel) >= swipe) {
                            val forward = (travel < 0) != rtl
                            val next = selected.ordinal + if (forward) 1 else -1
                            ScanMode.entries.getOrNull(next)?.let(onSelect)
                        }
                    }
                ) { _, dx -> travel += dx }
            },
        content = { ScanMode.entries.forEach { ModeItem(it, it == selected) { onSelect(it) } } }
    ) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val gapPx = gap.roundToPx()
        val starts = items.runningFold(0) { x, item -> x + item.width + gapPx }
        val centres = items.indices.map { starts[it] + items[it].width / 2f }
        val width = constraints.maxWidth
        layout(width, constraints.maxHeight) {
            val shift = width / 2f - centreAt(centres, position.value)
            items.forEachIndexed { i, item -> item.placeRelative((starts[i] + shift).roundToInt(), 0) }
        }
    }
}

/** The centre the row brings to the middle for a fractional (springing) [position]; overshoot extrapolates. */
private fun centreAt(centres: List<Float>, position: Float): Float {
    val i = floor(position).toInt().coerceIn(0, centres.size - 2)
    return centres[i] + (centres[i + 1] - centres[i]) * (position - i)
}

@Composable
private fun ModeItem(mode: ScanMode, selected: Boolean, onClick: () -> Unit) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
        effects,
        label = "modeContainer"
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        effects,
        label = "modeContent"
    )
    // The whole 48 dp slot takes the tap, but the ripple is drawn on the pill only, so the press
    // feedback has the same shape as the selected highlight instead of a grey rectangle.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(ScanTokens.TouchTarget)
            .widthIn(min = ScanTokens.TouchTarget)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .testTag(ScanTags.mode(mode)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(ScanTokens.PillHeight)
                .clip(CircleShape)
                .background(container)
                .indication(interaction, ripple())
                .padding(horizontal = ScanTokens.Space4),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(mode.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
