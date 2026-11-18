package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.roundToInt

/**
 * Which blocks of a page read as headings. A PDF keeps no paragraph styles, only how the text looks:
 * a short block set larger than the page's body text, or bold where the body is not, stands alone
 * as a heading. The writer marks it with an outline level, which Word shows in its navigation pane
 * and contents; nothing on the page moves.
 */
internal object ExactHeadings {

    /** Each heading block of [layout] with its outline level (0 = top). */
    fun levels(layout: ExactPageLayout): Map<Block, Int> {
        val body = bodyStyle(layout.blocks) ?: return emptyMap()
        val out = HashMap<Block, Int>()
        for (block in layout.blocks) {
            val level = level(block, body) ?: continue
            out[block] = level
        }
        return out
    }

    private class Body(val sizePt: Float, val boldShare: Float)

    /** The size most of the page's letters are set in, and how much of that text is bold. */
    private fun bodyStyle(blocks: List<Block>): Body? {
        val bySize = HashMap<Int, Int>()
        var letters = 0
        var bold = 0
        for (token in blocks.flatMap { b -> b.lines.flatMap { it.tokens } }) {
            val n = token.text.count(Char::isLetter)
            bySize.merge((token.style.sizePt * SIZE_STEPS).roundToInt(), n, Int::plus)
            letters += n
            if (token.style.bold) bold += n
        }
        val size = bySize.maxByOrNull { it.value }?.key ?: return null
        return if (letters == 0) null else Body(size / SIZE_STEPS, bold.toFloat() / letters)
    }

    private fun level(block: Block, body: Body): Int? {
        if (!standsAlone(block)) return null
        val words = block.lines.flatMap { it.tokens }.filter { it.text.any(Char::isLetter) }
        val size = words.minOf { it.style.sizePt }
        val larger = size >= body.sizePt * LARGER
        val bold = body.boldShare < BODY_BOLD_LIMIT && words.all { it.style.bold }
        return when {
            larger && size >= body.sizePt * TOP_LEVEL -> 0
            larger || bold && size >= body.sizePt * NOT_SMALLER -> 1
            else -> null
        }
    }

    /** A short upright block of words that does not end like a sentence. */
    private fun standsAlone(block: Block): Boolean {
        if (block.lines.size > MAX_LINES || block.angleDeg != 0) return false
        val tokens = block.lines.flatMap { it.tokens }
        val text = tokens.joinToString(" ") { it.text }.trim()
        return text.count(Char::isLetter) >= MIN_LETTERS && tokens.size <= MAX_WORDS && text.last() !in SENTENCE_END
    }

    private const val SIZE_STEPS = 2f
    private const val MAX_LINES = 3
    private const val MAX_WORDS = 24
    private const val MIN_LETTERS = 2

    /** Larger than the body by more than a rounding of the size. */
    private const val LARGER = 1.15f
    private const val TOP_LEVEL = 1.5f

    /** Bold text is only a heading where the body is not bold itself, and not set smaller than it. */
    private const val BODY_BOLD_LIMIT = 0.5f
    private const val NOT_SMALLER = 0.95f
    private val SENTENCE_END = setOf('.', ',', ';')
}
