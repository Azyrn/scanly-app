package com.skeler.scanely.core.transcribe

import android.content.Context
import android.util.Log
import com.skeler.scanely.core.network.StoreJson
import com.skeler.scanely.core.network.TranscriptDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcripts the user has made, with the audio they were made from, in app-private storage.
 *
 * Kept on the device because a transcript is paid for: losing one to process death or a closed
 * screen would be losing credits. Each lives in its own directory — `transcript.json`, a small
 * `summary.json` for the list, and the audio for playback — so the list never parses a three-hour
 * transcript just to show its title.
 */
@Singleton
class TranscriptStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val root = File(context.filesDir, "transcripts")
    private val json = StoreJson
    private val lock = Mutex()

    private val _summaries = MutableStateFlow<List<TranscriptSummary>>(emptyList())
    val summaries: StateFlow<List<TranscriptSummary>> = _summaries.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _summaries.value = lock.withLock { readSummaries() }
    }

    /**
     * Saves a new transcript. [audio] is moved, not copied — it is a cache file the pipeline made
     * for the upload, and a second copy of a long recording is tens of megabytes for nothing.
     */
    internal suspend fun save(title: String, dto: TranscriptDto, audio: File?): Transcript =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val id = UUID.randomUUID().toString()
                val dir = File(root, id).apply { mkdirs() }
                val audioName = audio?.takeIf { it.exists() }?.let { keepAudio(it, dir) }
                val stored = StoredTranscript(
                    id = id,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    transcript = dto,
                    audioFile = audioName
                )
                write(dir, stored)
                prune()
                _summaries.value = readSummaries()
                stored.toTranscript()
            }
        }

    /** Moves the upload beside the transcript; a copy when the two are on different volumes. */
    private fun keepAudio(source: File, dir: File): String? {
        val target = File(dir, "audio.${source.extension.ifBlank { "m4a" }}")
        if (source.renameTo(target)) return target.name
        return runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            target.name
        }.getOrNull()
    }

    suspend fun load(id: String): Transcript? = withContext(Dispatchers.IO) {
        lock.withLock { read(id)?.toTranscript() }
    }

    /** The recording behind a transcript, or null if it was never kept. */
    suspend fun audioFor(id: String): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            read(id)?.audioFile?.let { File(dirOf(id) ?: return@withLock null, it) }?.takeIf(File::exists)
        }
    }

    suspend fun rename(id: String, title: String) = update(id) { it.copy(title = title) }

    suspend fun renameSpeaker(id: String, speaker: Int, name: String) = update(id) { stored ->
        val names = stored.speakerNames.toMutableMap()
        if (name.isBlank()) names.remove(speaker) else names[speaker] = name.trim()
        stored.copy(speakerNames = names)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            dirOf(id)?.deleteRecursively()
            _summaries.value = readSummaries()
        }
    }

    private suspend fun update(id: String, change: (StoredTranscript) -> StoredTranscript): Transcript? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val stored = read(id) ?: return@withLock null
                val updated = change(stored)
                write(dirOf(id) ?: return@withLock null, updated)
                _summaries.value = readSummaries()
                updated.toTranscript()
            }
        }

    /** Ids are UUIDs we minted; anything else must not name a path. */
    private fun dirOf(id: String): File? =
        runCatching { UUID.fromString(id) }.getOrNull()?.let { File(root, it.toString()) }

    private fun read(id: String): StoredTranscript? {
        val file = File(dirOf(id) ?: return null, TRANSCRIPT_FILE)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(StoredTranscript.serializer(), file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable transcript $id", e)
            null
        }
    }

    private fun write(dir: File, stored: StoredTranscript) {
        // Written beside and renamed over, so a crash mid-write cannot corrupt a paid transcript.
        val transcript = File(dir, "$TRANSCRIPT_FILE.tmp")
        transcript.writeText(json.encodeToString(StoredTranscript.serializer(), stored))
        transcript.renameTo(File(dir, TRANSCRIPT_FILE))
        val summary = stored.toTranscript().let { t ->
            TranscriptSummary(
                id = t.id,
                title = t.title,
                createdAt = t.createdAt,
                durationMs = t.durationMs,
                speakerCount = t.speakerCount,
                preview = t.segments.firstOrNull { it.text.isNotBlank() }?.text?.take(PREVIEW_CHARS).orEmpty()
            )
        }
        File(dir, SUMMARY_FILE).writeText(json.encodeToString(TranscriptSummary.serializer(), summary))
    }

    private fun readSummaries(): List<TranscriptSummary> =
        (root.listFiles() ?: emptyArray())
            .mapNotNull { dir ->
                val file = File(dir, SUMMARY_FILE)
                if (!file.exists()) return@mapNotNull null
                runCatching { json.decodeFromString(TranscriptSummary.serializer(), file.readText()) }
                    .getOrNull()
            }
            .sortedByDescending { it.createdAt }

    /** Oldest first beyond the cap. Audio is what fills storage, so the cap is on count *and* bytes. */
    private fun prune() {
        val dirs = (root.listFiles() ?: return).sortedByDescending { it.lastModified() }
        var bytes = 0L
        dirs.forEachIndexed { index, dir ->
            bytes += dir.walkBottomUp().filter(File::isFile).sumOf(File::length)
            if (index >= MAX_TRANSCRIPTS || bytes > MAX_BYTES && index > 0) dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "TranscriptStore"
        const val TRANSCRIPT_FILE = "transcript.json"
        const val SUMMARY_FILE = "summary.json"
        const val PREVIEW_CHARS = 160
        const val MAX_TRANSCRIPTS = 100
        const val MAX_BYTES = 1_000L * 1024 * 1024
    }
}
