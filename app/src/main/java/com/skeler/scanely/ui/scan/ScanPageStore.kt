package com.skeler.scanely.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import com.skeler.scanely.core.image.DocumentFilters
import com.skeler.scanely.core.image.ScanFilter
import com.skeler.scanely.core.scan.ScanMode
import com.skeler.scanely.ui.viewmodel.decodeScaled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

/**
 * The scan session's files in cacheDir/scans: raw camera photos (capture_*) and straightened pages (page_*).
 * Pages outlive the session until the next one starts, because review and the ID copy read them after it ends.
 */
class ScanPageStore @Inject constructor(@param:ApplicationContext private val appContext: Context) {

    private val dir get() = File(appContext.cacheDir, SCANS_DIR)

    /** A file for the camera to write the next photo to. */
    fun newCaptureFile(): File = File(dir.apply { mkdirs() }, "$CAPTURE_PREFIX${UUID.randomUUID()}.jpg")

    /** Decodes a photo upright, at most 2200 px on its longest side; null when it can't be read. */
    suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decodeScaled(appContext.contentResolver, uri) }
            .onFailure { Log.e(TAG, "Couldn't decode the photo", it) }
            .getOrNull()
    }

    /** Straightens [crop] of [photo] (the ID shape forced for ID goals) and writes it as a page. */
    suspend fun writePage(crop: CropSession, photo: Bitmap, goal: ScanGoal): ScanPage {
        val aspect = when (goal) {
            is ScanGoal.Pages -> null
            is ScanGoal.IdCopy -> ScanMode.ID_CARD.forcedAspect(goal.kind)
            is ScanGoal.ReplaceSide -> ScanMode.ID_CARD.forcedAspect(goal.side.kind)
        }
        val warped = withContext(Dispatchers.Default) {
            val straight = CoreScanEngine.warp(photo, crop.quad, aspect)
            val mode = goal.enhanceOnSave ?: return@withContext straight
            DocumentFilters.apply(straight, ScanFilter.AUTO, mode).also { straight.recycle() }
        }
        try {
            val file = File(dir.apply { mkdirs() }, "$PAGE_PREFIX${UUID.randomUUID()}.jpg")
            withContext(Dispatchers.IO) {
                file.outputStream().use {
                    if (!warped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("JPEG failed")
                }
            }
            val thumbnail = withContext(Dispatchers.Default) { thumbnailOf(warped) }
            return ScanPage(Uri.fromFile(file), crop.source, crop.quad, crop.quarterTurns, thumbnail)
        } finally {
            warped.recycle()
        }
    }

    /** Gallery photos left when the user chose Done: each goes in with what detection finds, else whole. */
    suspend fun autoCropAll(sources: List<Uri>, goal: ScanGoal): List<ScanPage> = sources.mapNotNull { uri ->
        val photo = decode(uri) ?: return@mapNotNull null
        try {
            val quad = withContext(Dispatchers.Default) { CoreScanEngine.detectStill(photo) } ?: NormQuad.FULL
            writePage(CropSession(uri, photo, 0, quad, quad), photo, goal)
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't add a picked photo", e)
            null
        } finally {
            photo.recycle()
        }
    }

    /** Deletes [uri] if it is one of this store's raw camera photos. */
    fun deleteIfCapture(uri: Uri) {
        val path = uri.path ?: return
        val file = File(path)
        if (uri.scheme == "file" && file.parentFile == dir && file.name.startsWith(CAPTURE_PREFIX)) file.delete()
    }

    /** Pages and photos of earlier sessions; ID sides among them are not kept longer than needed. */
    fun deleteAll() {
        dir.listFiles()?.filter { it.name.startsWith(PAGE_PREFIX) || it.name.startsWith(CAPTURE_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun thumbnailOf(page: Bitmap): Bitmap {
        val scale = THUMB_PX.toFloat() / max(page.width, page.height).coerceAtLeast(1)
        return page.scale(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1)
        )
    }

    private companion object {
        const val TAG = "ScanPageStore"
        const val SCANS_DIR = "scans"
        const val PAGE_PREFIX = "page_"
        const val CAPTURE_PREFIX = "capture_"
        const val JPEG_QUALITY = 95
        const val THUMB_PX = 192
    }
}
