package com.skeler.scanely.core.pdftools.organize

/** Inclusive, 1-based page numbers, as the user types and qpdf reads them. */
data class PageRange(val first: Int, val last: Int) {
    init {
        require(first in 1..last) { "invalid range $first-$last" }
    }

    val size: Int get() = last - first + 1

    /** qpdf page-range syntax for this range. */
    val spec: String get() = if (first == last) "$first" else "$first-$last"
}

/** Why a typed range list was refused; each maps to its own inline message. */
enum class RangeError {
    /** Nothing typed. */
    EMPTY,

    /** Not numbers and dashes, such as "1-" followed by letters or "3--5". */
    SYNTAX,

    /** A page number below 1 or past the last page. */
    OUT_OF_BOUNDS,

    /** "9-3": refused rather than silently reversed, because a reversed range is usually a typo. */
    REVERSED
}

sealed interface RangeParse {
    data class Ok(val ranges: List<PageRange>) : RangeParse

    /** [token] is the piece of the input that failed, for the message; null for [RangeError.EMPTY]. */
    data class Invalid(val error: RangeError, val token: String?) : RangeParse
}

/**
 * Parses what a user types into a page-range box: "1-3, 5, 8-10". Pure Kotlin so the rules are
 * unit-tested on the JVM, and the options screen and the job agree on them exactly.
 *
 * Commas, semicolons and whitespace all separate, since phone keyboards make each the easiest to
 * reach on some layout; an en or em dash counts as a hyphen because autocorrect produces them,
 * and spaces around a dash are dropped so "1 - 3" is one range.
 * "8-" runs to the last page. Order and repeats are kept: what each mode does with them is the
 * mode's business (one file per range, or pages in the order typed).
 */
object PageRanges {

    private val SEPARATORS = Regex("[,;\\s]+")
    private val DASHES = Regex("\\s*[-–—−]\\s*")
    private val SINGLE = Regex("\\d+")
    private val SPAN = Regex("(\\d+)-(\\d*)")

    fun parse(text: String, pageCount: Int): RangeParse {
        require(pageCount > 0) { "document has no pages" }
        val tokens = text.replace(DASHES, "-")
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RangeParse.Invalid(RangeError.EMPTY, null)

        val ranges = ArrayList<PageRange>(tokens.size)
        for (token in tokens) {
            val parsed = parseToken(token, pageCount)
            if (parsed is RangeParse.Invalid) return parsed
            ranges += (parsed as RangeParse.Ok).ranges
        }
        return RangeParse.Ok(ranges)
    }

    /** The pages [ranges] name, each once, ascending. */
    fun pagesOf(ranges: List<PageRange>): Set<Int> =
        ranges.flatMapTo(sortedSetOf<Int>()) { it.first..it.last }

    /** [pages] (1-based, any order) folded back into the fewest ascending ranges. */
    fun compact(pages: Collection<Int>): List<PageRange> {
        val sorted = pages.toSortedSet()
        val out = ArrayList<PageRange>()
        var start = -1
        var previous = -1
        for (page in sorted) {
            if (start < 0) {
                start = page
            } else if (page != previous + 1) {
                out += PageRange(start, previous)
                start = page
            }
            previous = page
        }
        if (start > 0) out += PageRange(start, previous)
        return out
    }

    /** qpdf page-range syntax for a whole list, in order. */
    fun spec(ranges: List<PageRange>): String = ranges.joinToString(",") { it.spec }

    private fun parseToken(token: String, pageCount: Int): RangeParse {
        if (SINGLE.matchEntire(token) != null) return parseSingle(token, pageCount)
        val span = SPAN.matchEntire(token) ?: return RangeParse.Invalid(RangeError.SYNTAX, token)
        return parseSpan(span, token, pageCount)
    }

    private fun parseSingle(token: String, pageCount: Int): RangeParse {
        val page = token.toIntOrNull() ?: return RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, token)
        if (page !in 1..pageCount) return RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, token)
        return RangeParse.Ok(listOf(PageRange(page, page)))
    }

    /** `a-b`, or `a-` for "to the end". */
    private fun parseSpan(span: MatchResult, token: String, pageCount: Int): RangeParse {
        // Digits that overflow an Int are past any real page count, not a syntax error.
        val first = span.groupValues[1].toIntOrNull() ?: return RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, token)
        val lastText = span.groupValues[2]
        val last = if (lastText.isEmpty()) {
            pageCount
        } else {
            lastText.toIntOrNull() ?: return RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, token)
        }
        return when {
            first !in 1..pageCount || last !in 1..pageCount -> RangeParse.Invalid(RangeError.OUT_OF_BOUNDS, token)
            first > last -> RangeParse.Invalid(RangeError.REVERSED, token)
            else -> RangeParse.Ok(listOf(PageRange(first, last)))
        }
    }
}
