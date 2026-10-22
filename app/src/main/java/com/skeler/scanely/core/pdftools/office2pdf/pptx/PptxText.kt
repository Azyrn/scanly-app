package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * Text bodies and the OOXML style cascade. A run's look is decided by the first level in a chain
 * that sets each property: the run itself, the shape's list style, the layout and master
 * placeholders it inherits from, the master's title/body styles, and the deck's defaults. Every
 * property is nullable here so "not set" and "set to off" stay different until the chain ends.
 */

internal const val DEFAULT_TEXT_SIZE = 18f
private const val TEXT_SIZE_SCALE = 100f
internal const val LEVELS = 9

internal data class RunProps(
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val typeface: String? = null
) {
    fun or(o: RunProps) = RunProps(
        size ?: o.size,
        bold ?: o.bold,
        italic ?: o.italic,
        underline ?: o.underline,
        strike ?: o.strike,
        color ?: o.color,
        typeface ?: o.typeface
    )
}

internal sealed interface BulletSpec {
    data object None : BulletSpec

    data class Glyph(val char: String, val font: String?) : BulletSpec

    data class AutoNumber(val scheme: String, val start: Int) : BulletSpec
}

internal data class LevelProps(
    val align: TextAlign? = null,
    val marginLeft: Float? = null,
    val indent: Float? = null,
    val bullet: BulletSpec? = null,
    val bulletColor: Int? = null,
    val lineSpacing: Float? = null,
    val run: RunProps = RunProps()
) {
    fun or(o: LevelProps) = LevelProps(
        align ?: o.align,
        marginLeft ?: o.marginLeft,
        indent ?: o.indent,
        bullet ?: o.bullet,
        bulletColor ?: o.bulletColor,
        lineSpacing ?: o.lineSpacing,
        run.or(o.run)
    )
}

/** Nine levels, any of which may be unset. */
internal typealias ListStyle = Array<LevelProps?>

internal fun uniformStyle(props: LevelProps): ListStyle = Array(LEVELS) { props }

private fun bool(value: String?): Boolean? = when (value) {
    "1", "true", "on" -> true
    "0", "false", "off" -> false
    else -> null
}

internal fun parseRunProps(rPr: XNode?, colors: Colors): RunProps {
    rPr ?: return RunProps()
    val fill = rPr.child("solidFill") ?: rPr.path("gradFill", "gsLst", "gs")
    return RunProps(
        size = rPr.attr("sz")?.toIntOrNull()?.let { it / TEXT_SIZE_SCALE }?.takeIf { it > 0 },
        bold = bool(rPr.attr("b")),
        italic = bool(rPr.attr("i")),
        underline = rPr.attr("u")?.let { it != "none" },
        strike = rPr.attr("strike")?.let { it != "noStrike" },
        color = colorOf(fill, colors),
        typeface = rPr.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }
    )
}

internal fun parseLevel(pPr: XNode?, colors: Colors): LevelProps {
    pPr ?: return LevelProps()
    return LevelProps(
        align = alignOf(pPr.attr("algn")),
        marginLeft = emu(pPr.attr("marL")),
        indent = emu(pPr.attr("indent")),
        bullet = bulletOf(pPr),
        bulletColor = colorOf(pPr.child("buClr"), colors),
        lineSpacing = percent(pPr.path("lnSpc", "spcPct")?.attr("val")),
        run = parseRunProps(pPr.child("defRPr"), colors)
    )
}

/** buNone wins over buAutoNum, which wins over buChar; null when the level says nothing about bullets. */
private fun bulletOf(pPr: XNode): BulletSpec? {
    val numbering = pPr.child("buAutoNum")
    val glyph = pPr.child("buChar")
    return when {
        pPr.child("buNone") != null -> BulletSpec.None
        numbering != null -> BulletSpec.AutoNumber(
            numbering.attr("type") ?: "arabicPeriod",
            numbering.attr("startAt")?.toIntOrNull() ?: 1
        )
        glyph != null -> BulletSpec.Glyph(glyph.attr("char").orEmpty(), pPr.child("buFont")?.attr("typeface"))
        else -> null
    }
}

/** Justified and distributed text is drawn left-aligned. */
private fun alignOf(algn: String?): TextAlign? = when (algn) {
    "ctr" -> TextAlign.CENTER
    "r" -> TextAlign.RIGHT
    "l", "just", "dist", "justLow", "thaiDist" -> TextAlign.LEFT
    else -> null
}

/** A bodyPr or tcPr anchor value; anything unknown anchors at the top. */
internal fun anchorOf(value: String?): Anchor = when (value) {
    "ctr" -> Anchor.CENTER
    "b" -> Anchor.BOTTOM
    else -> Anchor.TOP
}

/** lstStyle, titleStyle, bodyStyle, defaultTextStyle — all the same shape: defPPr plus lvl1pPr..lvl9pPr. */
internal fun parseListStyle(node: XNode?, colors: Colors): ListStyle? {
    node ?: return null
    val base = node.child("defPPr")?.let { parseLevel(it, colors) }
    return Array(LEVELS) { i ->
        val level = node.child("lvl${i + 1}pPr")?.let { parseLevel(it, colors) }
        when {
            level != null && base != null -> level.or(base)
            else -> level ?: base
        }
    }
}
