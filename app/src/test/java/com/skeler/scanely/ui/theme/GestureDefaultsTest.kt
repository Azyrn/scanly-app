package com.skeler.scanely.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDefaultsTest {
    private val dimension = 1000f

    @Test
    fun rubberBandStartsAtTheFactorAndStiffens() {
        val first = RubberBand.rubberBand(1f, dimension)
        assertEquals(GestureDefaults.RUBBER_BAND, first, 0.001f)
        val near = RubberBand.rubberBand(100f, dimension) / 100f
        val far = RubberBand.rubberBand(2000f, dimension) / 2000f
        assertTrue("resistance grows with distance", far < near)
        assertTrue("never reaches the dimension", RubberBand.rubberBand(1e7f, dimension) < dimension)
    }

    @Test
    fun rubberBandIsSymmetric() {
        assertEquals(-RubberBand.rubberBand(300f, dimension), RubberBand.rubberBand(-300f, dimension), 0.0001f)
    }

    @Test
    fun bandedRoundTripsSoACaughtElementDoesNotJump() {
        for (raw in listOf(-800f, -10f, 0f, 250f, 500f, 520f, 1900f)) {
            val shown = RubberBand.banded(raw, 0f, 500f, dimension)
            assertEquals(raw, RubberBand.unbanded(shown, 0f, 500f, dimension), 0.05f)
        }
    }

    @Test
    fun insideTheBoundsIsOneToOne() {
        assertEquals(321f, RubberBand.banded(321f, 0f, 500f, dimension), 0f)
    }
}
