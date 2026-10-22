package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * Theme colours and DrawingML colour/fill parsing. Pure arithmetic on ARGB ints — no
 * android.graphics.Color — so the reader stays testable on a plain JVM.
 */

internal const val EMU_PER_POINT = 12_700f

/** OOXML percentages and angles are stored as integers scaled by these. */
internal const val PERCENT_SCALE = 100_000f
internal const val ANGLE_SCALE = 60_000f

internal const val BLACK = 0xFF000000.toInt()
internal const val WHITE = 0xFFFFFFFF.toInt()

/** An srgbClr value: six hex digits, no alpha. */
private const val HEX_RGB_DIGITS = 6
private const val HEX_RADIX = 16

internal fun emu(value: String?): Float? = value?.trim()?.toLongOrNull()?.let { it / EMU_PER_POINT }

internal fun percent(value: String?): Float? = value?.trim()?.toIntOrNull()?.let { it / PERCENT_SCALE }

/** The theme pieces the renderer needs: the colour scheme, the two theme fonts, line widths by style index. */
internal class Theme(
    val colors: Map<String, Int>,
    val majorFont: String,
    val minorFont: String,
    val lineWidths: List<Float>
) {
    companion object {
        /** Office's own defaults, for a deck whose master has no theme part. */
        val DEFAULT = Theme(
            colors = mapOf(
                "dk1" to BLACK, "lt1" to WHITE, "dk2" to argb(0x44546A), "lt2" to argb(0xE7E6E6),
                "accent1" to argb(0x4472C4), "accent2" to argb(0xED7D31), "accent3" to argb(0xA5A5A5),
                "accent4" to argb(0xFFC000), "accent5" to argb(0x5B9BD5), "accent6" to argb(0x70AD47),
                "hlink" to argb(0x0563C1), "folHlink" to argb(0x954F72)
            ),
            majorFont = "Calibri Light",
            minorFont = "Calibri",
            lineWidths = listOf(0.5f, 1f, 1.5f)
        )

        fun parse(root: XNode?): Theme {
            root ?: return DEFAULT
            val elements = root.child("themeElements") ?: return DEFAULT
            val colors = schemeColors(elements)
            val fonts = elements.child("fontScheme")
            val widths = elements.path("fmtScheme", "lnStyleLst")?.children("ln")
                ?.mapNotNull { emu(it.attr("w")) }
                ?.takeIf { it.isNotEmpty() }
            return Theme(
                colors = colors,
                majorFont = fonts?.path("majorFont", "latin")?.attr("typeface") ?: DEFAULT.majorFont,
                minorFont = fonts?.path("minorFont", "latin")?.attr("typeface") ?: DEFAULT.minorFont,
                lineWidths = widths ?: DEFAULT.lineWidths
            )
        }

        /** The default scheme with every slot the theme's clrScheme defines replaced. */
        private fun schemeColors(elements: XNode): HashMap<String, Int> {
            val colors = HashMap(DEFAULT.colors)
            elements.child("clrScheme")?.children?.forEach { slot ->
                val value = slot.children.firstNotNullOfOrNull(::slotColor)
                if (value != null) colors[slot.name] = value
            }
            return colors
        }

        private fun slotColor(c: XNode): Int? = when (c.name) {
            "srgbClr" -> hex(c.attr("val"))
            "sysClr" -> hex(c.attr("lastClr"))
            else -> null
        }
    }
}

/** A master's theme plus its colour map, which turns "tx1"/"bg1" into theme slots. */
internal class Colors(val theme: Theme, private val map: Map<String, String>) {
    fun scheme(name: String): Int? = theme.colors[map[name] ?: name]

    companion object {
        val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
        val DEFAULT = Colors(Theme.DEFAULT, DEFAULT_MAP)
    }
}

internal fun argb(rgb: Int): Int = rgb or BLACK

internal fun hex(value: String?): Int? {
    val v = value?.trim() ?: return null
    if (v.length != HEX_RGB_DIGITS) return null
    return v.toIntOrNull(HEX_RADIX)?.let(::argb)
}

private val PRESET_COLORS = mapOf(
    "black" to BLACK, "white" to WHITE, "red" to argb(0xFF0000), "green" to argb(0x008000),
    "blue" to argb(0x0000FF), "yellow" to argb(0xFFFF00), "gray" to argb(0x808080),
    "grey" to argb(0x808080), "orange" to argb(0xFFA500), "purple" to argb(0x800080),
    "ltGray" to argb(0xD3D3D3), "dkGray" to argb(0xA9A9A9), "navy" to argb(0x000080)
)

/** The first colour element inside [container] (solidFill, buClr, a style ref, …), with its modifiers applied. */
internal fun colorOf(container: XNode?, colors: Colors): Int? {
    container ?: return null
    val c = container.children.firstOrNull { it.name in COLOR_ELEMENTS } ?: return null
    val base = baseColor(c, colors) ?: return null
    return applyModifiers(base, c)
}

/** The DrawingML colour elements; anything else in a container (extLst, …) is passed over. */
private val COLOR_ELEMENTS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr")

private fun baseColor(c: XNode, colors: Colors): Int? = when (c.name) {
    "srgbClr" -> hex(c.attr("val"))
    "schemeClr" -> colors.scheme(c.attr("val").orEmpty())
    "sysClr" -> systemColor(c)
    "prstClr" -> PRESET_COLORS[c.attr("val")] ?: BLACK
    "scrgbClr" -> scrgb(c)
    else -> null
}

/** A system colour without its cached lastClr falls back to the usual window/text pair. */
private fun systemColor(c: XNode): Int = hex(c.attr("lastClr")) ?: if (c.attr("val") == "window") WHITE else BLACK

private fun scrgb(c: XNode): Int {
    fun channel(name: String) = ((percent(c.attr(name)) ?: 0f).coerceIn(0f, 1f) * CHANNEL_MAX).toInt()
    return argb((channel("r") shl RED_SHIFT) or (channel("g") shl GREEN_SHIFT) or channel("b"))
}
