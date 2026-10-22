package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs

/*
 * Word formatting is inherited: document defaults, then a style's basedOn chain, then the
 * paragraph's own properties, each overriding only what it sets. Holding a level as a sparse map
 * makes "override what it sets" a plain map union, and keeps an explicit off (`<w:b w:val="0"/>`)
 * distinguishable from "not set".
 */

internal enum class RunKey {
    STYLE, BOLD, ITALIC, UNDERLINE, STRIKE, SIZE, COLOR, BACKGROUND, FONT, VERTICAL, CAPS, HIDDEN
}

internal enum class ParaKey {
    STYLE, ALIGN, BEFORE, AFTER, LINE, LINE_RULE, LEFT, RIGHT, FIRST_LINE, CONTEXTUAL, NUM_ID, LEVEL, PAGE_BREAK_BEFORE
}

/** One level of formatting: only the properties that level sets. */
internal class Props<K : Enum<K>>(val values: Map<K, Any> = emptyMap()) {
    /** This level on top of [base]: whatever is set here wins. */
    fun over(base: Props<K>): Props<K> = if (values.isEmpty()) base else Props(base.values + values)

    inline operator fun <reified T> get(key: K): T? = values[key] as? T
}

internal typealias RunProps = Props<RunKey>
internal typealias ParaProps = Props<ParaKey>

internal fun RunProps.toStyle(): RunStyle = RunStyle(
    bold = get<Boolean>(RunKey.BOLD) == true,
    italic = get<Boolean>(RunKey.ITALIC) == true,
    underline = get<Boolean>(RunKey.UNDERLINE) == true,
    strike = get<Boolean>(RunKey.STRIKE) == true,
    halfPoints = get<Int>(RunKey.SIZE)?.takeIf { it > 0 } ?: RunStyle().halfPoints,
    color = get<Int>(RunKey.COLOR)?.takeIf { it != AUTO_COLOR },
    background = get<Int>(RunKey.BACKGROUND)?.takeIf { it != AUTO_COLOR },
    font = get<String>(RunKey.FONT),
    verticalAlign = get<VerticalAlign>(RunKey.VERTICAL) ?: VerticalAlign.BASELINE,
    caps = get<Boolean>(RunKey.CAPS) == true,
    hidden = get<Boolean>(RunKey.HIDDEN) == true
)

internal fun ParaProps.toStyle(mark: RunStyle): ParagraphStyle = ParagraphStyle(
    styleId = get<String>(ParaKey.STYLE),
    alignment = get<ParagraphAlign>(ParaKey.ALIGN) ?: ParagraphAlign.START,
    spaceBefore = get<Int>(ParaKey.BEFORE) ?: 0,
    spaceAfter = get<Int>(ParaKey.AFTER) ?: 0,
    line = get<Int>(ParaKey.LINE) ?: SINGLE_LINE,
    lineRule = get<LineRule>(ParaKey.LINE_RULE) ?: LineRule.AUTO,
    indentLeft = get<Int>(ParaKey.LEFT) ?: 0,
    indentRight = get<Int>(ParaKey.RIGHT) ?: 0,
    firstLine = get<Int>(ParaKey.FIRST_LINE) ?: 0,
    contextualSpacing = get<Boolean>(ParaKey.CONTEXTUAL) == true,
    mark = mark
)

/** The theme's heading and body typefaces, which rFonts may name instead of a family. */
internal data class ThemeFonts(val major: String? = null, val minor: String? = null) {
    fun fontFor(parser: XmlPullParser): String? {
        val themed = parser.attr("asciiTheme") ?: parser.attr("hAnsiTheme")
        val fromTheme = themed?.let { if (it.startsWith("major")) major else minor }
        return fromTheme ?: parser.attr("ascii") ?: parser.attr("hAnsi")
    }
}

/** w:rPr. Unknown children are skipped; every known one is an attribute-only leaf. */
internal fun parseRunProps(parser: XmlPullParser, theme: ThemeFonts): RunProps {
    val values = HashMap<RunKey, Any>()
    parser.children { name ->
        if (parser.isW()) RUN_PARSERS[name]?.invoke(parser, values, theme)
        parser.skip()
    }
    return Props(values)
}

/** w:pPr, with its paragraph-mark run properties and a section that ends at this paragraph. */
internal class ParagraphProperties(val props: ParaProps, val mark: RunProps, val section: PageSetup?)

