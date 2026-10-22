package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * DrawingML colour modifiers and the RGB/HSL arithmetic behind them, on packed ARGB ints.
 */

/** ARGB packing: bit offsets of each channel, the per-channel mask and its largest value. */
private const val ALPHA_SHIFT = 24
internal const val RED_SHIFT = 16
internal const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0
private const val CHANNEL_MASK = 0xFF
internal const val CHANNEL_MAX = 255f
private const val ROUND_HALF = 0.5f

/** The red and blue hues sit a third of the colour wheel either side of green. */
private const val ONE_THIRD = 1f / 3

/** lumMod/lumOff (how theme tints are written), tint, shade, satMod and alpha; the rest are rare. */
internal fun applyModifiers(color: Int, node: XNode): Int {
    if (node.children.isEmpty()) return color
    var alpha = 1f
    val rgb = floatArrayOf(channel(color, RED_SHIFT), channel(color, GREEN_SHIFT), channel(color, BLUE_SHIFT))
    for (m in node.children) {
        val v = percent(m.attr("val")) ?: continue
        if (m.name == "alpha") alpha = v.coerceIn(0f, 1f) else modify(rgb, m.name, v)
    }
    return pack(alpha, rgb[0], rgb[1], rgb[2])
}

/** One channel of [color] as 0..1. */
private fun channel(color: Int, shift: Int): Float = ((color shr shift) and CHANNEL_MASK) / CHANNEL_MAX

/** Applies one non-alpha modifier to [rgb] in place; unknown modifiers leave it as is. */
private fun modify(rgb: FloatArray, name: String, v: Float) {
    when (name) {
        "tint" -> for (i in rgb.indices) rgb[i] = 1 - (1 - rgb[i]) * v
        "shade" -> for (i in rgb.indices) rgb[i] *= v
        "lumMod", "lumOff", "satMod" -> modifyHsl(rgb, name, v)
    }
}

/** Luminance and saturation modifiers work in HSL; the result is clamped back into range. */
private fun modifyHsl(rgb: FloatArray, name: String, v: Float) {
    val hsl = rgbToHsl(rgb[0], rgb[1], rgb[2])
    when (name) {
        "lumMod" -> hsl[2] *= v
        "lumOff" -> hsl[2] += v
        else -> hsl[1] *= v
    }
    hslToRgb(hsl[0], hsl[1].coerceIn(0f, 1f), hsl[2].coerceIn(0f, 1f)).copyInto(rgb)
}

private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * CHANNEL_MAX + ROUND_HALF).toInt()
    return (c(a) shl ALPHA_SHIFT) or (c(r) shl RED_SHIFT) or (c(g) shl GREEN_SHIFT) or c(b)
}

/** Mixes [color] toward white, keeping [amount] of the colour — a table band's light shade. */
internal fun lighten(color: Int, amount: Float): Int {
    fun ch(shift: Int) = channel(color, shift)
    return pack(
        1f,
        1 - (1 - ch(RED_SHIFT)) * amount,
        1 - (1 - ch(GREEN_SHIFT)) * amount,
        1 - (1 - ch(BLUE_SHIFT)) * amount
    )
}

private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6 else 0)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    } / 6
    return floatArrayOf(h, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
    if (s == 0f) return floatArrayOf(l, l, l)
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    return floatArrayOf(hue(p, q, h + ONE_THIRD), hue(p, q, h), hue(p, q, h - ONE_THIRD))
}

@Suppress("MagicNumber")
private fun hue(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0) t += 1
    if (t > 1) t -= 1
    return when {
        t < 1f / 6 -> p + (q - p) * 6 * t
        t < 1f / 2 -> q
        t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
        else -> p
    }
}
