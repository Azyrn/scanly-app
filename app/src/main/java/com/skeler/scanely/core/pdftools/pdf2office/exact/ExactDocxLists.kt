package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.XML_HEADER
import com.skeler.scanely.core.pdftools.pdf2office.xmlText
import kotlin.math.roundToInt

/**
 * A list item's marker read back into what Word numbers with: a counter in a number format between
 * a [prefix] and a [suffix] ("(a)", "3.", "iv)"), or a bullet character ([format] "bullet").
 */
internal class ListLabel(
    val format: String,
    val value: Int,
    val prefix: String,
    val suffix: String,
    val bullet: String
) {

    /** [next] counts on from this label: the same format, one more. */
    private fun follows(next: ListLabel): Boolean = format == next.format && value + 1 == next.value

    /** Word's level text: the counter as %1 inside its brackets, or the bullet itself. */
    val levelText: String get() = if (format == BULLET) bullet else "$prefix%1$suffix"

    companion object {
        const val BULLET = "bullet"
        private const val MAX_LENGTH = 8
        private const val LETTERS = 26
        private val COUNTER = Regex("""^([(\[]?)([0-9]+|[A-Za-z]+)([.)\]:]{0,2})$""")
        private const val BULLETS = "•◦▪▫■□●○◆◇‣⁃∙·–—-*"
        private val ROMAN = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)

        /**
         * [text] as a marker, or null when it does not read as one. [after] is the marker before it in
         * the same list: "i" after "h" is the letter, after nothing it is the Roman numeral.
         */
        fun parse(text: String, after: ListLabel? = null): ListLabel? {
            val marker = text.trim()
            if (marker.length == 1 && marker[0] in BULLETS) return ListLabel(BULLET, 1, "", "", marker)
            val match = COUNTER.matchEntire(marker)?.takeIf { marker.length <= MAX_LENGTH }?.destructured
            // A bare word is text; a counter is closed by a bracket or a full stop.
            if (match == null || match.component1().isEmpty() && match.component3().isEmpty()) return null
            val (open, counter, close) = match
            return if (counter[0].isDigit()) {
                ListLabel("decimal", counter.toInt(), open, close, "")
            } else {
                lettered(counter, open, close, after)
            }
        }

        /** A counter in letters: a letter of the alphabet or a Roman numeral, by what came before it. */
        private fun lettered(counter: String, open: String, close: String, after: ListLabel?): ListLabel? {
            val upper = counter[0].isUpperCase()
            if (counter.any { it.isUpperCase() != upper }) return null
            val case = if (upper) "upper" else "lower"
            val asLetter = letterValue(counter.lowercase())?.let { ListLabel(case + "Letter", it, open, close, "") }
            val asRoman = romanValue(counter.lowercase())?.let { ListLabel(case + "Roman", it, open, close, "") }
            // One that continues the label before wins; else "i", "ii", "iv" read as Roman, "c" as a letter.
            val continued = listOfNotNull(asLetter, asRoman).firstOrNull { after?.follows(it) == true }
            return continued ?: asRoman?.takeIf { counter.length > 1 || it.value == 1 } ?: asLetter
        }

        /** "a".."z" as 1..26, "aa" as 27 (Word repeats the letter). */
        private fun letterValue(text: String): Int? {
            if (text.any { it != text[0] } || text[0] !in 'a'..'z') return null
            return (text[0] - 'a' + 1) + (text.length - 1) * LETTERS
        }

        private fun romanValue(text: String): Int? {
            var total = 0
            for ((i, c) in text.withIndex()) {
                val value = ROMAN[c] ?: return null
                val next = text.getOrNull(i + 1)?.let { ROMAN[it] } ?: 0
                total += if (value < next) -value else value
            }
            return total.takeIf { it > 0 }
        }
    }
}