internal fun parseParagraphProps(
    parser: XmlPullParser,
    theme: ThemeFonts,
    section: SectionReader?
): ParagraphProperties {
    val values = HashMap<ParaKey, Any>()
    var mark = RunProps()
    var setup: PageSetup? = null
    parser.children { name ->
        when {
            !parser.isW() -> parser.skip()
            name == "rPr" -> mark = parseRunProps(parser, theme)
            name == "numPr" -> parseNumPr(parser, values)
            name == "sectPr" && section != null -> setup = section.read(parser)
            else -> {
                PARA_PARSERS[name]?.invoke(parser, values)
                parser.skip()
            }
        }
    }
    return ParagraphProperties(Props(values), mark, setup)
}

private fun parseNumPr(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    parser.children { name ->
        if (name == "numId") parser.attr("val")?.let { values[ParaKey.NUM_ID] = it }
        if (name == "ilvl") parser.intAttr("val")?.let { values[ParaKey.LEVEL] = it }
        parser.skip()
    }
}

/**
 * w:sectPr. Collects header/footer references (only their presence matters: they are left out,
 * and the result says so when one has content) and notes multi-column layouts.
 */
internal class SectionReader(private val notes: DocxNotes, val headerRefs: MutableSet<String>) {
    fun read(parser: XmlPullParser): PageSetup {
        var setup = PageSetup()
        parser.children { name ->
            if (parser.isW()) setup = apply(name, parser, setup)
            parser.skip()
        }
        return setup
    }

    private fun apply(name: String, parser: XmlPullParser, setup: PageSetup): PageSetup = when (name) {
        "pgSz" -> pageSize(parser, setup)
        "pgMar" -> margins(parser, setup)
        "type" -> setup.copy(continuous = parser.attr("val") == "continuous")
        "cols" -> setup.also { noteColumns(parser) }
        "headerReference", "footerReference" -> setup.also { collectHeaderRef(parser) }
        else -> setup
    }

    private fun noteColumns(parser: XmlPullParser) {
        if ((parser.intAttr("num") ?: 1) > 1) notes.columns = true
    }

    private fun collectHeaderRef(parser: XmlPullParser) {
        parser.relAttr("id")?.let { headerRefs += it }
    }

    private fun pageSize(parser: XmlPullParser, setup: PageSetup): PageSetup {
        val width = parser.intAttr("w") ?: setup.widthTwips
        val height = parser.intAttr("h") ?: setup.heightTwips
        val landscape = parser.attr("orient") == "landscape"
        // Word writes landscape as swapped sizes plus the flag; some generators set only the flag.
        return if (landscape && width < height) {
            setup.copy(widthTwips = height, heightTwips = width)
        } else {
            setup.copy(widthTwips = width, heightTwips = height)
        }
    }

    /** A negative top/bottom margin means "text may run into the header"; only its size matters here. */
    private fun margins(parser: XmlPullParser, setup: PageSetup): PageSetup = setup.copy(
        marginTop = parser.intAttr("top")?.let(::abs) ?: setup.marginTop,
        marginBottom = parser.intAttr("bottom")?.let(::abs) ?: setup.marginBottom,
        marginLeft = parser.intAttr("left") ?: parser.intAttr("start") ?: setup.marginLeft,
        marginRight = parser.intAttr("right") ?: parser.intAttr("end") ?: setup.marginRight
    )
}

private typealias RunParser = (XmlPullParser, MutableMap<RunKey, Any>, ThemeFonts) -> Unit

/** Spells out the lambda types the map's pairs can't infer on their own. */
private fun runParser(parse: RunParser): RunParser = parse

private fun toggle(key: RunKey): RunParser = runParser { parser, values, _ -> values[key] = parser.toggle() }

