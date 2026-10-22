package com.skeler.scanely.ui.screens

/**
 * Which content sections the results screen renders.
 *
 * Every scan outcome that is not empty must light up at least one section, or the screen shows a
 * header over blank space and the scan looks like it failed. [hasVisibleContent] states that
 * invariant so it can be tested rather than assumed.
 */
data class ResultsSections(
    val showBarcodes: Boolean,
    val showText: Boolean
) {
    val hasVisibleContent: Boolean get() = showBarcodes || showText
}

fun resultsSections(hasBarcodes: Boolean, hasText: Boolean): ResultsSections =
    ResultsSections(showBarcodes = hasBarcodes, showText = hasText)