/**
 * The document's numbering part (word/numbering.xml). Every list item names a numbering instance;
 * items of one list that count on from each other share one, so Word renumbers them together when an
 * item is added or removed. An instance starts at the number its first item shows.
 */
internal class DocxLists {
    private class Open(val list: Int, val level: Int, val label: ListLabel, val abstractId: Int, val numId: Int)

    private val abstracts = LinkedHashMap<String, Int>()
    private val instances = ArrayList<Pair<Int, Int>>()
    private val open = HashMap<Long, Open>()

    val isEmpty: Boolean get() = instances.isEmpty()

    /** The label of [text] in the list [list] at depth [level], counting on from the item before when it does. */
    fun label(text: String, list: Int, level: Int): ListLabel? = ListLabel.parse(text, open[key(list, level)]?.label)

    /** The numbering instance (w:numId) for an item labelled [label], drawn in [style]. */
    fun numId(label: ListLabel, style: TextStyle, list: Int, level: Int): Int {
        val abstractId = abstracts.getOrPut(levelXml(label, style)) { abstracts.size }
        val key = key(list, level)
        val before = open[key]
        val continues = before != null && before.abstractId == abstractId && label.format != ListLabel.BULLET &&
            before.label.value + 1 == label.value
        val sameBullets = before != null && before.abstractId == abstractId && label.format == ListLabel.BULLET
        val numId = if (continues || sameBullets) {
            before.numId
        } else {
            instances += abstractId to label.value
            instances.size
        }
        open[key] = Open(list, level, label, abstractId, numId)
        return numId
    }

    private fun key(list: Int, level: Int): Long = list.toLong() * MAX_LEVELS + level

    /** One level: the paragraph itself carries the indents, so the level only names the format and the look. */
    private fun levelXml(label: ListLabel, style: TextStyle): String {
        val font = xmlText(
            if (label.format == ListLabel.BULLET) bulletFont(label.bullet, style) else style.family.officeName
        )
        val half = (style.sizePt * 2).roundToInt().coerceAtLeast(2)
        val rgb = style.argb and RGB_MASK
        return "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"${label.format}\"/><w:suff w:val=\"tab\"/>" +
            "<w:lvlText w:val=\"${xmlText(label.levelText)}\"/><w:lvlJc w:val=\"left\"/><w:rPr>" +
            "<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:cs=\"$font\" w:hint=\"default\"/>" +
            (if (style.bold) "<w:b/>" else "<w:b w:val=\"0\"/>") +
            (if (style.italic) "<w:i/>" else "<w:i w:val=\"0\"/>") +
            (if (rgb != 0) "<w:color w:val=\"%06X\"/>".format(rgb) else "") +
            "<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/></w:rPr></w:lvl>"
    }

    private fun bulletFont(bullet: String, style: TextStyle): String = when (bullet[0]) {
        SYMBOL_BULLET -> "Symbol"
        in WINGDINGS -> "Wingdings"
        else -> style.family.officeName
    }

    fun xml(): String {
        val xml = StringBuilder(XML_HEADER).append("<w:numbering xmlns:w=\"$NS_WORD_MAIN\">")
        abstracts.forEach { (level, id) ->
            xml.append("<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"singleLevel\"/>")
                .append(level).append("</w:abstractNum>")
        }
        instances.forEachIndexed { index, (abstractId, start) ->
            xml.append("<w:num w:numId=\"${index + 1}\"><w:abstractNumId w:val=\"$abstractId\"/>")
                .append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"$start\"/></w:lvlOverride></w:num>")
        }
        return xml.append("</w:numbering>").toString()
    }

    private companion object {
        const val MAX_LEVELS = 16L
        const val RGB_MASK = 0xFFFFFF

        /** Word's own bullets are private-use characters of the Symbol and Wingdings fonts. */
        const val SYMBOL_BULLET = '\uF0B7'
        const val WINGDINGS = "\uF0A7\uF0D8\uF0FC\uF076"
    }
}

internal const val NS_WORD_MAIN = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
