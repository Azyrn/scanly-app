package com.skeler.scanely.core.transcribe

import com.skeler.scanely.core.network.TranscriptDto
import kotlinx.serialization.Serializable

/**
 * What the user chose for one transcription. [language] null is auto-detect, which costs a second
 * provider pass when [formatNumbers] is on — the server refuses formatting without a language.
 */
data class TranscribeOptions(
    val language: String? = null,
    val formatNumbers: Boolean = true,
    val speakerLabels: Boolean = true,
    val keepFillerWords: Boolean = false,
    val keyTerms: List<String> = emptyList()
) {
    /** Provider passes this request will be billed for. Mirrors `routes/transcribe.ts`. */
    val passes: Int get() = if (language == null && formatNumbers) 2 else 1

    companion object {
        /** xAI's limits on key-term biasing, enforced server-side too. */
        const val MAX_KEY_TERMS = 100
        const val MAX_KEY_TERM_CHARS = 50
    }
}

/** A word's place in time and in its segment's text: `[start, end)` ms and `[from, to)` chars. */
data class TranscriptWord(val startMs: Long, val endMs: Long, val from: Int, val to: Int)

data class TranscriptSegment(
    val speaker: Int?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TranscriptWord>
)

data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val language: String,
    val durationMs: Long,
    val formatted: Boolean,
    val speakerCount: Int,
    val creditsUsed: Int,
    val segments: List<TranscriptSegment>,
    /** User-chosen names, keyed by the diarized speaker index. */
    val speakerNames: Map<Int, String> = emptyMap()
) {
    fun speakerName(speaker: Int?): String? =
        speaker?.let { speakerNames[it]?.takeIf(String::isNotBlank) ?: "Speaker ${it + 1}" }

    val isEmpty: Boolean get() = segments.all { it.text.isBlank() }
}

/**
 * Turns the server's compact word triples into ranges the UI can highlight and seek to.
 *
 * The server's output is still input here: every offset is clamped into its segment and every
 * range forced forward, so a malformed triple can mis-highlight a word but never throw.
 */
internal fun TranscriptDto.toSegments(): List<TranscriptSegment> = segments.map { dto ->
    val startMs = (dto.start * 1000).toLong().coerceAtLeast(0)
    val endMs = (dto.end * 1000).toLong().coerceAtLeast(startMs)
    val text = dto.text
    val triples = dto.words.filter { it.size >= 3 }
    val words = triples.mapIndexedNotNull { k, w ->
        val from = w[2].toInt().coerceIn(0, text.length)
        val nextFrom = triples.getOrNull(k + 1)?.get(2)?.toInt()?.coerceIn(from, text.length)
        val to = when {
            nextFrom == null -> text.length
            nextFrom > from && text[nextFrom - 1] == ' ' -> nextFrom - 1
            else -> nextFrom
        }
        if (to <= from) return@mapIndexedNotNull null
        val wordStart = (startMs + w[0]).coerceAtLeast(startMs)
        TranscriptWord(wordStart, (startMs + w[1]).coerceAtLeast(wordStart), from, to)
    }
    TranscriptSegment(dto.speaker, startMs, endMs, text, words)
}

/** What [TranscriptStore] keeps on disk: the server's own shape plus what the user added. */
@Serializable
internal data class StoredTranscript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val transcript: TranscriptDto,
    val speakerNames: Map<Int, String> = emptyMap(),
    /** File name of the audio beside it, for playback, or null if it could not be kept. */
    val audioFile: String? = null
) {
    fun toTranscript(): Transcript = Transcript(
        id = id,
        title = title,
        createdAt = createdAt,
        language = transcript.language,
        durationMs = (transcript.durationSeconds * 1000).toLong(),
        formatted = transcript.formatted,
        speakerCount = transcript.speakerCount,
        creditsUsed = transcript.usage.pages,
        segments = transcript.toSegments(),
        speakerNames = speakerNames
    )
}

/** The list row for a saved transcript, readable without parsing every segment. */
@Serializable
data class TranscriptSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val speakerCount: Int,
    val preview: String
)