private val RUN_PARSERS: Map<String, RunParser> = mapOf(
    "rStyle" to runParser { parser, values, _ -> parser.attr("val")?.let { values[RunKey.STYLE] = it } },
    "b" to toggle(RunKey.BOLD),
    "i" to toggle(RunKey.ITALIC),
    "strike" to toggle(RunKey.STRIKE),
    "dstrike" to toggle(RunKey.STRIKE),
    "caps" to toggle(RunKey.CAPS),
    "smallCaps" to toggle(RunKey.CAPS),
    "vanish" to toggle(RunKey.HIDDEN),
    "u" to runParser { parser, values, _ -> values[RunKey.UNDERLINE] = parser.attr("val") != "none" },
    "sz" to runParser { parser, values, _ -> parser.intAttr("val")?.let { values[RunKey.SIZE] = it } },
    "color" to runParser { parser, values, _ -> parseColor(parser.attr("val"))?.let { values[RunKey.COLOR] = it } },
    "highlight" to runParser { parser, values, _ ->
        values[RunKey.BACKGROUND] = HIGHLIGHTS[parser.attr("val")] ?: AUTO_COLOR
    },
    "shd" to runParser { parser, values, _ ->
        parseColor(parser.attr("fill"))?.takeIf { it != AUTO_COLOR }?.let { values[RunKey.BACKGROUND] = it }
    },
    "rFonts" to runParser { parser, values, theme -> theme.fontFor(parser)?.let { values[RunKey.FONT] = it } },
    "vertAlign" to runParser { parser, values, _ ->
        values[RunKey.VERTICAL] = when (parser.attr("val")) {
            "superscript" -> VerticalAlign.SUPER
            "subscript" -> VerticalAlign.SUB
            else -> VerticalAlign.BASELINE
        }
    }
)

private typealias ParaParser = (XmlPullParser, MutableMap<ParaKey, Any>) -> Unit

private fun paraParser(parse: ParaParser): ParaParser = parse

private val PARA_PARSERS: Map<String, ParaParser> = mapOf(
    "pStyle" to paraParser { parser, values -> parser.attr("val")?.let { values[ParaKey.STYLE] = it } },
    "jc" to paraParser { parser, values -> values[ParaKey.ALIGN] = alignment(parser.attr("val")) },
    "contextualSpacing" to paraParser { parser, values -> values[ParaKey.CONTEXTUAL] = parser.toggle() },
    "pageBreakBefore" to paraParser { parser, values -> values[ParaKey.PAGE_BREAK_BEFORE] = parser.toggle() },
    "spacing" to ::parseSpacing,
    "ind" to ::parseIndent
)

private fun alignment(value: String?): ParagraphAlign = when (value) {
    null, "left", "start" -> ParagraphAlign.START
    "center" -> ParagraphAlign.CENTER
    "right", "end" -> ParagraphAlign.END
    else -> ParagraphAlign.JUSTIFY
}

/** Auto spacing is Word's HTML-import rule: a fixed 14 pt whatever the number says. */
private fun parseSpacing(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    val autoBefore = isOn(parser.attr("beforeAutospacing"))
    val autoAfter = isOn(parser.attr("afterAutospacing"))
    values.putNotNull(ParaKey.BEFORE, if (autoBefore) AUTO_SPACING else parser.intAttr("before"))
    values.putNotNull(ParaKey.AFTER, if (autoAfter) AUTO_SPACING else parser.intAttr("after"))
    values.putNotNull(ParaKey.LINE, parser.intAttr("line"))
    values.putNotNull(ParaKey.LINE_RULE, LINE_RULES[parser.attr("lineRule")])
}

/** A hanging indent is stored as a negative first-line indent, and wins when both are given. */
private fun parseIndent(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    values.putNotNull(ParaKey.LEFT, parser.intAttr("left") ?: parser.intAttr("start"))
    values.putNotNull(ParaKey.RIGHT, parser.intAttr("right") ?: parser.intAttr("end"))
    values.putNotNull(ParaKey.FIRST_LINE, parser.intAttr("firstLine"))
    values.putNotNull(ParaKey.FIRST_LINE, parser.intAttr("hanging")?.unaryMinus())
}

private fun isOn(value: String?): Boolean = value == "1" || value == "true"

private fun <K> MutableMap<K, Any>.putNotNull(key: K, value: Any?) {
    if (value != null) put(key, value)
}

private val LINE_RULES = mapOf("auto" to LineRule.AUTO, "exact" to LineRule.EXACT, "atLeast" to LineRule.AT_LEAST)

private val HIGHLIGHTS = mapOf(
    "yellow" to 0xFFFF00,
    "green" to 0x00FF00,
    "cyan" to 0x00FFFF,
    "magenta" to 0xFF00FF,
    "blue" to 0x0000FF,
    "red" to 0xFF0000,
    "darkBlue" to 0x000080,
    "darkCyan" to 0x008080,
    "darkGreen" to 0x008000,
    "darkMagenta" to 0x800080,
    "darkRed" to 0x800000,
    "darkYellow" to 0x808000,
    "darkGray" to 0x808080,
    "lightGray" to 0xC0C0C0,
    "black" to 0x000000,
    "white" to 0xFFFFFF
)

private const val SINGLE_LINE = 240
private const val AUTO_SPACING = 280
