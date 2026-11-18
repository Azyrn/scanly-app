package com.skeler.scanely.history.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HistoryManager"
private const val MAX_HISTORY_ITEMS = 50

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val imageUri: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Returns null for malformed JSON; an empty list represents a valid empty history. */
internal fun parseHistoryJson(json: String): List<HistoryItem>? {
    return try {
        val jsonArray = JSONArray(json)
        val items = mutableListOf<HistoryItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            items.add(
                HistoryItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = obj.optString("text"),
                    imageUri = obj.optString("imageUri"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }
        items
    } catch (_: JSONException) {
        null
    }
}

internal fun historyToJson(items: List<HistoryItem>): String {
    val jsonArray = JSONArray()
    items.forEach { item ->
        val obj = JSONObject().apply {
            put("id", item.id)
            put("text", item.text)
            put("imageUri", item.imageUri)
            put("timestamp", item.timestamp)
        }
        jsonArray.put(obj)
    }
    return jsonArray.toString()
}

@Singleton
class HistoryManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    // Lazy: this singleton is built during Home's first composition on the main thread, and
    // getFilesDir and mkdirs are disk calls; the first history read or write pays for them instead.
    private val historyFile by lazy { File(context.filesDir, "scan_history.json") }

    /**
     * Where older versions copied each entry's whole source (whole PDFs included), which nothing
     * ever read. No longer written; kept so deleting or clearing history reclaims those copies.
     */
    private val imagesDir by lazy { File(context.filesDir, "history_images") }

    /**
     * Every read-modify-write of [historyFile] holds this. Saves, edits and deletes arrive from
     * several view models on different threads; unguarded, two at once lost one change or wrote
     * a corrupt file, which [getHistory] then sets aside and the user sees an empty history.
     */
    private val lock = Any()

    fun saveItem(text: String, imageUri: String): HistoryItem {
        val item = HistoryItem(text = text, imageUri = imageUri)
        synchronized(lock) {
            val currentList = getHistory().toMutableList()
            currentList.add(0, item)
            if (currentList.size > MAX_HISTORY_ITEMS) {
                val removed = currentList.drop(MAX_HISTORY_ITEMS)
                removed.forEach { old -> deleteImage(old.imageUri) }
            }
            val trimmedList = currentList.take(MAX_HISTORY_ITEMS)
            saveHistory(trimmedList)
        }
        return item
    }

    fun updateItemText(id: String, text: String) {
        if (id.isBlank()) return
        synchronized(lock) {
            val currentList = getHistory().toMutableList()
            val index = currentList.indexOfFirst { it.id == id }
            if (index == -1) return
            currentList[index] = currentList[index].copy(text = text)
            saveHistory(currentList)
        }
    }

    private fun deleteImage(imageUri: String) {
        try {
            if (imageUri.startsWith("file://")) {
                val file = File(imageUri.toUri().path ?: return)
                if (file.exists() && file.parentFile == imagesDir) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort image cleanup failed for $imageUri", e)
        }
    }

    fun getHistory(): List<HistoryItem> = synchronized(lock) { readHistory() }

    private fun readHistory(): List<HistoryItem> {
        if (!historyFile.exists()) return emptyList()
        val jsonString = try {
            historyFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read history file", e)
            return emptyList()
        }
        val items = parseHistoryJson(jsonString)
        if (items == null) {
            // Preserve corrupt data instead of overwriting it with an empty history.
            Log.e(TAG, "History unreadable, preserving as scan_history.corrupt.json")
            historyFile.renameTo(File(context.filesDir, "scan_history.corrupt.json"))
            return emptyList()
        }
        return items
    }

    fun clearHistory() = synchronized(lock) {
        imagesDir.listFiles()?.forEach { it.delete() }

        if (historyFile.exists()) {
            historyFile.delete()
        }
    }

    fun deleteItem(id: String) = synchronized(lock) {
        val currentList = getHistory().toMutableList()
        val item = currentList.find { it.id == id }
        if (item != null) {
            deleteImage(item.imageUri)
            currentList.removeAll { it.id == id }
            saveHistory(currentList)
        }
    }

    private fun saveHistory(items: List<HistoryItem>) {
        // Atomic replacement preserves the existing history if writing is interrupted. A unique
        // name, so no other writer can ever be filling the same temporary file.
        val tmpFile = File.createTempFile("scan_history", ".json.tmp", context.filesDir)
        var moved = false
        try {
            tmpFile.writeText(historyToJson(items))
            moved = tmpFile.renameTo(historyFile)
            if (!moved) Log.e(TAG, "Could not move new history into place")
        } finally {
            if (!moved) tmpFile.delete()
        }
    }
}
