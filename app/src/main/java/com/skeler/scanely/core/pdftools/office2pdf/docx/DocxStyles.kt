package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

internal class StyleDef(
    val basedOn: String?,
    val para: ParaProps,
    val run: RunProps,
    /** Null when the style says nothing about borders, so a basedOn ancestor can decide. */
    val tableBorders: Boolean?
)

/**
 * styles.xml, with basedOn chains flattened on demand. A chain is followed at most
 * [MAX_CHAIN] deep and never twice through one style, because a looping chain is a file error
 * Word tolerates and so must this.
 */
internal class StyleSheet(
    val defaultPara: ParaProps = ParaProps(),
    val defaultRun: RunProps = RunProps(),
    private val styles: Map<String, StyleDef> = emptyMap(),
    private val defaultParagraphStyle: String? = null,
    val defaultTableStyle: String? = null
) {
    private val cache = HashMap<String, Pair<ParaProps, RunProps>>()

    /** A paragraph style; an unknown or missing id falls back to the default style, as in Word. */
    fun paragraph(id: String?): Pair<ParaProps, RunProps> =
        resolved(id?.takeIf { it in styles } ?: defaultParagraphStyle)

    fun character(id: String?): RunProps = resolved(id).second

    fun table(id: String?): Pair<ParaProps, RunProps> = resolved(id ?: defaultTableStyle)

    fun tableBorders(id: String?): Boolean? =
        chain(id ?: defaultTableStyle).lastOrNull { it.tableBorders != null }?.tableBorders

    /** The list a numbering style stands for, which numStyleLink points at. */
    fun numIdOf(styleId: String): String? = styles[styleId]?.para?.get<String>(ParaKey.NUM_ID)

    private fun resolved(id: String?): Pair<ParaProps, RunProps> {
        if (id == null) return EMPTY
        return cache.getOrPut(id) {
            chain(id).fold(EMPTY) { (para, run), style -> style.para.over(para) to style.run.over(run) }
        }
    }

    /** Root first, so folding applies each descendant over its ancestor. */
    private fun chain(id: String?): List<StyleDef> {
        val chain = ArrayDeque<StyleDef>()
        val seen = HashSet<String>()
        var current = id
        while (current != null && seen.size < MAX_CHAIN && seen.add(current)) {
            val style = styles[current] ?: break
            chain.addFirst(style)
            current = style.basedOn
        }
        return chain
    }

    private companion object {
        val EMPTY = ParaProps() to RunProps()
        const val MAX_CHAIN = 20
    }
}

/** Reads w:styles from its root element. */
internal fun parseStyles(parser: XmlPullParser, theme: ThemeFonts): StyleSheet {
    val sheet = StyleSheetBuilder()
    parser.children { name ->
        when {
            !parser.isW() -> parser.skip()
            name == "docDefaults" -> parser.descendants { child ->
                if (child == "pPr") sheet.defaultPara = parseParagraphProps(parser, theme, null).props
                if (child == "rPr") sheet.defaultRun = parseRunProps(parser, theme)
            }
            name == "style" -> sheet.add(parser, theme)
            else -> parser.skip()
        }
    }
    return sheet.build()
}

private class StyleSheetBuilder {
    var defaultPara = ParaProps()
    var defaultRun = RunProps()
    private val styles = HashMap<String, StyleDef>()
    private var defaultParagraph: String? = null
    private var defaultTable: String? = null

    fun add(parser: XmlPullParser, theme: ThemeFonts) {
        val type = parser.attr("type")
        val id = parser.attr("styleId")
        val isDefault = parser.attr("default").let { it == "1" || it == "true" }
        val style = parseStyle(parser, theme)
        if (id == null) return
        styles[id] = style
        if (isDefault && type == "paragraph") defaultParagraph = id
        if (isDefault && type == "table") defaultTable = id
    }

    fun build() = StyleSheet(defaultPara, defaultRun, styles, defaultParagraph, defaultTable)
}

private fun parseStyle(parser: XmlPullParser, theme: ThemeFonts): StyleDef {
    var basedOn: String? = null
    var para = ParaProps()
    var run = RunProps()
    var borders: Boolean? = null
    parser.children { name ->
        when {
            !parser.isW() -> parser.skip()
            name == "basedOn" -> basedOn = parser.attr("val").also { parser.skip() }
            name == "pPr" -> para = parseParagraphProps(parser, theme, null).props
            name == "rPr" -> run = parseRunProps(parser, theme)
            name == "tblPr" -> parser.children { child ->
                if (child == "tblBorders") borders = parseBorders(parser) else parser.skip()
            }
            else -> parser.skip()
        }
    }
    return StyleDef(basedOn, para, run, borders)
}

/** tblBorders or tcBorders: drawn when any side has a visible line. */
internal fun parseBorders(parser: XmlPullParser): Boolean {
    var visible = false
    parser.children {
        if (parser.attr("val") !in INVISIBLE_BORDERS) visible = true
        parser.skip()
    }
    return visible
}

/** The theme's latin and bidi (complex-script) major (headings) and minor (body) typefaces. */
internal fun parseTheme(parser: XmlPullParser): ThemeFonts {
    val major = ThemeFaces()
    val minor = ThemeFaces()
    var inMajor = false
    parser.descendants { name ->
        when (name) {
            "majorFont" -> inMajor = true
            "minorFont" -> inMajor = false
            else -> (if (inMajor) major else minor).read(name, parser)
        }
    }
    return ThemeFonts(major.latin, minor.latin, major.bidi, minor.bidi)
}

/** One font collection of a theme (major or minor): the first latin and bidi faces it names. */
private class ThemeFaces {
    var latin: String? = null
    var bidi: String? = null

    /** The bidi font is a:cs when it names one, else the script's own entry (<a:font script="Arab">). */
    fun read(name: String, parser: XmlPullParser) {
        val face = parser.attr("typeface")?.takeIf { it.isNotBlank() } ?: return
        if (name == "latin" && latin == null) latin = face
        val isBidi = name == "cs" || name == "font" && parser.attr("script") == "Arab"
        if (isBidi && bidi == null) bidi = face
    }
}

private val INVISIBLE_BORDERS = setOf(null, "nil", "none")
