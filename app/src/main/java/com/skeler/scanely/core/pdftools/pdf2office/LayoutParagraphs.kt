package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.max
import kotlin.math.min

/**
 * Lines into paragraphs, headings and list items. A line joins the paragraph above when it is the
 * same kind (or wraps a list item), keeps its size and spacing, and isn't a fresh indent or the
 * line after a short sentence end.
 */
internal object LayoutParagraphs {

    fun paragraphs(lines: List<LayoutLine>, bodySize: Float): List<ParagraphBlock> {
        val blocks = mutableListOf<ParagraphBlock>()
        var current = mutableListOf<LayoutLine>()
        var currentKind = BlockKind.BODY

        for (line in lines) {
            val kind = classify(line, bodySize)
            val continues = current.isNotEmpty() && continuesParagraph(current, currentKind, line, kind)
            if (!continues) {
                if (current.isNotEmpty()) blocks += buildParagraph(current, currentKind)
                current = mutableListOf()
                currentKind = kind
            }
            current += line
        }
        if (current.isNotEmpty()) blocks += buildParagraph(current, currentKind)
        return blocks
    }

    private fun classify(line: LayoutLine, bodySize: Float): BlockKind {
        val first = line.words.first().text
        if (ParagraphText.isBulletMarker(first)) return BlockKind.BULLET
        if (ParagraphText.NUMBERED_MARKER.matches(first) && line.words.size > 1) return BlockKind.NUMBERED
        val ratio = line.fontSize / bodySize
        val short = line.text.length <= MAX_HEADING_CHARS
        return when {
            short && ratio >= HEADING_1_RATIO -> BlockKind.HEADING_1
            short && ratio >= HEADING_2_RATIO -> BlockKind.HEADING_2
            else -> BlockKind.BODY
        }
    }

    private fun continuesParagraph(
        current: List<LayoutLine>,
        currentKind: BlockKind,
        line: LayoutLine,
        kind: BlockKind
    ): Boolean {
        val listItem = currentKind == BlockKind.BULLET || currentKind == BlockKind.NUMBERED
        val sameKind = kind == currentKind && !listItem
        // A wrapped list item: an unmarked line indented past the marker.
        val listContinuation = listItem && kind == BlockKind.BODY &&
            line.box.left > current.first().box.left + line.fontSize * HALF
        if (!sameKind && !listContinuation) return false

        val previous = current.last()
        val em = max(previous.fontSize, line.fontSize)
        if (!keepsSpacing(current, previous, line, em)) return false
        return listContinuation || keepsProse(current, previous, line, em)
    }

    /** Same size, and no wider a step down than the paragraph's own line pitch (or a blank line's worth). */
    private fun keepsSpacing(current: List<LayoutLine>, previous: LayoutLine, line: LayoutLine, em: Float): Boolean {
        if (max(previous.fontSize, line.fontSize) > min(previous.fontSize, line.fontSize) * SIZE_CHANGE_RATIO) {
            return false
        }
        val pitch = line.box.centerY - previous.box.centerY
        val established = if (current.size >= 2) current[1].box.centerY - current[0].box.centerY else null
        return if (established != null) {
            !(pitch > established * PITCH_BREAK_RATIO)
        } else {
            !(line.box.top - previous.box.bottom > em * PARAGRAPH_GAP_EM)
        }
    }

    /** Prose only: no first-line indent, and the line above wasn't a short one ending a sentence. */
    private fun keepsProse(current: List<LayoutLine>, previous: LayoutLine, line: LayoutLine, em: Float): Boolean {
        val left = current.minOf { it.box.left }
        // A first-line indent opens a new paragraph.
        if (line.box.left > left + em * INDENT_EM) return false
        // A short line ending a sentence closed its paragraph.
        if (current.size < 2) return true
        val right = current.maxOf { it.box.right }
        val endsSentence = previous.text.trimEnd().lastOrNull() in ENDINGS
        return !(endsSentence && previous.box.right < right - em * SHORT_LINE_EM)
    }

    private fun buildParagraph(lines: List<LayoutLine>, kind: BlockKind): ParagraphBlock {
        val texts = ParagraphText.lineTexts(lines, kind)
        return ParagraphBlock(
            kind = kind,
            text = ParagraphText.reflow(texts),
            lines = texts,
            lineBoxes = lines.map { it.box },
            box = lines.map { it.box }.reduce { a, b -> a.union(b) },
            fontSize = lines.map { it.fontSize }.sorted()[lines.size / 2]
        )
    }

    private const val HALF = 0.5f
    private const val MAX_HEADING_CHARS = 200
    private const val HEADING_1_RATIO = 1.45f
    private const val HEADING_2_RATIO = 1.15f
    private const val SIZE_CHANGE_RATIO = 1.15f
    private const val PITCH_BREAK_RATIO = 1.3f
    private const val PARAGRAPH_GAP_EM = 0.9f
    private const val INDENT_EM = 1f
    private const val SHORT_LINE_EM = 4f

    private val ENDINGS = setOf('.', '!', '?', ':')
}
