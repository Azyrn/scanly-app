package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

internal data class ListLevel(
    val start: Int = 1,
    val format: String = "decimal",
    val text: String? = null,
    val para: ParaProps = ParaProps(),
    val run: RunProps = RunProps()
)

internal class AbstractList(val levels: Map<Int, ListLevel>, val styleLink: String?)

internal class ListInstance(val abstractId: String, val overrides: Map<Int, ListLevel?>, val starts: Map<Int, Int>)

/**
 * numbering.xml plus the running counters. Labels are computed in document order as paragraphs
 * are read, which is the only order in which "3." can be known to follow "2.".
 *
 * Counters are shared by every list instance of one abstract list unless an instance restarts
 * its numbering, which is how Word continues a list across intervening paragraphs.
 */
internal class Numbering(
    private val abstracts: Map<String, AbstractList> = emptyMap(),
    private val instances: Map<String, ListInstance> = emptyMap()
) {
    private val counters = HashMap<String, IntArray>()

    /** The level's own formatting (indents, label font), or null when [numId] isn't a list. */
    fun level(numId: String, level: Int, styles: StyleSheet): ListLevel? = levels(numId, styles)?.get(level)

    /** Advances the counters and returns the label text, such as "2.", "b)" or "•". */
    fun nextLabel(numId: String, level: Int, styles: StyleSheet): String? {
        val instance = instances[numId] ?: return null
        val levels = levels(numId, styles) ?: return null
        val current = levels[level] ?: return null
        val counts = counters.getOrPut(counterKey(numId, instance)) { IntArray(MAX_LEVELS) { UNSET } }
        advance(counts, level, current, levels)
        return labelText(current, level, levels, counts)
    }

    /** Instances that restart or override levels count alone; plain ones share their abstract list's counters. */
    private fun counterKey(numId: String, instance: ListInstance): String =
        if (instance.starts.isEmpty() && instance.overrides.isEmpty()) instance.abstractId else "#$numId"

    /** Steps [level], resets every deeper level and seeds unset upper levels with their start values. */
    private fun advance(counts: IntArray, level: Int, current: ListLevel, levels: Map<Int, ListLevel>) {
        for (deeper in level + 1 until MAX_LEVELS) counts[deeper] = UNSET
        counts[level] = if (counts[level] == UNSET) current.start else counts[level] + 1
        for (upper in 0 until level) {
            if (counts[upper] == UNSET) counts[upper] = levels[upper]?.start ?: 1
        }
    }

    private fun levels(numId: String, styles: StyleSheet): Map<Int, ListLevel>? {
        val instance = instances[numId] ?: return null
        val abstract = abstracts[instance.abstractId]?.let { linked(it, styles) } ?: return null
        val levels = abstract.levels.toMutableMap()
        instance.overrides.forEach { (index, level) -> if (level != null) levels[index] = level }
        instance.starts.forEach { (index, start) -> levels[index]?.let { levels[index] = it.copy(start = start) } }
        return levels
    }

    /** A list defined by a numbering style keeps its levels in the list that style points at. */
    private fun linked(abstract: AbstractList, styles: StyleSheet): AbstractList {
        val link = abstract.styleLink?.takeIf { abstract.levels.isEmpty() } ?: return abstract
        val target = styles.numIdOf(link)?.let { instances[it] }?.abstractId
        return target?.let { abstracts[it] } ?: abstract
    }

    companion object {
        const val MAX_LEVELS = 9
        private const val UNSET = Int.MIN_VALUE
    }
}

private val PLACEHOLDER = Regex("%([1-9])")

private fun labelText(level: ListLevel, index: Int, levels: Map<Int, ListLevel>, counts: IntArray): String {
    if (level.format == "bullet") return symbolText(level.text ?: BULLET)
    val template = level.text ?: "%${index + 1}."
    return PLACEHOLDER.replace(template) { match ->
        val referenced = match.groupValues[1].toInt() - 1
        formatNumber(counts[referenced].coerceAtLeast(0), levels[referenced]?.format ?: "decimal")
    }
}

internal fun formatNumber(number: Int, format: String): String = when (format) {
    "none", "bullet" -> ""
    "decimalZero" -> number.toString().padStart(2, '0')
    "upperRoman" -> roman(number)
    "lowerRoman" -> roman(number).lowercase()
    "upperLetter" -> letters(number)
    "lowerLetter" -> letters(number).lowercase()
    else -> number.toString()
}

/** Word repeats the letter past Z: 27 is "AA", 28 "BB". */
private fun letters(number: Int): String {
    if (number <= 0) return number.toString()
    val letter = 'A' + (number - 1) % ALPHABET
    return letter.toString().repeat((number - 1) / ALPHABET + 1)
}

private fun roman(number: Int): String {
    if (number !in 1..MAX_ROMAN) return number.toString()
    val builder = StringBuilder()
    var rest = number
    for ((value, symbol) in ROMAN) {
        while (rest >= value) {
            builder.append(symbol)
            rest -= value
        }
    }
    return builder.toString()
}

