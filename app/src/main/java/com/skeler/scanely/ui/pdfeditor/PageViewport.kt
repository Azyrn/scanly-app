package com.skeler.scanely.ui.pdfeditor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.skeler.scanely.ui.theme.GestureDefaults
import com.skeler.scanely.ui.theme.RubberBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Zoom and pan of one page in the editor, driven by two fingers.
 *
 * While the fingers are down the page is the fingers: the point under the pinch stays under it, and
 * past 1×–[maxZoom] or past the page's edges the page rubber-bands instead of stopping. When they lift,
 * zoom springs back into range and pan carries on with the fingers' speed (Android's fling curve), its
 * end clamped to the page, finishing on a spring. A new pinch catches both where they are.
 *
 * [zoom] and [pan] are animation state: read them in a draw or pointer lambda, never in composition,
 * so a pinch frame redraws the page without recomposing it.
 */
@Stable
class PageViewport(
    private val scope: CoroutineScope,
    private val decay: DecayAnimationSpec<Float>,
    private val maxZoom: Float
) {
    private val zoomState = Animatable(1f)
    private val panState = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val tracker = VelocityTracker()
    private var rawZoom = 1f
    private var rawPan = Offset.Zero

    val zoom: Float get() = zoomState.value
    val pan: Offset get() = panState.value

    /** [base] with the current zoom and pan applied. */
    fun projection(base: PageProjection): PageProjection = base.copy(zoom = zoom, pan = pan)

    /** Two fingers are down: stop any settle and resume from where the page shows now. */
    fun start(base: PageProjection) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            zoomState.stop()
            panState.stop()
        }
        rawZoom = RubberBand.unbanded(zoom, 1f, maxZoom, GestureDefaults.ZOOM_BAND)
        val limit = base.copy(zoom = zoom).panLimit()
        rawPan = Offset(
            RubberBand.unbanded(pan.x, -limit.x, limit.x, base.containerSize.width),
            RubberBand.unbanded(pan.y, -limit.y, limit.y, base.containerSize.height)
        )
        tracker.resetTracking()
    }

    /** One pointer event of the pinch: [centroid] in canvas px, [panChange] in px, [zoomChange] a ratio. */
    fun transform(base: PageProjection, centroid: Offset, panChange: Offset, zoomChange: Float, timeMillis: Long) {
        tracker.addPosition(timeMillis, centroid)
        val before = zoom
        rawZoom *= zoomChange
        val shownZoom = RubberBand.banded(rawZoom, 1f, maxZoom, GestureDefaults.ZOOM_BAND)
        // Zoom about the fingers: the page point under the centroid moves with it, as the finger would.
        val anchor = centroid - base.containerSize.center - pan
        rawPan += anchor * (1f - shownZoom / before) + panChange
        val shownPan = banded(base, shownZoom, rawPan)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            zoomState.snapTo(shownZoom)
            panState.snapTo(shownPan)
        }
    }

    /** The fingers lifted: settle into range from their speed. */
    fun release(base: PageProjection, reducedMotion: Boolean) {
        val velocity = tracker.calculateVelocity().let { Offset(it.x, it.y) }
        val start = if (reducedMotion) Offset.Zero else velocity
        val targetZoom = zoom.coerceIn(1f, maxZoom)
        val limit = base.copy(zoom = targetZoom).panLimit()
        val projected = Offset(decay.calculateTargetValue(pan.x, start.x), decay.calculateTargetValue(pan.y, start.y))
        val targetPan = Offset(projected.x.coerceIn(-limit.x, limit.x), projected.y.coerceIn(-limit.y, limit.y))
        if (targetZoom != zoom) {
            scope.launch { zoomState.animateTo(targetZoom, GestureDefaults.snapBack(reducedMotion)) }
        }
        if (targetPan != pan) {
            scope.launch {
                panState.animateTo(
                    targetPan,
                    GestureDefaults.release(reducedMotion, Offset.VisibilityThreshold),
                    initialVelocity = start
                )
            }
        }
    }

    private fun banded(base: PageProjection, zoom: Float, raw: Offset): Offset {
        val limit = base.copy(zoom = zoom).panLimit()
        return Offset(
            RubberBand.banded(raw.x, -limit.x, limit.x, base.containerSize.width),
            RubberBand.banded(raw.y, -limit.y, limit.y, base.containerSize.height)
        )
    }
}
