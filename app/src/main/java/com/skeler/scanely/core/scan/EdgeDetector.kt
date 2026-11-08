package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad

/** A document outline in frame pixels (TL, TR, BR, BL) and how sure the detector is, 0..1. */
data class QuadDetection(val quad: Quad, val confidence: Float)

/**
 * Finds the document in a luma frame, all on device.
 *
 * Serves the live preview (a 320-480 px Y plane, every frame) and the full-resolution still alike:
 * the frame is box-averaged down to a ~[WORK_LONG_SIDE] px work image where edges, lines and quad
 * candidates are found, then the winning quad is sharpened on a finer level (up to
 * [REFINE_LONG_SIDE] px) before it is scaled back to frame pixels.
 */
object EdgeDetector {

    /**
     * Long side of the image the search runs on. Preview frames (<= 640 px) run at their own size;
     * a still is box-averaged to 667-800 px: coarser than that, a thin dark band on a document's
     * edge (a screen's title bar against its bezel) merges into the strong edge next to it.
     */
    const val WORK_LONG_SIDE = 800

    /** Long side of the image the corners are sharpened on. */
    const val REFINE_LONG_SIDE = 1600

    /** Below this the best quad is not trusted at all. */
    const val MIN_CONFIDENCE = 0.35f

    /** Strongest lines considered: four sides plus room for clutter, text blocks and frames. */
    private const val MAX_LINES = 28

    /**
     * Candidate lines for a live frame. The quad search grows with the 4-line combinations (28 lines:
     * 20 475; 16: 1 820); the extra candidates matter for stills (frame lips, title bars), not the overlay.
     */
    private const val LIVE_MAX_LINES = 16

    /** A line needs this share of the work image's short side in edge pixels to be a candidate side. */
    private const val MIN_VOTE_FRACTION = 0.06f

    /** Search radius when sharpening, in work pixels (the coarse lines are good to about one). */
    private const val REFINE_RADIUS_WORK_PX = 2

    /**
     * Work size for live preview frames. The live outline only draws the overlay and feeds auto-capture;
     * the crop screen detects again on the full still. At [WORK_LONG_SIDE] a 640 x 480 analysis frame was
     * processed at full size: 155 ms per frame on the phone (6.5 fps).
     */
    const val LIVE_WORK_LONG_SIDE = 320

    /**
     * A camera preview frame: the same search on a small work image (see [LIVE_WORK_LONG_SIDE]), without
     * sub-pixel sharpening — that ran on the full frame and is only needed for the final crop.
     */
    fun detectLive(frame: LumaFrame): QuadDetection? =
        detect(frame, LIVE_WORK_LONG_SIDE, LIVE_MAX_LINES, sharpen = false)

    fun detect(frame: LumaFrame): QuadDetection? = detect(frame, WORK_LONG_SIDE, MAX_LINES, sharpen = true)

    private fun detect(frame: LumaFrame, workLongSide: Int, maxLines: Int, sharpen: Boolean): QuadDetection? {
        val long = maxOf(frame.width, frame.height)
        val workFactor = ceilDiv(long, workLongSide)
        val work = frame.downscaled(workFactor)
        val edges = EdgeMap.of(work)
        val minVotes = (MIN_VOTE_FRACTION * minOf(work.width, work.height)).toInt()
        val lines = HoughLines.find(edges, maxLines, minVotes)
        val best = QuadSearch.best(edges, lines)?.takeIf { it.confidence >= MIN_CONFIDENCE } ?: return null
        val workScale = workFactor.toFloat()
        if (!sharpen) return QuadDetection(best.quad.scaled(workScale, workScale), best.confidence)

        val fineFactor = minOf(workFactor, ceilDiv(long, REFINE_LONG_SIDE))
        val fine = if (fineFactor == workFactor) work else frame.downscaled(fineFactor)
        val ratio = workFactor.toFloat() / fineFactor
        val radius = ((REFINE_RADIUS_WORK_PX + 1) * ratio).toInt()
        val refined = QuadRefiner.refine(fine, best.quad.scaled(ratio, ratio), radius, best.insideBrighter, ratio)
        return QuadDetection(refined.scaled(fineFactor.toFloat(), fineFactor.toFloat()), best.confidence)
    }

    private fun ceilDiv(a: Int, b: Int) = maxOf(1, (a + b - 1) / b)
}
