package com.skeler.scanely.core.transcribe

/**
 * Subtitle cues cut from a transcript's words, for SRT and WebVTT.
 *
 * Cut from words rather than segments, because a segment is a paragraph — a 45-second monologue is
 * not a subtitle. A cue closes at the reading limits below, or early at a sentence end once it is
 * long enough to be worth reading on its own.
 */
internal object SubtitleCues {

    data class Cue(
        val startMs: Long,
        val endMs: Long,
        val speaker: Int?,
        val text: String
    ) {
        fun lines(t: Transcript, vtt: Boolean): String {
            val name = t.speakerName(speaker)
            val body = wrap(text)
            return when {
                name == null -> body
                vtt -> "<v ${name.replace(">", "")}>$body"
                else -> "$name: $body"
            }
        }
    }

    /** Readable-subtitle limits: two lines of 42 characters, on screen at most seven seconds. */
    private const val LINE_CHARS = 42
    private const val CUE_CHARS = LINE_CHARS * 2
    private const val CUE_MAX_MS = 7_000L
    private const val CUE_MIN_MS = 800L
    private val SENTENCE_END = Regex("[.!?。！？؟][\"'”’)\\]]*$")

    fun cut(t: Transcript): List<Cue> {
        val out = t.segments.flatMap(::segmentCues)
        // A cue never runs into the next one's time; players show overlapping cues stacked.
        return out.mapIndexed { i, cue ->
            val nextStart = out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            cue.copy(endMs = minOf(cue.endMs, nextStart).coerceAtLeast(cue.startMs + 1))
        }
    }

    /** Two balanced lines at a space, when one line would be too long. */
    private fun wrap(text: String): String {
        if (text.length <= LINE_CHARS) return text
        val middle = text.length / 2
        val left = text.lastIndexOf(' ', middle)
        val right = text.indexOf(' ', middle)
        val cut = listOf(left, right).filter { it > 0 }.minByOrNull { kotlin.math.abs(it - middle) }
            ?: return text
        return text.substring(0, cut) + "\n" + text.substring(cut + 1)
    }

    private fun segmentCues(s: TranscriptSegment): List<Cue> {
        if (s.words.isEmpty()) {
            val text = s.text.trim()
            return if (text.isEmpty()) emptyList() else listOf(Cue(s.startMs, s.endMs, s.speaker, text))
        }
        val cues = mutableListOf<Cue>()
        var first = 0
        for (k in s.words.indices) {
            val start = s.words[first]
            val word = s.words[k]
            val text = s.text.substring(start.from, word.to)
            if (closesAfter(s, start, k, text)) {
                cues += Cue(start.startMs, maxOf(word.endMs, start.startMs + CUE_MIN_MS), s.speaker, text)
                first = k + 1
            }
        }
        return cues
    }

    /** Whether the cue running from [start] should end at word [k] rather than take the next. */
    private fun closesAfter(s: TranscriptSegment, start: TranscriptWord, k: Int, text: String): Boolean {
        val next = s.words.getOrNull(k + 1) ?: return true
        val tooLong = s.text.substring(start.from, next.to).length > CUE_CHARS ||
            next.endMs - start.startMs > CUE_MAX_MS
        val sentence = SENTENCE_END.containsMatchIn(text) && text.length >= LINE_CHARS / 2
        return tooLong || sentence
    }
}
