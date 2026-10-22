package com.skeler.scanely.ui.viewmodel

import android.content.Context
import com.skeler.scanely.core.network.StoreJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class SavedDesignVersion(val uri: String, val id: String, val pages: Int, val billed: Int)

@Serializable
internal data class SavedDesignRequest(
    val prompt: String,
    val source: String?,
    val prior: String?,
    val photo: String?,
    val document: String?,
    val template: String? = null,
    val ocrReceipt: String? = null
)

@Serializable
internal data class DesignWorkspace(
    val prompt: String = "",
    val templateId: String? = null,
    val revisionPrompt: String = "",
    val sourceId: String? = null,
    val sourceLabel: String? = null,
    val sourceText: String? = null,
    val photo: String? = null,
    val document: String? = null,
    val documentName: String? = null,
    val versions: List<SavedDesignVersion> = emptyList(),
    val versionIndex: Int = -1,
    val previewPage: Int = 0,
    val pending: SavedDesignRequest? = null,
    val pendingKey: String? = null,
    val pendingAt: Long = 0,
    /** The picked file's text as read and corrected, and the receipt that bills its OCR. */
    val documentText: String? = null,
    val documentReceipt: String? = null
)

/**
 * The old designer's single saved workspace (3.5 and earlier). Only read now, once, by the chat's
 * LegacyDesignImport, which turns its versions into a chat and then clears it.
 */
@Singleton
class DesignWorkspaceStore internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "design-workspace"))
    private val mutex = Mutex()
    private val json = StoreJson

    internal suspend fun read(): DesignWorkspace? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(directory, "current.json")
            if (!file.exists()) null else json.decodeFromString<DesignWorkspace>(file.readText())
        }
    }

    internal suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { directory.deleteRecursively() }
    }
}
