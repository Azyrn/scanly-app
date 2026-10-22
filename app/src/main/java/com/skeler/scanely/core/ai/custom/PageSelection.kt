package com.skeler.scanely.core.ai.custom

/**
 * Resolves the page range the app already sends to `/v1/ocr` into zero-based page indices.
 *
 * The backend parses this itself, in `worker/src/pages.ts`, and a scan on a user's own endpoint
 * never reaches it — the range has to be applied here or not at all. Applying it here is also what
 * lets the endpoint be sent five pages of a two-hundred-page PDF instead of the first twenty.
 *
 * Deliberately the same grammar as the server's: `1-5`, `2,4,7`, one-based and inclusive. The two
 * must agree, because the same dialog produces both.
 */
internal object PageSelection {

    /** Matches `parsePageSelection` in the Worker. */
    private const val MAX_PARTS = 64

    private val PART = Regex("""(\d{1,6})(?:\s*-\s*(\d{1,6}))?""")

    sealed interface Result {
        /** Zero-based, sorted, unique, and inside the document. */
        data class Pages(val indices: List<Int>) : Result

        data object Malformed : Result

        /** The range is well-formed but names pages this document does not have. */
        data class OutOfRange(val pageCount: Int) : Result
    }

    fun parse(spec: String?, pageCount: Int): Result {
        val trimmed = spec?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Pages((0 until pageCount).toList())

        val parts = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val spans = if (parts.isEmpty() || parts.size > MAX_PARTS) null else parts.map(::spanOf)
        if (spans == null || spans.any { it == null }) return Result.Malformed

        // Clamped rather than expanded: `1-999999` is a legal spec, and materialising it before
        // intersecting with the document would allocate a million entries to keep ten.
        val pages = spans.filterNotNull().flatMapTo(sortedSetOf()) { span ->
            (span.first..minOf(span.last, pageCount)).map { it - 1 }
        }

        return if (pages.isEmpty()) Result.OutOfRange(pageCount) else Result.Pages(pages.toList())
    }

    /** One `4` or `2-7`, one-based and inclusive. Null when it is not a range at all. */
    private fun spanOf(part: String): IntRange? {
        val match = PART.matchEntire(part) ?: return null
        val from = match.groupValues[1].toIntOrNull() ?: return null
        val to = match.groupValues[2].ifEmpty { null }?.toIntOrNull() ?: from
        return if (from < 1 || to < from) null else from..to
    }
}
