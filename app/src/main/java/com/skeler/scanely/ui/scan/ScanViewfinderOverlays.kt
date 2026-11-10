@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.scan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.skeler.scanely.R
import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.ui.theme.ScanTokens
import kotlin.math.max

/** The live page outline on the preview (spec 3.2): placed as PreviewView FILL_CENTER places the frame. */
@Composable
fun LiveQuadOverlay(view: LiveView, size: IntSize) {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Offset>()
    val place = placeImage(view.frameAspect, size.width.toFloat(), size.height.toFloat(), fill = true)
    val quad = view.quad
    val targets = (quad ?: NormQuad.FULL).corners.map { place.toView(it).let { (x, y) -> Offset(x, y) } }
    // The animations are read while drawing, not here: the outline glides every frame the page moves,
    // and reading them in composition recomposed the overlay on each of those frames.
    val corners = targets.mapIndexed { i, target -> animateOffsetAsState(target, spec, label = "corner$i") }
    val shown = animateFloatAsState(
        if (quad != null && view.frameAspect > 0f) 1f else 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "quadShown"
    )
    val primary = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { ScanTokens.Stroke.toPx() }
    Canvas(Modifier.fillMaxSize().testTag(ScanTags.CAMERA_QUAD)) {
        val alpha = shown.value
        if (alpha <= 0f) return@Canvas
        val points = corners.map { it.value }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, primary.copy(alpha = ScanTokens.QUAD_FILL_ALPHA * alpha))
        drawPath(path, primary.copy(alpha = alpha), style = Stroke(stroke))
    }
}

/** The ID-1 / ID-3 frame, centred, with the outside dimmed (spec 3.2). */
@Composable
fun IdFrameOverlay(kind: IdDocumentKind, viewfinderWidth: Dp) {
    val frameWidth = viewfinderWidth - ScanTokens.IdFrameInset * 2
    val frameHeight = frameWidth * (kind.heightMm / kind.widthMm)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val stroke = with(density) { ScanTokens.Stroke.toPx() }
    val frameW = with(density) { frameWidth.toPx() }
    val frameH = with(density) { frameHeight.toPx() }
    val radius = frameW * ScanTokens.ID_FRAME_CORNER_RATIO
    Canvas(Modifier.fillMaxSize()) {
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        val hole = Path().apply {
            addRoundRect(RoundRect(left, top, left + frameW, top + frameH, CornerRadius(radius)))
        }
        clipPath(hole, ClipOp.Difference) { drawRect(colors.scrim.copy(alpha = ScanTokens.SCRIM_ALPHA)) }
    }
    Box(
        Modifier
            .size(frameWidth, frameHeight)
            .testTag(ScanTags.CAMERA_ID_FRAME)
            .drawBehind {
                val inset = stroke / 2f
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(max(0f, radius - inset)),
                    style = Stroke(stroke)
                )
            }
    )
}

/** The guidance pill near the bottom of the viewfinder (spec 3.2). */
@Composable
fun GuidancePill(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = modifier
            .heightIn(min = ScanTokens.PillHeight)
            .testTag(ScanTags.CAMERA_GUIDANCE)
    ) {
        Box(Modifier.padding(horizontal = ScanTokens.Space4, vertical = ScanTokens.Space2)) {
            Text(text = text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Card / Passport (spec 3.2): two equal segments, each the widest label plus 16 on both sides. */
@Composable
fun KindToggle(kind: IdDocumentKind, onKind: (IdDocumentKind) -> Unit, modifier: Modifier = Modifier) {
    val labels = IdDocumentKind.entries.map { stringResource(it.toggleLabel()) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Whole pixels: the row splits its width between the segments by weight after overlapping their
    // 1 dp outlines, so a width in fractional pixels left one segment 2 px narrower than the other.
    val (segment, row) = remember(labels, style, density) {
        with(density) {
            val widest = labels.maxOf { measurer.measure(it, style).size.width }
            val segmentPx = widest + (ScanTokens.Space4 * 2).roundToPx()
            val rowPx = segmentPx * IdDocumentKind.entries.size - ScanTokens.HairStroke.roundToPx()
            segmentPx.toDp() to rowPx.toDp()
        }
    }
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .width(row)
            .testTag(ScanTags.CAMERA_KIND)
    ) {
        IdDocumentKind.entries.forEachIndexed { i, entry ->
            SegmentedButton(
                selected = entry == kind,
                onClick = { onKind(entry) },
                shape = SegmentedButtonDefaults.itemShape(i, IdDocumentKind.entries.size),
                modifier = Modifier
                    .width(segment)
                    .testTag(entry.toggleTag()),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer,
                    activeContentColor = colors.onSecondaryContainer,
                    activeBorderColor = colors.outline,
                    inactiveBorderColor = colors.outline
                ),
                contentPadding = PaddingValues(horizontal = ScanTokens.Space4),
                icon = {},
                label = { Text(labels[i], style = style, maxLines = 1) }
            )
        }
    }
}

private fun IdDocumentKind.toggleTag(): String = when (this) {
    IdDocumentKind.ID_CARD -> ScanTags.CAMERA_KIND_CARD
    IdDocumentKind.PASSPORT -> ScanTags.CAMERA_KIND_PASSPORT
}

private fun IdDocumentKind.toggleLabel(): Int = when (this) {
    IdDocumentKind.ID_CARD -> R.string.scan_kind_card
    IdDocumentKind.PASSPORT -> R.string.scan_kind_passport
}
