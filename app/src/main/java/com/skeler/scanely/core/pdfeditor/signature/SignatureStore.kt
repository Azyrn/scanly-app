package com.skeler.scanely.core.pdfeditor.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SignatureStore"

data class StoredSignature(
    val id: String,
    val label: String,
    val createdAt: Long,
    /** Page-point aspect ratio, so a placement box can be sized before the bitmap is decoded. */
    val aspectRatio: Float
)

/**
 * A reusable library of extracted signatures.
 *
 * Capturing one is the expensive part of signing — camera, crop, review — so keeping the result
 * makes every later signature pick-and-place. A first-class feature, not a cache.
 *
 * Internal storage: a signature is as sensitive as a physical one and must not be visible to other
 * apps or land in a media scan.
 */
@Singleton
class SignatureStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val directory: File by lazy {
        File(context.filesDir, "signatures").apply { mkdirs() }
    }

    private val indexFile: File get() = File(directory, "index.json")

    private val _signatures = MutableStateFlow<List<StoredSignature>>(emptyList())
    val signatures: StateFlow<List<StoredSignature>> = _signatures.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _signatures.value = readIndex()
    }

    suspend fun save(bitmap: Bitmap, label: String): StoredSignature? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.png")

        val written = runCatching {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess

        if (!written) {
            Log.w(TAG, "Could not write signature $id")
            return@withContext null
        }

        val entry = StoredSignature(
            id = id,
            label = label.ifBlank { "Signature" },
            createdAt = System.currentTimeMillis(),
            aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        )
        writeIndex(readIndex() + entry)
        _signatures.value = readIndex()
        entry
    }

    suspend fun bitmapFor(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(directory, "$id.png")
        if (!file.exists()) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.png").delete()
        writeIndex(readIndex().filterNot { it.id == id })
        _signatures.value = readIndex()
    }

    private fun readIndex(): List<StoredSignature> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                StoredSignature(
                    id = item.getString("id"),
                    label = item.optString("label", "Signature"),
                    createdAt = item.optLong("createdAt"),
                    aspectRatio = item.optDouble("aspectRatio", 3.0).toFloat()
                )
            }
        }.getOrElse {
            Log.w(TAG, "Signature index unreadable; starting empty", it)
            emptyList()
        }.filter { File(directory, "${it.id}.png").exists() }
    }

    private fun writeIndex(entries: List<StoredSignature>) {
        runCatching {
            val array = JSONArray()
            entries.sortedByDescending { it.createdAt }.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("label", entry.label)
                        .put("createdAt", entry.createdAt)
                        .put("aspectRatio", entry.aspectRatio.toDouble())
                )
            }
            indexFile.writeText(array.toString())
        }.onFailure { Log.w(TAG, "Could not write signature index", it) }
    }
}
