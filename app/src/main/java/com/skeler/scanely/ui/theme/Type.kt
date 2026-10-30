package com.skeler.scanely.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.skeler.scanely.R

val ScanelyFontFamily = FontFamily.Default

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.15).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ScanelyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Every style takes its paragraph direction from its own text (first strong character), not from
 * the layout: the UI has no Arabic strings yet, so on an Arabic phone the English labels sit in a
 * mirrored layout, and with the layout's direction their punctuation jumped to the wrong end
 * ("?What should we build"). Arabic text still runs right to left.
 */
val Typography = BaseTypography.run {
    fun TextStyle.content() = copy(textDirection = TextDirection.Content)
    copy(
        displayLarge = displayLarge.content(),
        displayMedium = displayMedium.content(),
        displaySmall = displaySmall.content(),
        headlineLarge = headlineLarge.content(),
        headlineMedium = headlineMedium.content(),
        headlineSmall = headlineSmall.content(),
        titleLarge = titleLarge.content(),
        titleMedium = titleMedium.content(),
        titleSmall = titleSmall.content(),
        bodyLarge = bodyLarge.content(),
        bodyMedium = bodyMedium.content(),
        bodySmall = bodySmall.content(),
        labelLarge = labelLarge.content(),
        labelMedium = labelMedium.content(),
        labelSmall = labelSmall.content()
    )
}

/**
 * Arabic letters in the bundled Naskh with Roboto's line metrics (see scripts/make_arabic_ui_font.py);
 * Latin letters, digits and punctuation are not in those files, so they keep the system font.
 */
private val ArabicUiFontFamily = FontFamily(
    Font(R.font.arabic_ui_regular, FontWeight.Normal),
    Font(R.font.arabic_ui_medium, FontWeight.Medium),
    Font(R.font.arabic_ui_bold, FontWeight.Bold)
)

/**
 * The UI in Arabic. Since Android 15 the system draws Arabic in the tall "elegant" Naskh (about 1.7 em
 * per line against Roboto's 1.17), and Compose grows every line to the font it draws with, so Arabic
 * rows, cards and buttons came out about 45% taller and pushed screens past the bottom edge. With the
 * bundled font an Arabic line is exactly as tall as an English one.
 */
val ArabicTypography = Typography.run {
    fun TextStyle.arabic() = copy(fontFamily = ArabicUiFontFamily)
    copy(
        displayLarge = displayLarge.arabic(),
        displayMedium = displayMedium.arabic(),
        displaySmall = displaySmall.arabic(),
        headlineLarge = headlineLarge.arabic(),
        headlineMedium = headlineMedium.arabic(),
        headlineSmall = headlineSmall.arabic(),
        titleLarge = titleLarge.arabic(),
        titleMedium = titleMedium.arabic(),
        titleSmall = titleSmall.arabic(),
        bodyLarge = bodyLarge.arabic(),
        bodyMedium = bodyMedium.arabic(),
        bodySmall = bodySmall.arabic(),
        labelLarge = labelLarge.arabic(),
        labelMedium = labelMedium.arabic(),
        labelSmall = labelSmall.arabic()
    )
}
