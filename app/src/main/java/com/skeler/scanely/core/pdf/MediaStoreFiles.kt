package com.skeler.scanely.core.pdf

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

private const val AUTHORITY_SUFFIX = ".fileprovider"
private const val ALBUM = "Scanly"
private const val JPEG_QUALITY = 95
private const val TAG = "ScanExporter"

/** Saves [bitmap] as a JPEG in Pictures/Scanly; false when it could not be written. */
internal fun saveOneToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    val resolver = context.contentResolver
    val values = galleryValues(displayName) ?: return false

    // A missing storage grant on pre-Q surfaces here as a SecurityException.
    val uri = try {
        resolver.insert(galleryCollection(), values)
    } catch (e: SecurityException) {
        Log.e(TAG, "Gallery save refused for $displayName", e)
        null
    } ?: return false
    return writeGalleryImage(resolver, uri, bitmap, values, displayName)
}

/** The MediaStore row for a new gallery JPEG; null when the pre-Q album folder can't be made. */
private fun galleryValues(displayName: String): ContentValues? = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}/$ALBUM"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    } else {
        // Pre-Q has no RELATIVE_PATH; the album folder is set through the file path.
        @Suppress("DEPRECATION")
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        if (!album.exists() && !album.mkdirs()) return null
        @Suppress("DEPRECATION")
        put(MediaStore.Images.Media.DATA, File(album, "$displayName.jpg").absolutePath)
    }
}

private fun galleryCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
} else {
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

private fun writeGalleryImage(
    resolver: ContentResolver,
    uri: Uri,
    bitmap: Bitmap,
    values: ContentValues,
    displayName: String
): Boolean = try {
    val written = resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    }
    if (written != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    written != null
} catch (e: Exception) {
    Log.e(TAG, "Gallery save failed for $displayName, rolling back MediaStore row", e)
    resolver.delete(uri, null, null)
    false
}

/** Writes durable scan exports to the user-visible Downloads/Scanly directory. */
internal fun exportToDownloads(
    context: Context,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit
): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    exportToMediaStoreDownloads(context.contentResolver, displayName, mimeType, write)
} else {
    exportToLegacyDownloads(context, displayName, write)
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun exportToMediaStoreDownloads(
    resolver: ContentResolver,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit
): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        values
    ) ?: throw IOException("Couldn't create $displayName in Downloads/$ALBUM")
    // The row is removed again on any failure, which still propagates.
    var written = false
    try {
        resolver.openOutputStream(uri)?.use(write)
            ?: throw IOException("Couldn't open $displayName for writing")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        written = true
    } finally {
        if (!written) resolver.delete(uri, null, null)
    }
    return uri
}

// Pre-Q public Downloads needs a WRITE_EXTERNAL_STORAGE grant we never ask for; without it fall
// back to the app's shareable cache so export still produces an openable file.
private fun exportToLegacyDownloads(context: Context, displayName: String, write: (OutputStream) -> Unit): Uri {
    val directory = if (hasLegacyWritePermission(context)) {
        @Suppress("DEPRECATION")
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ALBUM
        )
    } else {
        File(context.cacheDir, "scans")
    }
    if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("Couldn't create ${directory.absolutePath}")
    }
    val file = File(directory, displayName)
    // A partial file is removed on any failure, which still propagates.
    var written = false
    try {
        file.outputStream().use(write)
        written = true
    } finally {
        if (!written) file.delete()
    }
    return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
}

private fun hasLegacyWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
