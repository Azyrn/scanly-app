package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Turns a cell's stored number into the text Excel prints, for the formats people actually use:
 * General, fixed decimals, thousands separators, percent, scientific, currency/accounting literals
 * and dates. Fractions and the rarer codes fall back to General rather than guessing.
 *
 * Separators follow the phone's locale, as Excel follows the reader's.
 */
internal class NumberFormatter(date1904: Boolean, locale: Locale = Locale.getDefault()) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val dates = DateFormatter(date1904, locale)
    private val cache = HashMap<String, FormatCode>()

    fun format(value: Double, code: String): String {
        if (value.isNaN() || value.isInfinite()) return NUM_ERROR
        val format = cache.getOrPut(code) {
            FormatCode.parse(if (code == LOCALE_SHORT_DATE) dates.shortDateCode else code)
        }
        val (section, minus) = format.sectionFor(value)
        return when (section.kind) {
            SectionKind.GENERAL, SectionKind.TEXT -> general(value)
            SectionKind.DATE -> dates.format(value, section.parts)
            SectionKind.NUMBER -> number(abs(value), section.parts, minus)
        }
    }

    /** General shows up to ten significant digits, switching to scientific for very big or small numbers. */
    fun general(value: Double): String {
        val magnitude = abs(value)
        val text = when {
            value == 0.0 -> "0"
            magnitude < GENERAL_MAX && value == floor(value) -> value.toLong().toString()
            magnitude >= GENERAL_MAX || magnitude < GENERAL_MIN ->
                scientific(value, GENERAL_SCI_DIGITS, 2, true).let { sci ->
                    sci.substringBefore('E').trimEnd('0').trimEnd('.') + "E" + sci.substringAfter('E')
                }
            else -> BigDecimal(value).round(MathContext(GENERAL_DIGITS, RoundingMode.HALF_UP))
                .stripTrailingZeros().toPlainString()
        }
        return text.replace('.', symbols.decimalSeparator)
    }

    private fun number(value: Double, parts: List<FormatPart>, minus: Boolean): String {
        val placeholderAt = parts.indices.filter { isPlaceholder(parts[it]) }
        if (placeholderAt.isEmpty()) return literalText(parts)
        val first = placeholderAt.first()
        var last = placeholderAt.last()
        var scaleDown = 0
        while (last + 1 < parts.size && parts[last + 1] == COMMA) {
            last++
            scaleDown++
        }
        val core = parts.subList(first, last + 1 - scaleDown)
        val percents = parts.count { it == PERCENT }
        val scaled = BigDecimal(value.toString()).movePointLeft(THOUSANDS_DIGITS * scaleDown)
            .movePointRight(PERCENT_DIGITS * percents)
        val body = formatCore(scaled, core) ?: return general(if (minus) -value else value)
        val sign = if (minus && body.any { it in '1'..'9' }) "-" else ""
        return sign + literalText(parts.subList(0, first)) + body + literalText(parts.subList(last + 1, parts.size))
    }

    /** Null for a core this formatter doesn't model (fractions), so the caller falls back to General. */
    private fun formatCore(value: BigDecimal, core: List<FormatPart>): String? {
        val chars = core.map { (it as? FormatPart.Code)?.char ?: LITERAL_MARK }
        if ('/' in chars) return null
        val exponentAt = chars.indexOfFirst { it == 'E' || it == 'e' }
        if (exponentAt >= 0) {
            val fraction = chars.subList(0, exponentAt).dropWhile { it != '.' }.count { it in DIGITS }
            val expDigits = chars.subList(exponentAt, chars.size).count { it == '0' }.coerceAtLeast(1)
            return scientific(value.toDouble(), fraction, expDigits, chars.getOrNull(exponentAt + 1) == '+')
                .replace('.', symbols.decimalSeparator)
        }
        if (chars.any { it !in FIXED_CHARS } || chars.count { it == '.' } > 1) return digitFill(value, core)
        return fixed(value, chars)
    }

    private fun fixed(value: BigDecimal, chars: List<Char>): String {
        val point = chars.indexOf('.')
        val intPattern = if (point >= 0) chars.subList(0, point) else chars
        val fracPattern = if (point >= 0) chars.subList(point + 1, chars.size) else emptyList()
        val maxFrac = fracPattern.count { it in DIGITS }
        val minFrac = fracPattern.count { it == '0' }
        val minInt = intPattern.count { it == '0' }
        val plain = value.setScale(maxFrac, RoundingMode.HALF_UP).toPlainString()
        var integer = plain.substringBefore('.').trimStart('0')
        var fraction = plain.substringAfter('.', "").trimEnd('0')
        while (fraction.length < minFrac) fraction += "0"
        while (integer.length < minInt) integer = "0$integer"
        if (',' in intPattern) integer = group(integer)
        return if (point >= 0) integer + symbols.decimalSeparator + fraction else integer
    }

    /** Patterns like `000-00-0000`: digits poured into the placeholders from the right. */
    private fun digitFill(value: BigDecimal, core: List<FormatPart>): String {
        var digits = value.setScale(0, RoundingMode.HALF_UP).toPlainString().trimStart('0')
        val out = StringBuilder()
        val slots = core.count { isPlaceholder(it) }
        var slot = slots
        for (part in core.asReversed()) {
            if (!isPlaceholder(part)) {
                out.insert(0, (part as? FormatPart.Literal)?.text ?: (part as FormatPart.Code).char.toString())
                continue
            }
            slot--
            val take = if (slot == 0) digits else digits.takeLast(1)
            digits = digits.dropLast(take.length)
            out.insert(0, take.ifEmpty { if ((part as FormatPart.Code).char == '0') "0" else "" })
        }
        return out.toString()
    }

    private fun scientific(value: Double, fraction: Int, expDigits: Int, plus: Boolean): String {
        val magnitude = abs(value)
        var exponent = if (magnitude == 0.0) 0 else floor(log10(magnitude)).toInt()
        var mantissa = BigDecimal(magnitude).movePointLeft(exponent).setScale(fraction, RoundingMode.HALF_UP)
        if (mantissa >= BigDecimal.TEN) {
            exponent++
            mantissa = mantissa.movePointLeft(1).setScale(fraction, RoundingMode.HALF_UP)
        }
        val sign = when {
            exponent < 0 -> "-"
            plus -> "+"
            else -> ""
        }
        val exp = abs(exponent).toString().padStart(expDigits, '0')
        val head = if (value < 0) "-" else ""
        return head + mantissa.toPlainString() + "E" + sign + exp
    }

    private fun group(integer: String): String {
        val builder = StringBuilder()
        integer.forEachIndexed { index, c ->
            if (index > 0 && (integer.length - index) % GROUP_SIZE == 0) builder.append(symbols.groupingSeparator)
            builder.append(c)
        }
        return builder.toString()
    }

    private fun literalText(parts: List<FormatPart>): String = buildString {
        parts.forEach { part ->
            when (part) {
                is FormatPart.Literal -> append(part.text)
                is FormatPart.Code -> append(part.char)
                is FormatPart.Elapsed -> Unit
            }
        }
    }

    private fun isPlaceholder(part: FormatPart): Boolean = part is FormatPart.Code && part.char in DIGITS

    private companion object {
        val COMMA = FormatPart.Code(',')
        val PERCENT = FormatPart.Code('%')
        const val DIGITS = "0#?"
        const val FIXED_CHARS = "0#?.,"
        const val LITERAL_MARK = '\u0000'
        const val NUM_ERROR = "#NUM!"
        const val GENERAL_MAX = 1e11
        const val GENERAL_MIN = 1e-9
        const val GENERAL_DIGITS = 10
        const val GENERAL_SCI_DIGITS = 5
        const val THOUSANDS_DIGITS = 3
        const val PERCENT_DIGITS = 2
        const val GROUP_SIZE = 3
    }
}
