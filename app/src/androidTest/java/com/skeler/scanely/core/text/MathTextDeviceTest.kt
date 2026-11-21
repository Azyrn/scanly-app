package com.skeler.scanely.core.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on the phone because Android's ICU regex engine is stricter than the JVM's: a bare `}`
 * compiles in unit tests and crashes the app when an AI Scan result contains an equation.
 */
@RunWith(AndroidJUnit4::class)
class MathTextDeviceTest {

    @Test
    fun patternsCompileAndConvertOnAndroid() {
        assertEquals("a/b", MathText.prettify("\\frac{a}{b}"))
        assertEquals("√x", MathText.prettify("\\sqrt{x}"))
        assertEquals("x^(2y)", MathText.prettify("x^{2y}"))
        assertEquals("v = d/t", MathText.prettify("\\text{v} = \\frac{d}{t}"))
    }
}
