package com.skeler.scanely.core.transcribe

import java.util.Locale

/**
 * Every text form a transcript leaves the app in. Pure functions of the [Transcript], so the exact
 * output of each is pinned by `TranscriptFormatsTest` rather than checked by eye.
 */
object TranscriptFormats {

    /** `m:ss`, or `h:mm:ss` from an hour on — what a reader scans for in a transcript margin. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / MS_PER_SECOND
        val h = total / SECONDS_PER_HOUR
        val m = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val s = total % SECONDS_PER_MINUTE
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Paragraphs, each opened by its speaker and time — the copy/share form. */
    fun plainText(t: Transcript, timestamps: Boolean = true): String = t.segments.joinToString("\n\n") { s ->
        val label = listOfNotNull(
            t.speakerName(s.speaker),
            clock(s.startMs).takeIf { timestamps }
        ).joinToString(" · ")
        if (label.isEmpty()) s.text else "$label\n${s.text}"
    }

    /** The input to the PDF and Word exporters, which render Markdown. */
    fun markdown(t: Transcript): String = buildString {
        append("# ").append(escape(t.title)).append("\n\n")
        val facts = listOfNotNull(
            clock(t.durationMs),
            t.language.takeIf { it.isNotBlank() }?.let(::languageName),
            "${t.speakerCount} speakers".takeIf { t.speakerCount > 1 }
        )
        append('*').append(facts.joinToString(" · ")).append("*\n\n")
        for (s in t.segments) {
            val speaker = t.speakerName(s.speaker)
            if (speaker != null) append("**").append(escape(speaker)).append("** · ")
            append(clock(s.startMs)).append("\n\n")
            append(escape(s.text)).append("\n\n")
        }
    }.trimEnd() + "\n"

    /** SubRip. Cues are cut from words, not segments: a 45-second paragraph is not a subtitle. */
    fun srt(t: Transcript): String = SubtitleCues.cut(t).mapIndexed { i, cue ->
        "${i + 1}\n${stamp(cue.startMs, ',')} --> ${stamp(cue.endMs, ',')}\n${cue.lines(t, vtt = false)}"
    }.joinToString("\n\n", postfix = "\n")

    /** WebVTT, with speakers as `<v>` voice spans so players can style or announce them. */
    fun vtt(t: Transcript): String = SubtitleCues.cut(t).joinToString("\n\n", "WEBVTT\n\n", "\n") { cue ->
        "${stamp(cue.startMs, '.')} --> ${stamp(cue.endMs, '.')}\n${cue.lines(t, vtt = true)}"
    }

    private fun stamp(ms: Long, separator: Char): String {
        val v = ms.coerceAtLeast(0)
        val seconds = v / MS_PER_SECOND
        return String.format(
            Locale.US,
            "%02d:%02d:%02d%c%03d",
            seconds / SECONDS_PER_HOUR,
            (seconds / SECONDS_PER_MINUTE) % SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
            separator,
            v % MS_PER_SECOND
        )
    }

    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L

    /** Markdown the exporters would otherwise read as formatting — `*`, `_`, `#` in speech. */
    private fun escape(text: String): String =
        text.replace(Regex("([\\\\`*_#\\[\\]|<>])"), "\\\\$1")

    fun languageName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        return if (name.isBlank() || name == code) code.uppercase(Locale.ROOT) else name
    }
}
