package com.skeler.scanely.core.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriNames {

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
