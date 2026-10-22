package com.skeler.scanely.docchat.presentation.screen

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class ChatAgeTest {
    private val zoneId = ZoneId.of("Africa/Casablanca")
    private val zone = TimeZone.getTimeZone(zoneId)
    private val today = LocalDate.of(2026, 10, 2)
    private val now = at(today, LocalTime.of(15, 0))

    private fun at(date: LocalDate, time: LocalTime = LocalTime.NOON) =
        date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()

    @Test
    fun `groups by calendar day in the phone's zone, not by 24-hour spans`() {
        assertEquals(ChatAge.TODAY, chatAge(at(today, LocalTime.of(0, 5)), now, zone))
        assertEquals(ChatAge.YESTERDAY, chatAge(at(today.minusDays(1), LocalTime.of(23, 55)), now, zone))
        assertEquals(ChatAge.WEEK, chatAge(at(today.minusDays(2)), now, zone))
        assertEquals(ChatAge.WEEK, chatAge(at(today.minusDays(6)), now, zone))
        assertEquals(ChatAge.OLDER, chatAge(at(today.minusDays(7)), now, zone))
    }

    @Test
    fun `a clock slightly ahead of the phone still counts as today`() {
        assertEquals(ChatAge.TODAY, chatAge(at(today.plusDays(1), LocalTime.of(0, 1)), now, zone))
    }
}
