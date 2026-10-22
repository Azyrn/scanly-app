package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** A resolved date/time token; `m` is already decided as month or minute. */
private sealed interface DateToken {
    data class Text(val text: String) : DateToken

    data class Field(val letter: Char, val count: Int) : DateToken

    data class AmPm(val short: Boolean, val upper: Boolean) : DateToken

    data class Elapsed(val unit: Char, val count: Int) : DateToken

    data class FractionOfSecond(val digits: Int) : DateToken
}

/** Calendar fields of a serial, computed once per cell. */
private class DateFields(
    val year: Int,
    val month: Int,
    val day: Int,
    val weekday: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millis: Int,
    val totalMillis: Long
)

/**
 * Excel stores dates as day serials. In the 1900 system day 1 is 1900-01-01 and day 60 is the
 * 29 February 1900 that never existed (kept for Lotus 1-2-3 compatibility), so serials from 61 on
 * are one day ahead of a true count. The 1904 system (old Mac workbooks) has no such day.
 */
internal class DateFormatter(private val date1904: Boolean, private val locale: Locale) {
    private val names = DateFormatSymbols.getInstance(locale)

    /** Built-in format 14 is "the reader's short date"; the locale's pattern, with a four-digit year. */
    val shortDateCode: String by lazy {
        val pattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()
        pattern?.let(::javaPatternToExcel) ?: "m/d/yyyy"
    }

    fun format(serial: Double, parts: List<FormatPart>): String {
        if (serial < 0 || serial > MAX_SERIAL) return OUT_OF_RANGE
        val tokens = tokenize(parts)
        val fields = fields(serial, keepMillis = tokens.any { it is DateToken.FractionOfSecond })
        val twelveHour = tokens.any { it is DateToken.AmPm }
        return buildString { tokens.forEach { append(render(it, fields, twelveHour)) } }
    }

    private fun render(token: DateToken, f: DateFields, twelveHour: Boolean): String = when (token) {
        is DateToken.Text -> token.text
        is DateToken.Field -> field(token, f, twelveHour)
        is DateToken.AmPm -> amPm(token, f)
        is DateToken.Elapsed -> elapsed(token, f)
        is DateToken.FractionOfSecond ->
            "." + f.millis.toString().padStart(MILLIS_DIGITS, '0').take(token.digits)
    }

    private fun amPm(token: DateToken.AmPm, f: DateFields): String {
        val text = if (f.hour < NOON) (if (token.short) "A" else "AM") else (if (token.short) "P" else "PM")
        return if (token.upper) text else text.lowercase()
    }

    private fun elapsed(token: DateToken.Elapsed, f: DateFields): String {
        val divisor = when (token.unit) {
            'h' -> MILLIS_PER_HOUR
            'm' -> MILLIS_PER_MINUTE
            else -> MILLIS_PER_SECOND
        }
        return (f.totalMillis / divisor).toString().padStart(token.count, '0')
    }

    private fun field(token: DateToken.Field, f: DateFields, twelveHour: Boolean): String {
        val n = token.count
        return when (token.letter) {
            'y' -> if (n <= 2) (f.year % YEARS_PER_CENTURY).toString().padStart(2, '0') else f.year.toString()
            'M' -> month(n, f)
            'd' -> day(n, f)
            'h' -> {
                val hour = if (twelveHour) (f.hour + HOURS_12 - 1) % HOURS_12 + 1 else f.hour
                hour.toString().padStart(n.coerceAtMost(2), '0')
            }
            'm' -> f.minute.toString().padStart(n.coerceAtMost(2), '0')
            else -> f.second.toString().padStart(n.coerceAtMost(2), '0')
        }
    }

    /** `m`/`mm` as a number, `mmm` and `mmmm` as names, `mmmmm` as the name's initial. */
    private fun month(n: Int, f: DateFields): String = when (n) {
        1, 2 -> f.month.toString().padStart(n, '0')
        MMM -> names.shortMonths[f.month - 1]
        MMMM -> names.months[f.month - 1]
        else -> names.months[f.month - 1].take(1)
    }

    /** `d`/`dd` as the day of month, `ddd` and longer as the weekday's name. */
    private fun day(n: Int, f: DateFields): String = when (n) {
        1, 2 -> f.day.toString().padStart(n, '0')
        DDD -> names.shortWeekdays[f.weekday]
        else -> names.weekdays[f.weekday]
    }

