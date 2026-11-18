package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/*
 * Word formatting is inherited: document defaults, then a style's basedOn chain, then the
 * paragraph's own properties, each overriding only what it sets. Holding a level as a sparse map
 * makes "override what it sets" a plain map union, and keeps an explicit off (`<w:b w:val="0"/>`)
 * distinguishable from "not set".
 */

internal enum class RunKey {
    STYLE, BOLD, ITALIC, UNDERLINE, STRIKE, SIZE, COLOR, BACKGROUND, FONT, VERTICAL, CAPS, HIDDEN, SCALE, SPACING, KERN,
    CS_FONT, CS_SIZE, CS_BOLD, CS_ITALIC
}

internal enum class ParaKey {
    STYLE, ALIGN, BEFORE, AFTER, LINE, LINE_RULE, LEFT, RIGHT, FIRST_LINE, CONTEXTUAL, NUM_ID, LEVEL, PAGE_BREAK_BEFORE,
    KEEP_NEXT, KEEP_LINES, WIDOW_CONTROL, TABS, AUTO_BEFORE, AUTO_AFTER, FRAME
}

/** One level of formatting: only the properties that level sets. */
internal class Props<K : Enum<K>>(val values: Map<K, Any> = emptyMap()) {
    /** This level on top of [base]: whatever is set here wins. */
    fun over(base: Props<K>): Props<K> {
        if (values.isEmpty()) return base
        val merged = HashMap(base.values)
        for ((key, value) in values) {
            val below = merged[key]
            merged[key] = if (value is TabStops && below is TabStops) value.over(below) else value
        }
        return Props(merged)
    }

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
    hidden = get<Boolean>(RunKey.HIDDEN) == true,
    scale = get<Int>(RunKey.SCALE)?.coerceIn(MIN_SCALE, MAX_SCALE) ?: FULL_SCALE,
    spacing = get<Int>(RunKey.SPACING)?.coerceIn(-MAX_SPACING, MAX_SPACING) ?: 0,
    kerning = kerning(),
    complex = complexScript()
)

/** w:kern is the smallest size, in half-points, that is kerned; 0 turns kerning off. */
private fun RunProps.kerning(): Boolean =
    (get<Int>(RunKey.KERN) ?: 0).let { it > 0 && it <= (get<Int>(RunKey.SIZE) ?: RunStyle().halfPoints) }

/** Complex-script size falls back to the plain size, then Word's default. */
private fun RunProps.complexScript(): ComplexScript = ComplexScript(
    font = get<String>(RunKey.CS_FONT),
    halfPoints = get<Int>(RunKey.CS_SIZE)?.takeIf { it > 0 } ?: get<Int>(RunKey.SIZE)?.takeIf { it > 0 }
        ?: RunStyle().halfPoints,
    bold = get<Boolean>(RunKey.CS_BOLD) == true,
    italic = get<Boolean>(RunKey.CS_ITALIC) == true
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
    autoBefore = get<Boolean>(ParaKey.AUTO_BEFORE) == true,
    autoAfter = get<Boolean>(ParaKey.AUTO_AFTER) == true,
    keepNext = get<Boolean>(ParaKey.KEEP_NEXT) == true,
    keepLines = get<Boolean>(ParaKey.KEEP_LINES) == true,
    widowControl = get<Boolean>(ParaKey.WIDOW_CONTROL) != false,
    tabs = get<TabStops>(ParaKey.TABS)?.stops?.filter { it.kind != TabKind.CLEAR }.orEmpty(),
    mark = mark,
    frame = get<FramePosition>(ParaKey.FRAME)
)

/** The theme's heading and body typefaces, which rFonts may name instead of a family. */
internal data class ThemeFonts(
    val major: String? = null,
    val minor: String? = null,
    /** The complex-script (Arabic) heading and body typefaces: rFonts cstheme majorBidi / minorBidi. */
    val majorBidi: String? = null,
    val minorBidi: String? = null
) {
    fun fontFor(parser: XmlPullParser): String? {
        val themed = parser.attr("asciiTheme") ?: parser.attr("hAnsiTheme")
        val fromTheme = themed?.let { if (it.startsWith("major")) major else minor }
        return fromTheme ?: parser.attr("ascii") ?: parser.attr("hAnsi")
    }

    /** The font of complex-script text (w:cs, or the theme's bidi font through w:cstheme). */
    fun complexFontFor(parser: XmlPullParser): String? {
        val themed = parser.attr("cstheme")?.let { if (it.startsWith("major")) majorBidi else minorBidi }
        return themed ?: parser.attr("cs")
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
            name == "tabs" -> values[ParaKey.TABS] = parseTabs(parser)
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
    "w" to runParser { parser, values, _ -> parser.intAttr("val")?.let { values[RunKey.SCALE] = it } },
    "spacing" to runParser { parser, values, _ -> parser.intAttr("val")?.let { values[RunKey.SPACING] = it } },
    "kern" to runParser { parser, values, _ -> parser.intAttr("val")?.let { values[RunKey.KERN] = it } },
    "rFonts" to runParser { parser, values, theme ->
        theme.fontFor(parser)?.let { values[RunKey.FONT] = it }
        theme.complexFontFor(parser)?.let { values[RunKey.CS_FONT] = it }
    },
    "szCs" to runParser { parser, values, _ -> parser.intAttr("val")?.let { values[RunKey.CS_SIZE] = it } },
    "bCs" to toggle(RunKey.CS_BOLD),
    "iCs" to toggle(RunKey.CS_ITALIC),
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
    "keepNext" to paraParser { parser, values -> values[ParaKey.KEEP_NEXT] = parser.toggle() },
    "keepLines" to paraParser { parser, values -> values[ParaKey.KEEP_LINES] = parser.toggle() },
    "widowControl" to paraParser { parser, values -> values[ParaKey.WIDOW_CONTROL] = parser.toggle() },
    "pageBreakBefore" to paraParser { parser, values -> values[ParaKey.PAGE_BREAK_BEFORE] = parser.toggle() },
    "spacing" to ::parseSpacing,
    "ind" to ::parseIndent,
    "framePr" to ::parseFrame
)

private fun alignment(value: String?): ParagraphAlign = when (value) {
    null, "left", "start" -> ParagraphAlign.START
    "center" -> ParagraphAlign.CENTER
    "right", "end" -> ParagraphAlign.END
    else -> ParagraphAlign.JUSTIFY
}

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
private const val MIN_SCALE = 1
private const val MAX_SCALE = 600

/** ECMA-376 limits character spacing to 1584 pt either way. */
private const val MAX_SPACING = 31_680
