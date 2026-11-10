package com.skeler.scanely.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 3.3 release rule: velocity or distance commits, anything else springs back. */
class ModeReleaseTargetTest {
    private val flick = 365f
    private val last = 2

    @Test
    fun aShortFastFlickMovesOneMode() {
        assertEquals(1, modeReleaseTarget(start = 0, position = 0.1f, velocity = 1300f, flick = flick, lastIndex = last))
        assertEquals(0, modeReleaseTarget(start = 1, position = 0.9f, velocity = -1300f, flick = flick, lastIndex = last))
    }

    @Test
    fun aSlowDragCommitsAtFortyFivePercent() {
        assertEquals(1, modeReleaseTarget(start = 0, position = 0.46f, velocity = 0f, flick = flick, lastIndex = last))
        assertEquals(0, modeReleaseTarget(start = 0, position = 0.44f, velocity = 0f, flick = flick, lastIndex = last))
        assertEquals(1, modeReleaseTarget(start = 2, position = 1.5f, velocity = 0f, flick = flick, lastIndex = last))
    }

    @Test
    fun aLongSlowDragCanCrossTwoModes() {
        assertEquals(2, modeReleaseTarget(start = 0, position = 1.5f, velocity = 0f, flick = flick, lastIndex = last))
    }

    @Test
    fun aFlickBackAfterDraggingForwardReturns() {
        assertEquals(0, modeReleaseTarget(start = 0, position = 0.7f, velocity = -800f, flick = flick, lastIndex = last))
    }

    @Test
    fun theTargetStaysOnARealMode() {
        assertEquals(2, modeReleaseTarget(start = 2, position = 2.2f, velocity = 2000f, flick = flick, lastIndex = last))
        assertEquals(0, modeReleaseTarget(start = 0, position = -0.3f, velocity = -2000f, flick = flick, lastIndex = last))
    }
}