    private fun fields(serial: Double, keepMillis: Boolean): DateFields {
        val rawMillis = serial * MILLIS_PER_DAY
        val unit = if (keepMillis) 1L else MILLIS_PER_SECOND
        val totalMillis = (rawMillis / unit).roundToLong() * unit
        val dayNumber = floor(totalMillis.toDouble() / MILLIS_PER_DAY).toLong()
        val ofDay = totalMillis - dayNumber * MILLIS_PER_DAY
        val date = calendarDate(dayNumber)
        return DateFields(
            year = date.year.toInt(),
            month = date.month.toInt(),
            day = date.day.toInt(),
            weekday = (Math.floorMod(date.unixDay + THURSDAY, DAYS_PER_WEEK) + 1).toInt(),
            hour = (ofDay / MILLIS_PER_HOUR).toInt(),
            minute = (ofDay % MILLIS_PER_HOUR / MILLIS_PER_MINUTE).toInt(),
            second = (ofDay % MILLIS_PER_MINUTE / MILLIS_PER_SECOND).toInt(),
            millis = (ofDay % MILLIS_PER_SECOND).toInt(),
            totalMillis = totalMillis
        )
    }

    /** Year, month, day and the Unix day number of a serial day. */
    private class CalendarDate(val year: Long, val month: Long, val day: Long, val unixDay: Long)

    private fun calendarDate(serialDay: Long): CalendarDate {
        if (!date1904 && serialDay == LEAP_BUG_DAY) {
            return CalendarDate(LEAP_BUG_YEAR, FEBRUARY, LEAP_BUG_DATE, UNIX_DAY_1900_02_28 + 1)
        }
        val unixDay = when {
            date1904 -> serialDay - EPOCH_1904
            serialDay < LEAP_BUG_DAY -> serialDay - EPOCH_1900 + 1
            else -> serialDay - EPOCH_1900
        }
        // Days-to-civil (proleptic Gregorian), so no Calendar or time zone can shift the date.
        val z = unixDay + DAYS_0000_TO_1970
        val era = Math.floorDiv(z, DAYS_PER_ERA)
        val doe = z - era * DAYS_PER_ERA
        val yoe = (doe - doe / DAYS_4Y + doe / DAYS_100Y - doe / (DAYS_PER_ERA - 1)) / DAYS_PER_YEAR
        val doy = doe - (DAYS_PER_YEAR * yoe + yoe / LEAP_EVERY - yoe / YEARS_PER_CENTURY)
        val mp = (MONTH_FACTOR * doy + 2) / MONTH_DAYS_153
        val day = doy - (MONTH_DAYS_153 * mp + 2) / MONTH_FACTOR + 1
        val month = if (mp < MARCH_SHIFT_LIMIT) mp + MARCH_OFFSET else mp - MONTH_WRAP
        val year = yoe + era * YEARS_PER_ERA + if (month <= 2) 1 else 0
        return CalendarDate(year, month, day, unixDay)
    }

    private fun tokenize(parts: List<FormatPart>): List<DateToken> {
        val tokens = mutableListOf<DateToken>()
        var i = 0
        while (i < parts.size) {
            i = when (val part = parts[i]) {
                is FormatPart.Literal -> { tokens += DateToken.Text(part.text); i + 1 }
                is FormatPart.Elapsed -> { tokens += DateToken.Elapsed(part.unit, part.count); i + 1 }
                is FormatPart.Code -> codeToken(parts, i, tokens)
            }
        }
        return resolveMinutes(tokens)
    }

    private fun codeToken(parts: List<FormatPart>, start: Int, tokens: MutableList<DateToken>): Int {
        val c = (parts[start] as FormatPart.Code).char
        val lower = c.lowercaseChar()
        return when {
            lower == 'a' && matches(parts, start, "am/pm") -> {
                tokens += DateToken.AmPm(short = false, upper = c == 'A'); start + AM_PM.length
            }
            lower == 'a' && matches(parts, start, "a/p") -> {
                tokens += DateToken.AmPm(short = true, upper = c == 'A'); start + A_P.length
            }
            lower == '.' && (parts.getOrNull(start + 1) as? FormatPart.Code)?.char == '0' ->
                fractionToken(parts, start, tokens)
            lower in FIELD_LETTERS -> fieldToken(parts, start, lower, tokens)
            else -> { tokens += DateToken.Text(c.toString()); start + 1 }
        }
    }