/**
 * Symbol and Wingdings bullets are stored as private-use code points that mean nothing in any
 * other font; the common ones get their Unicode look-alike and the rest a plain bullet.
 */
internal fun symbolText(text: String): String = buildString {
    text.forEach { char ->
        val code = char.code
        append(SYMBOLS[code] ?: if (code in PRIVATE_USE) BULLET else char.toString())
    }
}

/** Reads w:numbering from its root element. */
internal fun parseNumbering(parser: XmlPullParser, theme: ThemeFonts): Numbering {
    val abstracts = HashMap<String, AbstractList>()
    val instances = HashMap<String, ListInstance>()
    parser.children { name ->
        val id = parser.attr(if (name == "abstractNum") "abstractNumId" else "numId")
        when {
            !parser.isW() || id == null -> parser.skip()
            name == "abstractNum" -> abstracts[id] = parseAbstract(parser, theme)
            name == "num" -> parseInstance(parser, theme)?.let { instances[id] = it }
            else -> parser.skip()
        }
    }
    return Numbering(abstracts, instances)
}

private fun parseAbstract(parser: XmlPullParser, theme: ThemeFonts): AbstractList {
    val levels = HashMap<Int, ListLevel>()
    var link: String? = null
    parser.children { name ->
        when (name) {
            "lvl" -> parseLevel(parser, theme)?.let { (index, level) -> levels[index] = level }
            "numStyleLink", "styleLink" -> link = parser.attr("val").also { parser.skip() }
            else -> parser.skip()
        }
    }
    return AbstractList(levels, link)
}

private fun parseInstance(parser: XmlPullParser, theme: ThemeFonts): ListInstance? {
    var abstractId: String? = null
    val overrides = HashMap<Int, ListLevel?>()
    val starts = HashMap<Int, Int>()
    parser.children { name ->
        when (name) {
            "abstractNumId" -> abstractId = parser.attr("val").also { parser.skip() }
            "lvlOverride" -> parseOverride(parser, theme, overrides, starts)
            else -> parser.skip()
        }
    }
    return abstractId?.let { ListInstance(it, overrides, starts) }
}

private fun parseOverride(
    parser: XmlPullParser,
    theme: ThemeFonts,
    overrides: MutableMap<Int, ListLevel?>,
    starts: MutableMap<Int, Int>
) {
    val index = parser.intAttr("ilvl") ?: 0
    parser.children { child ->
        when (child) {
            "startOverride" -> {
                parser.intAttr("val")?.let { starts[index] = it }
                parser.skip()
            }
            "lvl" -> overrides[index] = parseLevel(parser, theme)?.second
            else -> parser.skip()
        }
    }
}

private fun parseLevel(parser: XmlPullParser, theme: ThemeFonts): Pair<Int, ListLevel>? {
    val index = parser.intAttr("ilvl")?.takeIf { it in 0 until Numbering.MAX_LEVELS }
    var level = ListLevel()
    parser.children { name -> level = parseLevelChild(name, parser, theme, level) }
    return index?.let { it to level }
}

private typealias LevelParser = (XmlPullParser, ThemeFonts, ListLevel) -> ListLevel

/** Spells out the lambda types the map's pairs can't infer on their own. */
private fun levelParser(parse: LevelParser): LevelParser = parse

/** Readers for the children of w:lvl; each consumes its element. */
private val LEVEL_PARSERS: Map<String, LevelParser> = mapOf(
    "start" to levelParser { parser, _, level ->
        level.copy(start = parser.intAttr("val") ?: level.start).also { parser.skip() }
    },
    "numFmt" to levelParser { parser, _, level ->
        level.copy(format = parser.attr("val") ?: level.format).also { parser.skip() }
    },
    "lvlText" to levelParser { parser, _, level -> level.copy(text = parser.attr("val")).also { parser.skip() } },
    "pPr" to levelParser { parser, theme, level ->
        level.copy(para = parseParagraphProps(parser, theme, null).props)
    },
    "rPr" to levelParser { parser, theme, level -> level.copy(run = parseRunProps(parser, theme)) }
)

/** Applies one child of w:lvl to [level]; unknown children are skipped. */
private fun parseLevelChild(name: String, parser: XmlPullParser, theme: ThemeFonts, level: ListLevel): ListLevel =
    LEVEL_PARSERS[name]?.invoke(parser, theme, level) ?: level.also { parser.skip() }

private const val BULLET = "•"
private const val ALPHABET = 26
private const val MAX_ROMAN = 3999
private val PRIVATE_USE = 0xE000..0xF8FF

private val ROMAN = listOf(
    1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
    50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
)

private val SYMBOLS = mapOf(
    0xF0B7 to BULLET,
    0xF0A7 to "▪",
    0xF0A8 to "□",
    0xF06E to "■",
    0xF071 to "❑",
    0xF076 to "❖",
    0xF0D8 to "➢",
    0xF0E0 to "→",
    0xF0FC to "✓",
    0xF0FE to "☑"
)
