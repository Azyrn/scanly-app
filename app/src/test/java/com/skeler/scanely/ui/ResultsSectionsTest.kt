package com.skeler.scanely.ui

import com.skeler.scanely.ui.screens.resultsSections
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsSectionsTest {

    @Test
    fun `barcode without text still renders a section`() {
        val sections = resultsSections(hasBarcodes = true, hasText = false)

        assertTrue(sections.showBarcodes)
        assertTrue(sections.hasVisibleContent)
    }

    @Test
    fun `text without barcode renders the text section only`() {
        val sections = resultsSections(hasBarcodes = false, hasText = true)

        assertTrue(sections.showText)
        assertFalse(sections.showBarcodes)
    }

    @Test
    fun `barcode and text both render`() {
        val sections = resultsSections(hasBarcodes = true, hasText = true)

        assertTrue(sections.showBarcodes)
        assertTrue(sections.showText)
    }

    @Test
    fun `every non-empty outcome shows something`() {
        val outcomes = listOf(true, false).flatMap { barcodes ->
            listOf(true, false).map { text -> barcodes to text }
        }

        outcomes.filter { (barcodes, text) -> barcodes || text }
            .forEach { (barcodes, text) ->
                assertTrue(
                    "hasBarcodes=$barcodes hasText=$text rendered nothing",
                    resultsSections(barcodes, text).hasVisibleContent
                )
            }
    }

    @Test
    fun `empty outcome shows nothing so the screen falls back to its empty state`() {
        assertFalse(resultsSections(hasBarcodes = false, hasText = false).hasVisibleContent)
    }
}