    /** `m` is minutes right after an hour or right before seconds, and the month everywhere else. */
    private fun resolveMinutes(tokens: List<DateToken>): List<DateToken> {
        val fields = tokens.filter { it is DateToken.Field || it is DateToken.Elapsed }
        return tokens.map { token ->
            if (token !is DateToken.Field || token.letter != 'M' || token.count > 2) return@map token
            val at = fields.indexOf(token)
            val before = fields.getOrNull(at - 1)
            val after = fields.getOrNull(at + 1)
            val afterHour = (before as? DateToken.Field)?.letter == 'h' || (before as? DateToken.Elapsed)?.unit == 'h'
            val beforeSecond = (after as? DateToken.Field)?.letter == 's' || (after as? DateToken.Elapsed)?.unit == 's'
            if (afterHour || beforeSecond) DateToken.Field('m', token.count) else token
        }
    }

    private companion object {
        /** `.0`, `.00`, `.000` after seconds: the digits of a fraction of a second. Returns the next index. */
        private fun fractionToken(parts: List<FormatPart>, start: Int, tokens: MutableList<DateToken>): Int {
            var end = start + 1
            while ((parts.getOrNull(end) as? FormatPart.Code)?.char == '0') end++
            tokens += DateToken.FractionOfSecond(end - start - 1)
            return end
        }

        /** A run of one field letter in any case; `e` is a year, `m` a month until resolved. Returns the next index. */
        private fun fieldToken(parts: List<FormatPart>, start: Int, lower: Char, tokens: MutableList<DateToken>): Int {
            var end = start
            while ((parts.getOrNull(end) as? FormatPart.Code)?.char?.lowercaseChar() == lower) end++
            val letter = when (lower) { 'e' -> 'y'; 'm' -> 'M'; else -> lower }
            tokens += DateToken.Field(letter, end - start)
            return end
        }

        private fun matches(parts: List<FormatPart>, start: Int, word: String): Boolean =
            word.indices.all { (parts.getOrNull(start + it) as? FormatPart.Code)?.char?.lowercaseChar() == word[it] }

        const val OUT_OF_RANGE = "#######"
        const val MAX_SERIAL = 2_958_466.0
        const val FIELD_LETTERS = "ymdhse"
        const val AM_PM = "am/pm"
        const val A_P = "a/p"
        const val NOON = 12
        const val HOURS_12 = 12
        const val MMM = 3
        const val MMMM = 4
        const val DDD = 3
        const val MILLIS_DIGITS = 3
        const val YEARS_PER_CENTURY = 100
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_HOUR = 3_600_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val DAYS_PER_WEEK = 7L
        const val THURSDAY = 4L
        const val LEAP_BUG_DAY = 60L
        const val LEAP_BUG_YEAR = 1900L
        const val FEBRUARY = 2L
        const val LEAP_BUG_DATE = 29L
        const val UNIX_DAY_1900_02_28 = -25_509L
        const val EPOCH_1900 = 25_569L
        const val EPOCH_1904 = 24_107L
        const val DAYS_0000_TO_1970 = 719_468L
        const val DAYS_PER_ERA = 146_097L
        const val DAYS_4Y = 1_460L
        const val DAYS_100Y = 36_524L
        const val DAYS_PER_YEAR = 365L
        const val LEAP_EVERY = 4L
        const val YEARS_PER_ERA = 400L
        const val MONTH_FACTOR = 5L
        const val MONTH_DAYS_153 = 153L
        const val MARCH_SHIFT_LIMIT = 10L
        const val MARCH_OFFSET = 3L
        const val MONTH_WRAP = 9L
    }
}

/** `M/d/yy` → `m/d/yyyy`: Java pattern letters to Excel's, years always four digits. */
private fun javaPatternToExcel(pattern: String): String {
    val builder = StringBuilder()
    var quoted = false
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\'' -> quoted = !quoted
            quoted -> builder.append('\\').append(c)
            c == 'y' -> {
                while (i + 1 < pattern.length && pattern[i + 1] == 'y') i++
                builder.append("yyyy")
            }
            else -> appendUnquoted(builder, c)
        }
        i++
    }
    return builder.toString()
}

/** One unquoted pattern character other than a year: month and day letters map, other letters drop. */
private fun appendUnquoted(builder: StringBuilder, c: Char) {
    when {
        c == 'M' || c == 'L' -> builder.append('m')
        c == 'd' -> builder.append('d')
        c.isLetter() -> Unit
        else -> builder.append('\\').append(c)
    }
}
