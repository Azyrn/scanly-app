package com.skeler.scanely.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** Camera JPEGs usually store orientation as metadata; re-encoding must bake it into pixels. */
internal fun ContentResolver.orientBitmap(uri: Uri, bitmap: Bitmap): Bitmap {
    val matrix = orientationMatrix(orientationOf(uri)) ?: return bitmap
    val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (upright !== bitmap) bitmap.recycle()
    return upright
}

/** Missing or invalid EXIF metadata leaves the decoded pixels unchanged. */
private fun ContentResolver.orientationOf(uri: Uri): Int? = runCatching {
    openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
}.getOrNull()

private fun orientationMatrix(orientation: Int?): Matrix? {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(HALF_TURN_DEGREES)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(QUARTER_TURN_DEGREES)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-QUARTER_TURN_DEGREES)
        else -> return null
    }
    return matrix
}

private const val QUARTER_TURN_DEGREES = 90f
private const val HALF_TURN_DEGREES = 180f
