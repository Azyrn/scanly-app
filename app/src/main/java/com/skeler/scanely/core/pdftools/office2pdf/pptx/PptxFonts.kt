package com.skeler.scanely.core.pdftools.office2pdf.pptx

/* Typeface names mapped onto the font families the phone has. */

private val SERIF_FONTS = listOf(
    "times", "georgia", "cambria", "garamond", "book antiqua", "palatino", "constantia", "baskerville",
    "bodoni", "didot", "century schoolbook", "rockwell", "bookman", "merriweather", "playfair",
    "noto serif", "droid serif", "minion", "caslon", "perpetua", "goudy", "sitka", "liberation serif",
    "dejavu serif", "tinos", "caladea", "lora", "pt serif", "source serif", "big caslon", "centaur"
)

private val MONO_FONTS = listOf(
    "courier", "consolas", "lucida console", "menlo", "monaco", "monospace", "source code", "fira code",
    "inconsolata", "cascadia", " mono"
)

private val SANS_FONTS = listOf(
    "calibri", "arial", "helvetica", "segoe", "verdana", "tahoma", "aptos", "roboto", "open sans",
    "gill sans", "century gothic", "trebuchet", "franklin gothic", "corbel", "candara", "lato",
    "montserrat", "noto sans", "source sans", "futura", "avenir", "myriad", "dengxian", "yu gothic",
    "meiryo", "malgun", "yahei", "lucida sans", "tw cen", "univers", "frutiger", "inter", "poppins",
    "raleway", "nunito", "ubuntu", "dejavu sans", "liberation sans", "arimo", "carlito", "selawik",
    "leelawadee", "gadugi", "nirmala", "ebrima", "sans", "grande", "dubai", "sakkal", "tajawal",
    "cairo", "hind", "mulish", "work sans", "oswald", "bahnschrift", "abadi", "gill", "quicksand"
)

/**
 * Maps a typeface to the closest family the phone has. Only names whose look we don't recognise
 * count as "replaced": Calibri drawn in Roboto keeps its character, a script font in Roboto doesn't.
 */
internal fun fontKind(typeface: String?, theme: Theme, issues: PptxIssues): FontKind =
    knownKind(themeTypeface(typeface, theme).lowercase()) ?: FontKind.SANS.also { issues.fontsReplaced = true }

/** "+mj-lt"/"+mn-lt" stand for the theme's heading and body fonts; no typeface means the body font. */
private fun themeTypeface(typeface: String?, theme: Theme): String = when {
    typeface == null -> theme.minorFont
    typeface.startsWith("+mj") -> theme.majorFont
    typeface.startsWith("+mn") -> theme.minorFont
    else -> typeface
}

/** The family a lowercase font [name] looks like, or null when we don't recognise it. */
private fun knownKind(name: String): FontKind? = when {
    name.isBlank() -> FontKind.SANS
    MONO_FONTS.any { it in name } -> FontKind.MONO
    SERIF_FONTS.any { it in name } || (name.contains("serif") && !name.contains("sans")) -> FontKind.SERIF
    SANS_FONTS.any { it in name } -> FontKind.SANS
    else -> null
}
