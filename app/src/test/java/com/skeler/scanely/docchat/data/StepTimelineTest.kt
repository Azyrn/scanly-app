package com.skeler.scanely.docchat.data

import com.skeler.scanely.docchat.presentation.screen.stepDurations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepTimelineTest {
    @Test
    fun `a step lasts until the next one, the live one until now, a timed one its own length`() {
        val steps = listOf(
            StepRecord(DesignStep.EXTRACTING_TEXT, 1_000, ms = 4_000),
            StepRecord(DesignStep.READING_FILE, 1_000),
            StepRecord(DesignStep.PLANNING, 3_000),
            StepRecord(DesignStep.WRITING, 10_000)
        )
        assertEquals(listOf(4_000L, 2_000L, 7_000L, 5_000L), stepDurations(steps, liveUntil = 15_000))
        assertEquals(0L, stepDurations(steps, liveUntil = null).last())
    }

    @Test
    fun `clock skew never shows a negative duration`() {
        val steps = listOf(StepRecord(DesignStep.PLANNING, 5_000), StepRecord(DesignStep.WRITING, 4_000))
        assertEquals(listOf(0L, 0L), stepDurations(steps, liveUntil = null))
    }

    @Test
    fun `the worker's step ids map onto the chat's steps, and unknown ones are ignored`() {
        assertEquals(DesignStep.BUILDING_PDF, designStepOf("building"))
        assertEquals(DesignStep.FIXING_LAYOUT, designStepOf("fixing"))
        assertNull(designStepOf("reviewing"))
    }

    @Test
    fun `a delivered reply ends its timeline with Done exactly once`() {
        val working = WorkingBody(1, listOf(StepRecord(DesignStep.PLANNING, 1)))
        assertEquals(DesignStep.DONE, working.finishedSteps(now = 9).last().step)
        val done = WorkingBody(1, listOf(StepRecord(DesignStep.PLANNING, 1), StepRecord(DesignStep.DONE, 5)))
        assertEquals(done.steps, done.finishedSteps(now = 9))
    }
}
