package com.skeler.scanely.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holder outlives the composition on a configuration change; these pin what it hands back
 * when the screen asks again afterwards.
 */
class ExtractedTextHolderTest {

    @Test
    fun `asking again for the same text returns the edit in progress`() {
        val holder = ExtractedTextHolder()
        val before = holder.stateFor("Invoice 42")
        before.editing = true
        before.draft = "Invoice 42, corrected"

        val after = holder.stateFor(String(charArrayOf(*"Invoice 42".toCharArray())))

        assertSame(before, after)
        assertTrue(after.editing)
        assertEquals("Invoice 42, corrected", after.draft)
    }

    @Test
    fun `a new result starts a fresh state`() {
        val holder = ExtractedTextHolder()
        holder.stateFor("first scan").apply {
            editing = true
            draft = "edited"
        }

        val next = holder.stateFor("second scan")

        assertFalse(next.editing)
        assertEquals("second scan", next.draft)
    }
}
