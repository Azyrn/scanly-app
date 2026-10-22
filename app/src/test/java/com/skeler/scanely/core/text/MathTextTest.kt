package com.skeler.scanely.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class MathTextTest {

    @Test
    fun `fractions and roots read as plain math`() {
        assertEquals("a/b", MathText.prettify("\\frac{a}{b}"))
        assertEquals("(x+1)/2", MathText.prettify("\\frac{x+1}{2}"))
        assertEquals("√x", MathText.prettify("\\sqrt{x}"))
    }

    @Test
    fun `nested fractions unwrap inside out`() {
        assertEquals("(a/b)/c", MathText.prettify("\\frac{\\frac{a}{b}}{c}"))
    }

    @Test
    fun `symbols map to unicode`() {
        assertEquals("a × b ≤ π", MathText.prettify("a \\times b \\leq \\pi"))
        assertEquals("x ≠ ∞", MathText.prettify("x \\neq \\infty"))
        assertEquals("Σ α + Δ", MathText.prettify("\\sum \\alpha + \\Delta"))
    }

    @Test
    fun `scripts convert when every character maps`() {
        assertEquals("E = mc²", MathText.prettify("E = mc^2"))
        assertEquals("x₁ + x₂", MathText.prettify("x_1 + x_2"))
        assertEquals("x^(2y)", MathText.prettify("x^{2y}"))
    }

    @Test
    fun `unknown commands keep their names`() {
        assertEquals("sin θ + cos θ", MathText.prettify("\\sin \\theta + \\cos \\theta"))
    }

    @Test
    fun `text wrappers unwrap`() {
        assertEquals("speed = 3 m/s", MathText.prettify("\\text{speed} = 3 \\text{m/s}"))
    }
}
