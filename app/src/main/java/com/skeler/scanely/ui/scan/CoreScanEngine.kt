package com.skeler.scanely.ui.scan

import android.graphics.Bitmap
import com.skeler.scanely.core.geometry.Quad
import com.skeler.scanely.core.scan.EdgeDetector
import com.skeler.scanely.core.scan.LumaFrame
import com.skeler.scanely.core.scan.PageWarp
import com.skeler.scanely.core.scan.QuadDetection

/** The core scan code (core/scan) in the scan screens' terms: outlines as [NormQuad] on the photo. */
object CoreScanEngine {

    /** The page on a decoded photo, or null when there is none. */
    fun detectStill(photo: Bitmap): NormQuad? {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val found = EdgeDetector.detect(LumaFrame.fromArgb(pixels, photo.width, photo.height)) ?: return null
        return found.toNorm(photo.width, photo.height)
    }

    /** The page inside [quad], flattened; [aspect] (landscape width / height) forces its shape, as for an ID. */
    fun warp(photo: Bitmap, quad: NormQuad, aspect: Float?): Bitmap =
        PageWarp.warp(photo, quad.toQuad(photo.width, photo.height), aspect)
}

/** A detection in frame pixels as a [NormQuad] on that frame; null if its corners are not a convex quad. */
fun QuadDetection.toNorm(width: Int, height: Int): NormQuad? =
    NormQuad.of((0 until CORNERS).map { NormPoint(quad.x(it) / width, quad.y(it) / height) })

fun NormQuad.toQuad(width: Int, height: Int): Quad =
    Quad(corners.flatMap { listOf(it.x * width, it.y * height) }.toFloatArray())

private const val CORNERS = 4
