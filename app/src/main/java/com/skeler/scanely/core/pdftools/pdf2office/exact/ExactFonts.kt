package com.skeler.scanely.core.pdftools.pdf2office.exact

/**
 * The Office font a PDF font becomes, chosen so the receiving app has it or a metric twin of it:
 * Word ships each of these, LibreOffice substitutes the Liberation/Carlito/Caladea twin with the
 * same advance widths. Measurements on the phone use those twins (bundled in assets/fonts), so a
 * width corrected here comes out the same width there.
 *
 * Fonts outside this set (Georgia, Verdana, embedded subsets with made-up names) are mapped by
 * their descriptor flags rather than kept by name: a name the reader doesn't have is substituted
 * with unknown metrics, which is exactly what makes lines drift.
 */
enum class OfficeFamily(val officeName: String, private val assets: Array<String>) {
    TIMES(
        "Times New Roman",
        arrayOf(
            "fonts/LiberationSerif-Regular.ttf",
            "fonts/LiberationSerif-Bold.ttf",
            "fonts/LiberationSerif-Italic.ttf",
            "fonts/LiberationSerif-BoldItalic.ttf"
        )
    ),
    ARIAL(
        "Arial",
        arrayOf(
            // pdfbox-android ships the regular face; the APK doesn't carry it twice.
            "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf",
            "fonts/LiberationSans-Bold.ttf",
            "fonts/LiberationSans-Italic.ttf",
            "fonts/LiberationSans-BoldItalic.ttf"
        )
    ),
    COURIER(
        "Courier New",
        arrayOf(
            "fonts/LiberationMono-Regular.ttf",
            "fonts/LiberationMono-Bold.ttf",
            "fonts/LiberationMono-Italic.ttf",
            "fonts/LiberationMono-BoldItalic.ttf"
        )
    ),
    CALIBRI(
        "Calibri",
        arrayOf(
            "fonts/Carlito-Regular.ttf",
            "fonts/Carlito-Bold.ttf",
            "fonts/Carlito-Italic.ttf",
            "fonts/Carlito-BoldItalic.ttf"
        )
    ),
    CAMBRIA(
        "Cambria",
        arrayOf(
            "fonts/Caladea-Regular.ttf",
            "fonts/Caladea-Bold.ttf",
            "fonts/Caladea-Italic.ttf",
            "fonts/Caladea-BoldItalic.ttf"
        )
    );

    /** Asset path of the metric twin for this style; regular and bold carry italics too. */
    fun asset(bold: Boolean, italic: Boolean): String = assets[(if (bold) 1 else 0) + (if (italic) 2 else 0)]

    companion object {
        /** Arabic script has no italic and is drawn from its own face in every family. */
        const val ARABIC_NAME = "Noto Naskh Arabic"
        const val ARABIC_REGULAR = "fonts/NotoNaskhArabic-Regular.ttf"
        const val ARABIC_BOLD = "fonts/NotoNaskhArabic-Bold.ttf"

        // PDF font descriptor flags (PDF 32000-1:2008, table 123).
        private const val FLAG_FIXED_PITCH = 1
        private const val FLAG_SERIF = 1 shl 1
        private const val FLAG_ITALIC = 1 shl 6
        private const val FLAG_FORCE_BOLD = 1 shl 18

        private const val BOLD_WEIGHT = 600

        /** [name] as pdfium reports it, possibly with a subset prefix ("ABCDEF+Times-Bold"). */
        fun of(name: String, flags: Int): OfficeFamily {
            val n = name.substringAfter('+').lowercase()
            // The first family whose name hints match, in this order; else the descriptor's flags.
            val byName = listOf(
                MONO_HINTS to COURIER,
                listOf("calibri", "carlito") to CALIBRI,
                listOf("cambria", "caladea") to CAMBRIA,
                SANS_HINTS to ARIAL,
                SERIF_HINTS to TIMES
            ).firstOrNull { (hints, _) -> hints.any { it in n } }?.second
            return byName ?: when {
                flags and FLAG_FIXED_PITCH != 0 -> COURIER
                flags and FLAG_SERIF != 0 -> TIMES
                else -> ARIAL
            }
        }

        fun isBold(name: String, flags: Int, weight: Int): Boolean {
            val n = name.substringAfter('+').lowercase()
            if (BOLD_HINTS.any { it in n }) return true
            // A face that names its own weight ("Regular") is that weight: pdfium's estimate from
            // stem widths reads 704 for Noto Naskh Arabic Regular.
            if (REGULAR_HINTS.any { it in n }) return false
            return weight >= BOLD_WEIGHT || flags and FLAG_FORCE_BOLD != 0
        }

        fun isItalic(name: String, flags: Int): Boolean {
            val n = name.substringAfter('+').lowercase()
            return flags and FLAG_ITALIC != 0 || ITALIC_HINTS.any { it in n } || n.endsWith("-it") ||
                n.endsWith("-boldit") || n.endsWith(",italic")
        }

        private val ITALIC_HINTS = listOf("italic", "oblique", "cmti", "cmmi", "cmsl")

        private val MONO_HINTS =
            listOf("courier", "mono", "cousine", "consola", "typewriter", "cmtt", "sfmono", "nimbusmon", "lmmono")
        private val SANS_HINTS = listOf(
            "arial", "helvetica", "arimo", "liberationsans", "liberation sans", "nimbussans", "nimbus sans",
            "dejavusans", "dejavu sans", "verdana", "tahoma", "segoe", "roboto", "opensans", "open sans",
            "notosans", "noto sans", "lato", "frutiger", "univers", "myriad", "gill", "futura", "calibri",
            "sans"
        )
        private val SERIF_HINTS = listOf(
            "times", "tinos", "liberationserif", "liberation serif", "nimbusrom", "nimbus roman", "georgia",
            "garamond", "palatino", "book antiqua", "cmr", "cmbx", "cmti", "minion", "utopia", "charter",
            "notoserif", "noto serif", "serif", "roman"
        )
        private val REGULAR_HINTS = listOf("regular", "-roman", "-book", "-normal", "-light", "medium")
        private val BOLD_HINTS = listOf("bold", "black", "heavy", "semibold", "demi", "cmbx", "-bd", ",bd")
    }
}

/** Measures text the way the receiving app will lay it out: no kerning, no ligatures. */
fun interface TextMeasurer {
    /** Advance width of [text] in points at [sizePt]. */
    fun width(text: String, family: OfficeFamily, arabic: Boolean, bold: Boolean, italic: Boolean, sizePt: Float): Float
}
