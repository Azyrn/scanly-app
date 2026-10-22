package com.skeler.scanely.core.pdftools.office2pdf.xlsx

/**
 * A number format code split into sections and tokens. Only what changes the printed text is kept:
 * colours (`[Red]`) and conditions are dropped, `_x` becomes a space, `*x` fill is ignored, and a
 * `[$€-407]` locale block becomes its currency symbol.
 */
internal sealed interface FormatPart {
    data class Literal(val text: String) : FormatPart

    /** One unquoted character of the code: a placeholder, a date letter, or a plain separator. */
    data class Code(val char: Char) : FormatPart

    /** `[h]`, `[mm]`, `[ss]`: time that keeps counting past a day. */
    data class Elapsed(val unit: Char, val count: Int) : FormatPart
}

internal enum class SectionKind { GENERAL, NUMBER, DATE, TEXT }

internal class FormatSection(val kind: SectionKind, val parts: List<FormatPart>)

internal class FormatCode(val sections: List<FormatSection>) {

    /**
     * Excel's section rule: one section serves every number (and adds its own minus sign), two
     * split positives from negatives, a third takes zero. The fourth is for text only.
     */
    fun sectionFor(value: Double): Pair<FormatSection, Boolean> {
        val numeric = sections.filter { it.kind != SectionKind.TEXT }.ifEmpty { listOf(GENERAL_SECTION) }
        return when {
            value < 0 && numeric.size >= 2 -> numeric[1] to false
            value == 0.0 && numeric.size >= THREE_SECTIONS -> numeric[2] to false
            else -> numeric[0] to (value < 0)
        }
    }

    companion object {
        private val GENERAL_SECTION = FormatSection(SectionKind.GENERAL, emptyList())

        fun parse(code: String): FormatCode = FormatCode(splitSections(code).map(::parseSection))

        private fun splitSections(code: String): List<String> {
            val sections = mutableListOf<String>()
            var start = 0
            var i = 0
            var quoted = false
            var bracket = false
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '"' -> quoted = !quoted
                    quoted -> Unit
                    c == '\\' -> i++
                    c == '[' -> bracket = true
                    c == ']' -> bracket = false
                    c == ';' && !bracket -> {
                        sections += code.substring(start, i)
                        start = i + 1
                    }
                }
                i++
            }
            sections += code.substring(start)
            return sections
        }

        private fun parseSection(section: String): FormatSection {
            val parts = scan(section)
            val codes = parts.filterIsInstance<FormatPart.Code>().map { it.char }
            val joined = String(codes.toCharArray()).lowercase()
            val kind = when {
                joined.contains("general") -> SectionKind.GENERAL
                codes.contains('@') -> SectionKind.TEXT
                parts.any { it is FormatPart.Elapsed } || codes.any { it.lowercaseChar() in DATE_LETTERS } ->
                    SectionKind.DATE
                codes.isEmpty() && parts.isEmpty() -> SectionKind.GENERAL
                else -> SectionKind.NUMBER
            }
            return FormatSection(kind, parts)
        }

        private fun scan(section: String): List<FormatPart> {
            val parts = mutableListOf<FormatPart>()
            var i = 0
            while (i < section.length) {
                val c = section[i]
                i = when (c) {
                    '"' -> scanQuoted(section, i, parts)
                    '\\' -> scanEscaped(section, i, parts)
                    '_' -> {
                        parts += FormatPart.Literal(" ")
                        i + 2
                    }
                    '*' -> i + 2
                    '[' -> scanBracket(section, i, parts)
                    else -> {
                        parts += FormatPart.Code(c)
                        i + 1
                    }
                }
            }
            return parts
        }

        /** A `"..."` literal (unterminated runs to the end). Returns the index after it. */
        private fun scanQuoted(section: String, start: Int, parts: MutableList<FormatPart>): Int {
            val end = section.indexOf('"', start + 1).let { if (it < 0) section.length else it }
            parts += FormatPart.Literal(section.substring(start + 1, end))
            return end + 1
        }

        /** A backslash-escaped character, taken literally. Returns the index after it. */
        private fun scanEscaped(section: String, start: Int, parts: MutableList<FormatPart>): Int {
            if (start + 1 < section.length) parts += FormatPart.Literal(section[start + 1].toString())
            return start + 2
        }

        private fun scanBracket(section: String, start: Int, parts: MutableList<FormatPart>): Int {
            val end = section.indexOf(']', start).let { if (it < 0) section.length else it }
            val inner = section.substring(start + 1, end)
            val lower = inner.lowercase()
            when {
                inner.startsWith("$") -> {
                    val symbol = inner.drop(1).substringBefore('-')
                    if (symbol.isNotEmpty()) parts += FormatPart.Literal(symbol)
                }
                lower.isNotEmpty() && lower.all { it == lower[0] } && lower[0] in ELAPSED_UNITS ->
                    parts += FormatPart.Elapsed(lower[0], lower.length)
            }
            return end + 1
        }

        private const val DATE_LETTERS = "ymdhs"
        private const val ELAPSED_UNITS = "hms"
        private const val THREE_SECTIONS = 3
    }
}

/**
 * Excel's built-in formats, which a file references by id without spelling out. Ids 5–8 and the
 * accounting ones are locale-dependent in Excel; these are its en-US spellings.
 */
internal fun builtInFormat(id: Int): String? = BUILT_IN_FORMATS[id]

private val BUILT_IN_FORMATS: Map<Int, String> = mapOf(
    0 to GENERAL_FORMAT,
    1 to "0",
    2 to "0.00",
    3 to "#,##0",
    4 to "#,##0.00",
    5 to "\$#,##0_);(\$#,##0)",
    6 to "\$#,##0_);[Red](\$#,##0)",
    7 to "\$#,##0.00_);(\$#,##0.00)",
    8 to "\$#,##0.00_);[Red](\$#,##0.00)",
    9 to "0%",
    10 to "0.00%",
    11 to "0.00E+00",
    12 to GENERAL_FORMAT,
    13 to GENERAL_FORMAT,
    14 to LOCALE_SHORT_DATE,
    15 to "d-mmm-yy",
    16 to "d-mmm",
    17 to "mmm-yy",
    18 to "h:mm AM/PM",
    19 to "h:mm:ss AM/PM",
    20 to "h:mm",
    21 to "h:mm:ss",
    22 to "m/d/yyyy h:mm",
    37 to "#,##0 ;(#,##0)",
    38 to "#,##0 ;[Red](#,##0)",
    39 to "#,##0.00;(#,##0.00)",
    40 to "#,##0.00;[Red](#,##0.00)",
    41 to "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)",
    42 to "_(\"\$\"* #,##0_);_(\"\$\"* \\(#,##0\\);_(\"\$\"* \"-\"_);_(@_)",
    43 to "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)",
    44 to "_(\"\$\"* #,##0.00_);_(\"\$\"* \\(#,##0.00\\);_(\"\$\"* \"-\"??_);_(@_)",
    45 to "mm:ss",
    46 to "[h]:mm:ss",
    47 to "mm:ss.0",
    48 to "##0.0E+0",
    49 to "@"
)

/** Marker for built-in 14, which Excel shows as the reader's own short date. */
internal const val LOCALE_SHORT_DATE = "\u0000short-date"
