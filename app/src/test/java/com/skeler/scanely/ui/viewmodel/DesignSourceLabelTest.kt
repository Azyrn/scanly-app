package com.skeler.scanely.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DesignSourceLabelTest {

    private val now = at(2026, Calendar.SEPTEMBER, 3, hour = 14)

    @Test
    fun `names the scan by its first meaningful line`() {
        assertEquals("Today · Invoice 402", designSourceLabel("Invoice 402\nTotal 90", now, now))
    }

    @Test
    fun `strips markdown the OCR left on the first line`() {
        assertEquals("Today · Bank statement", designSourceLabel("## Bank statement", now, now))
    }

    @Test
    fun `skips lines too short to name anything`() {
        assertEquals("Today · Rent agreement", designSourceLabel("|\n-\nRent agreement", now, now))
    }

    @Test
    fun `collapses whitespace so a chip stays one line`() {
        assertEquals("Today · A B", designSourceLabel("A\tB", now, now))
    }

    @Test
    fun `truncates a long line rather than letting the chip run off screen`() {
        val label = designSourceLabel("x".repeat(80), now, now)
        assertTrue(label, label.endsWith("…"))
        assertTrue(label, label.length < 40)
    }

    @Test
    fun `falls back to the date when there is no usable line`() {
        assertEquals("Today", designSourceLabel("|\n-\n#", now, now))
    }

    @Test
    fun `yesterday is named, not dated`() {
        val yesterday = at(2026, Calendar.SEPTEMBER, 2, hour = 23)
        assertEquals("Yesterday · Receipt", designSourceLabel("Receipt", yesterday, now))
    }

    @Test
    fun `an hour earlier on the same day is still today`() {
        val earlier = at(2026, Calendar.SEPTEMBER, 3, hour = 1)
        assertEquals("Today · Receipt", designSourceLabel("Receipt", earlier, now))
    }

    @Test
    fun `an older scan in this year carries a day and month`() {
        val august = at(2026, Calendar.AUGUST, 30, hour = 10)
        val label = designSourceLabel("Receipt", august, now)
        assertTrue(label, label.startsWith("30 "))
        assertTrue(label, label.endsWith("· Receipt"))
    }

    @Test
    fun `a scan from another year carries the year`() {
        val lastYear = at(2025, Calendar.SEPTEMBER, 3, hour = 10)
        assertTrue(designSourceLabel("Receipt", lastYear, now).contains("2025"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
