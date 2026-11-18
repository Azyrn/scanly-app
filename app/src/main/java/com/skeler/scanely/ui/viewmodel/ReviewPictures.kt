package com.skeler.scanely.ui.viewmodel

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import com.skeler.scanely.core.image.DocumentFilters
import com.skeler.scanely.core.image.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ReviewPictures"
private const val PREVIEW_DIMENSION = 220
private const val QUARTER_TURN_DEGREES = 90f

/**
 * Review's display pictures: each page decoded no larger than the screen ([screenLong] x [screenShort] px, in
 * either orientation), which is all a page filling the screen can show.
 */
internal class BitmapPageOps(
    private val resolver: ContentResolver,
    private val screenLong: Int,
    private val screenShort: Int
) : PageOps<Bitmap> {

    override fun decode(uri: Uri): Bitmap? = runCatching {
        val page = decodeScaled(resolver, uri)
        fitWithin(page, screenLong, screenShort).also { if (it !== page) page.recycle() }
    }.onFailure { Log.e(TAG, "Couldn't read a page for display", it) }.getOrNull()

    override fun rotateQuarter(picture: Bitmap): Bitmap = rotate(picture, QUARTER_TURN_DEGREES)

    override fun filter(picture: Bitmap, filter: ScanFilter): Bitmap = DocumentFilters.apply(picture, filter)

    override fun thumbnail(picture: Bitmap): Bitmap = scaleToThumbnail(picture)

    override fun recycle(picture: Bitmap) = picture.recycle()

    override suspend fun <T> background(block: () -> T): T = withContext(Dispatchers.Default) { block() }
}

/**
 * [page] at export quality: decoded at most 2200 px on its longest side, then turned and filtered in the order
 * the user edited it ([renderPage]); null when its file can't be read. The caller recycles the result.
 */
internal fun renderForExport(resolver: ContentResolver, page: ReviewPage, filter: ScanFilter): Bitmap? {
    val source = runCatching { decodeScaled(resolver, page.uri) }
        .onFailure { Log.e(TAG, "Couldn't read a page for export", it) }
        .getOrNull() ?: return null
    return try {
        renderPage(
            source,
            page.turns,
            rotateQuarter = { rotate(it, QUARTER_TURN_DEGREES) },
            filter = { DocumentFilters.apply(it, filter) },
            recycle = Bitmap::recycle
        )
    } finally {
        source.recycle()
    }
}

internal fun rotate(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun scaleToThumbnail(source: Bitmap): Bitmap {
    val longest = max(source.width, source.height).coerceAtLeast(1)
    val scale = PREVIEW_DIMENSION.toFloat() / longest
    if (scale >= 1f) return source
    return source.scale(
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1)
    )
}

/** [bitmap] scaled down, never up, so its long side fits [long] and its short side fits [short]. */
private fun fitWithin(bitmap: Bitmap, long: Int, short: Int): Bitmap {
    val scale = min(
        long.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1),
        short.toFloat() / min(bitmap.width, bitmap.height).coerceAtLeast(1)
    )
    if (scale >= 1f) return bitmap
    return bitmap.scale(
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1)
    )
}
