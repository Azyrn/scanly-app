package com.skeler.scanely.core.ocr.paddle

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/** Quarter-turn rotations of whole bitmaps, by pixel copy. */
object BitmapRotation {

    fun rotate90(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[(w - 1 - x) * h + y] = px[y * w + x]
            }
        }
        return createBitmap(h, w).apply { setPixels(out, 0, h, 0, 0, h, w) }
    }

    fun rotate180(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        px.reverse()
        return createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Never recycles its input; callers own both bitmaps. */
    fun rotate(bmp: Bitmap, degrees: Int): Bitmap = when (((degrees % 360) + 360) % 360) {
        90 -> rotate90(bmp)
        180 -> rotate180(bmp)
        270 -> rotate180(bmp).let { flipped -> rotate90(flipped).also { flipped.recycle() } }
        else -> bmp
    }
}
