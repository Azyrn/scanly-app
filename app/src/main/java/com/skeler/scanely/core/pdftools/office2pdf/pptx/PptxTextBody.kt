package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * Text bodies: paragraphs, runs and bullets resolved against the style cascade in PptxText.kt.
 */

private const val DEFAULT_INSET_X = 7.2f
private const val DEFAULT_INSET_Y = 3.6f

/**
 * Builds a drawable text body, or null when there is nothing visible — an empty placeholder
 * shows its prompt only while editing, never in a slideshow or a PDF.
 *
 * [bodyChain] is this body's bodyPr followed by the inherited ones; [styles] is the cascade below
 * the body's own lstStyle, strongest first.
 */
internal fun readTextBody(
    txBody: XNode?,
    bodyChain: List<XNode>,
    styles: List<ListStyle>,
    colors: Colors,
    issues: PptxIssues
): TextBody? {
    txBody ?: return null
    val autofit = bodyChain.firstNotNullOfOrNull { it.child("normAutofit") }
    val chain = listOfNotNull(parseListStyle(txBody.child("lstStyle"), colors)) + styles

    val counters = IntArray(LEVELS)
    val paragraphs = txBody.children("p").map { p ->
        readParagraph(p, chain, counters, colors, issues)
    }
    if (paragraphs.none { para -> para.runs.any { it.text.isNotBlank() } }) return null

    return TextBody(
        insets = bodyInsets(bodyChain),
        anchor = anchorOf(bodyAttr(bodyChain, "anchor")),
        wrap = bodyAttr(bodyChain, "wrap") != "none",
        fontScale = fontScaleOf(autofit),
        lineReduction = lineReductionOf(autofit),
        paragraphs = paragraphs
    )
}

/** The nearest bodyPr in the chain that sets [name]. */
private fun bodyAttr(bodyChain: List<XNode>, name: String): String? = bodyChain.firstNotNullOfOrNull { it.attr(name) }

private fun bodyInsets(bodyChain: List<XNode>): Insets = Insets(
    left = emu(bodyAttr(bodyChain, "lIns")) ?: DEFAULT_INSET_X,
    top = emu(bodyAttr(bodyChain, "tIns")) ?: DEFAULT_INSET_Y,
    right = emu(bodyAttr(bodyChain, "rIns")) ?: DEFAULT_INSET_X,
    bottom = emu(bodyAttr(bodyChain, "bIns")) ?: DEFAULT_INSET_Y
)

/** normAutofit's shrink, as PowerPoint last computed it; clamped so a bad value can't vanish the text. */
private fun fontScaleOf(autofit: XNode?): Float =
    percent(autofit?.attr("fontScale"))?.coerceIn(MIN_FONT_SCALE, 1f) ?: 1f

private fun lineReductionOf(autofit: XNode?): Float =
    percent(autofit?.attr("lnSpcReduction"))?.coerceIn(0f, MAX_LINE_REDUCTION) ?: 0f

private const val MIN_FONT_SCALE = 0.1f
private const val MAX_LINE_REDUCTION = 0.5f

private fun readParagraph(
    p: XNode,
    chain: List<ListStyle>,
    counters: IntArray,
    colors: Colors,
    issues: PptxIssues
): Paragraph {
    val pPr = p.child("pPr")
    val level = pPr?.attr("lvl")?.toIntOrNull()?.coerceIn(0, LEVELS - 1) ?: 0
    var props = parseLevel(pPr, colors)
    for (style in chain) style[level]?.let { props = props.or(it) }

    val runs = p.children.mapNotNull { node ->
        runText(node)?.takeIf { it.isNotEmpty() }?.let { text -> textRun(text, node, props, colors, issues) }
    }
    val bullet = nextBullet(props.bullet, runs.any { it.text.isNotBlank() }, level, counters)

    return Paragraph(
        align = props.align ?: TextAlign.LEFT,
        marginLeft = props.marginLeft ?: 0f,
        indent = props.indent ?: 0f,
        bullet = bullet,
        bulletColor = props.bulletColor,
        lineSpacing = props.lineSpacing,
        runs = runs,
        endSize = parseRunProps(p.child("endParaRPr"), colors).or(props.run).size ?: DEFAULT_TEXT_SIZE
    )
}

/** The text a paragraph child contributes, or null for children that aren't text (pPr, endParaRPr, …). */
private fun runText(node: XNode): String? = when (node.name) {
    "r", "fld" -> node.child("t")?.text.orEmpty()
    "br" -> "\n"
    else -> null
}

private fun textRun(text: String, node: XNode, props: LevelProps, colors: Colors, issues: PptxIssues): TextRun {
    val run = parseRunProps(node.child("rPr"), colors).or(props.run)
    return TextRun(
        text = text,
        size = run.size ?: DEFAULT_TEXT_SIZE,
        bold = run.bold ?: false,
        italic = run.italic ?: false,
        underline = run.underline ?: false,
        strike = run.strike ?: false,
        color = run.color ?: BLACK,
        font = fontKind(run.typeface, colors.theme, issues)
    )
}

/**
 * The paragraph's bullet text, keeping [counters] in step: an auto-numbered paragraph advances its
 * level's count, any other visible paragraph resets it, and every deeper level restarts.
 * Empty paragraphs show no bullet and leave the count alone.
 */
private fun nextBullet(spec: BulletSpec?, visible: Boolean, level: Int, counters: IntArray): String? {
    val bullet = when {
        !visible -> null
        spec is BulletSpec.AutoNumber -> autoNumber(spec.scheme, nextNumber(spec, level, counters))
        spec is BulletSpec.Glyph -> bulletGlyph(spec.char, spec.font)
        else -> null
    }
    if (spec !is BulletSpec.AutoNumber && visible) counters[level] = 0
    for (deeper in level + 1 until LEVELS) counters[deeper] = 0
    return bullet
}

private fun nextNumber(spec: BulletSpec.AutoNumber, level: Int, counters: IntArray): Int {
    val n = if (counters[level] > 0) counters[level] + 1 else spec.start
    counters[level] = n
    return n
}
