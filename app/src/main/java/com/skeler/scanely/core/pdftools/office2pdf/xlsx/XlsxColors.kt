package com.skeler.scanely.core.pdftools.office2pdf.xlsx

/**
 * Resolves an OOXML colour (`rgb`, `theme` + `tint`, or legacy `indexed`) to opaque ARGB. Alpha in
 * the file is ignored, as Excel ignores it for cell colours.
 */
internal class XlsxColors(private val theme: List<Int>) {

    fun resolve(cursor: XmlCursor): Int? {
        if (cursor.attr("auto") == "1") return null
        val base = cursor.attr("rgb")?.let(::parseRgb)
            ?: cursor.attr("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
            ?: cursor.attr("indexed")?.toIntOrNull()?.let(::indexed)
            ?: return null
        val tint = cursor.attr("tint")?.toDoubleOrNull() ?: 0.0
        return if (tint == 0.0) base else applyTint(base, tint)
    }

    private fun indexed(index: Int): Int? = when (index) {
        SYSTEM_FOREGROUND -> BLACK
        SYSTEM_BACKGROUND -> WHITE
        else -> INDEXED_PALETTE.getOrNull(index)?.let { it or OPAQUE }
    }

    companion object {
        /** Office's default theme, for workbooks that ship without a theme part. */
        val DEFAULT_THEME: List<Int> = listOf(
            0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4, 0xED7D31,
            0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47, 0x0563C1, 0x954F72
        ).map { it or OPAQUE }

        fun parseRgb(value: String): Int? {
            val hex = value.trim().takeLast(RGB_HEX_DIGITS)
            if (hex.length != RGB_HEX_DIGITS) return null
            return hex.toIntOrNull(HEX)?.let { it or OPAQUE }
        }

        /**
         * Excel tints in HSL lightness; scaling each channel toward black or white is within a shade
         * of it for the tints Office offers, and avoids a colour-space round trip per cell.
         */
        fun applyTint(color: Int, tint: Double): Int {
            fun channel(shift: Int): Int {
                val c = (color shr shift) and CHANNEL
                val tinted = if (tint < 0) c * (1 + tint) else c + (CHANNEL - c) * tint
                return tinted.toInt().coerceIn(0, CHANNEL) shl shift
            }
            return OPAQUE or channel(RED_SHIFT) or channel(GREEN_SHIFT) or channel(0)
        }

        private const val OPAQUE = 0xFF000000.toInt()
        private const val CHANNEL = 0xFF
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val RGB_HEX_DIGITS = 6
        private const val HEX = 16
        private const val SYSTEM_FOREGROUND = 64
        private const val SYSTEM_BACKGROUND = 65

        /** The legacy 64-colour palette `indexed` refers to. */
        private val INDEXED_PALETTE = intArrayOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
            0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
            0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
            0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
            0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
            0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
        )
    }
}

/** The theme part's colour scheme, in the order `theme="n"` indexes it (light and dark swapped). */
internal fun readThemeColors(cursor: XmlCursor): List<Int> {
    val found = HashMap<String, Int>()
    var current: String? = null
    while (true) {
        when (cursor.next()) {
            XmlCursor.START -> current = onThemeStart(cursor, current, found)
            XmlCursor.END -> if (cursor.name in SCHEME_SLOTS) current = null
            XmlCursor.END_DOCUMENT -> break
        }
    }
    return THEME_ORDER.mapIndexed { index, slot -> found[slot] ?: XlsxColors.DEFAULT_THEME[index] }
}

/** One start tag of the colour scheme: opens a slot, or fills the open one. Returns the slot now open. */
private fun onThemeStart(cursor: XmlCursor, current: String?, found: MutableMap<String, Int>): String? {
    val name = cursor.name
    if (name in SCHEME_SLOTS) return name
    if (name == "srgbClr" || name == "sysClr") {
        val value = cursor.attr(if (name == "sysClr") "lastClr" else "val")?.let(XlsxColors::parseRgb)
        if (current != null && value != null) found[current] = value
    }
    return current
}

private val SCHEME_SLOTS = setOf(
    "dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"
)

private val THEME_ORDER = listOf(
    "lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"
)
