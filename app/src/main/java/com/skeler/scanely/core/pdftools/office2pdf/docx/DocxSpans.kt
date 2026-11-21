package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import kotlin.math.max
import kotlin.math.min

/*
 * Span work on a paragraph's text that needs no fonts: footnote anchors, the footnote separator,
 * Word 2013's narrower spaces on justified lines, and the slices a very long paragraph is laid out in.
 */

internal const val OBJECT_CHAR = "\uFFFC"

/** Word's footnote separator: a rule two inches long from the paragraph's start. */
private const val SEPARATOR_WIDTH = 144f

/** The share of its width a space may give up on a justified line (Word 2013+): 1 - 18/23 dots. */
internal const val SPACE_SHRINK = 5f / 23f

private const val CHUNK_CHARS = 20_000
private const val CHUNK_SEARCH = 2_000

/** The footnote anchors in [text] and the line of [layout] each sits on. */
internal fun notesOf(text: CharSequence, layout: StaticLayout): List<NoteOnLine> {
    if (text !is Spanned) return emptyList()
    return text.getSpans(0, text.length, NoteAnchor::class.java).map { anchor ->
        NoteOnLine(layout.getLineForOffset(text.getSpanStart(anchor)), anchor.id, anchor.mark)
    }
}

internal fun appendSeparator(text: SpannableStringBuilder) {
    val start = text.length
    text.append(OBJECT_CHAR)
    text.setSpan(RuleSpan(SEPARATOR_WIDTH), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/**
 * Word 2013 and later fit a word on a justified line when the line holds it with its spaces shrunk by
 * up to [SPACE_SHRINK]; the line is then justified to the margin as any other. Measured on Word's PDFs of
 * the UN pairs: the narrowest space on a justified 9.96 pt line is 18 dots of 600 dpi for a natural 23
 * (2.161 of 2.765 pt) in every document. Breaking with spaces that much narrower reproduces that rule;
 * justification then sets each full line's spaces as Word does. Right-to-left (joined) runs keep full spaces.
 *
 * So: [natural] with each space between words narrowed by [SPACE_SHRINK] (a horizontal scale on the space
 * glyph alone, which Android honours when it breaks, justifies and draws a line; a paint's word spacing
 * is ignored when breaking and dropped when drawing a justified line).
 */
internal fun narrowSpaces(natural: SpannableStringBuilder): SpannableStringBuilder {
    val text = SpannableStringBuilder(natural)
    text.indices.filter { text[it] == ' ' }.forEach { i ->
        val run = text.getSpans(i, i + 1, RunSpan::class.java).firstOrNull()
        if (run != null && !run.joined) {
            text.setSpan(NarrowSpace(1f - SPACE_SHRINK), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return text
}

/** The text a paragraph's lines are broken on: [natural], or with narrower spaces when [shrink] ([narrowSpaces]). */
internal fun forBreaking(natural: SpannableStringBuilder, shrink: Boolean): SpannableStringBuilder =
    if (shrink) narrowSpaces(natural) else natural

/** Slices of at most [CHUNK_CHARS], cut after a line break or space where one is near. */
internal fun chunks(text: CharSequence): List<IntRange> {
    if (text.length <= CHUNK_CHARS) return listOf(0 until text.length)
    val ranges = ArrayList<IntRange>()
    var start = 0
    while (start < text.length) {
        var end = min(start + CHUNK_CHARS, text.length)
        if (end < text.length) {
            val window = text.subSequence(end - CHUNK_SEARCH, end)
            val cut = max(window.lastIndexOf('\n'), window.lastIndexOf(' '))
            if (cut >= 0) end = end - CHUNK_SEARCH + cut + 1
        }
        ranges += start until end
        start = end
    }
    return ranges
}
