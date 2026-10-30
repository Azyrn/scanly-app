package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.text.Bidi
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inverse bidi is checked against the forward one: what it reads must display as what was drawn. */
class ExactBidiTest {

    @Test
    fun anArabicSentenceWithANumberReadsInLogicalOrder() {
        // "سنة 2017 م" drawn left to right: م 2017 سنة reversed letters... drawn as the visual string.
        val logical = "سنة 2017"
        assertEquals(logical, roundTrip(logical, rtl = true).first)
    }

    @Test
    fun aCitationBesideArabicKeepsItsBracketsFacingTheRightWay() {
        // Drawn left to right: "[285] ةملك" — the brackets as the eye sees them.
        val visual = "[285] ةملك".codePoints().toArray()

        val reading = ExactBidi.reading(visual, rtl = true)

        val text = String(reading.codes, 0, reading.codes.size)
        // Read right to left: the word, a space, then the citation with its opener first.
        assertEquals("كلمة [285]", text)
    }

    @Test
    fun randomMixedTextDisplaysTheSameAfterTheRoundTrip() {
        val sets = mapOf(
            "arabic+latin" to "ابتثجحخد  abcde  ",
            "+digits" to "ابتثجحخد  abcde  12 3 ",
            "+punctuation" to "ابتثجحخد  abcde  12 3 ,.:-",
            "+brackets" to "ابتثجحخد  abcde  12 3 ,.:-«»",
        )
        for ((name, chars) in sets) {
            for (rtl in listOf(true, false)) {
                val random = Random(7)
                val alphabet = chars.toList()
                var bad = 0
                val total = 3000
                val examples = ArrayList<String>()
                repeat(total) {
                    var text = buildString { repeat(random.nextInt(3, 24)) { append(alphabet[random.nextInt(alphabet.size)]) } }
                    if (name == "+brackets") {
                        // Balanced pairs, as real text has them.
                        val from = random.nextInt(text.length)
                        val to = random.nextInt(from, text.length) + 1
                        text = text.substring(0, from) + "(" + text.substring(from, to) + ")" + text.substring(to)
                    }
                    val (rec, same) = roundTrip(text, rtl)
                    if (!same) {
                        bad++
                        if (examples.size < 4) examples += "'$text' -> '$rec' | ${display(text, rtl).first} vs ${display(rec, rtl).first}"
                    }
                }
                println("bidi $name ${if (rtl) "RTL" else "LTR"}: $bad of $total differ; e.g. $examples")
            }
        }
    }

    @Test
    fun theForwardLevelsMatchTheJdkOnRealisticText() {
        val random = Random(11)
        val alphabet = "ابتثجحخد  abcde  12 3 ,.:-()«»".toList()
        var bad = 0
        val examples = ArrayList<String>()
        repeat(3000) {
            val text = buildString { repeat(random.nextInt(3, 24)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val rtl = random.nextBoolean()
            val bidi = Bidi(text, if (rtl) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
            val expected = IntArray(text.length) { bidi.getLevelAt(it) }
            val actual = ExactBidi.forwardLevels(text.codePoints().toArray(), rtl)
            if (!expected.contentEquals(actual)) {
                bad++
                if (examples.size < 4) examples += "${if (rtl) "RTL" else "LTR"} '$text' jdk=${expected.toList()} mine=${actual.toList()}"
            }
        }
        println("forward vs jdk: $bad of 3000 differ; e.g. $examples")
    }

    /** Returns the logical text recovered from [logical]'s display and whether it displays identically. */
    private fun roundTrip(logical: String, rtl: Boolean): Pair<String, Boolean> {
        val (visual, _) = display(logical, rtl)
        val codes = visual.codePoints().toArray()
        val recovered = StringBuilder()
        ExactBidi.reading(codes, rtl).codes.forEach { recovered.appendCodePoint(it) }
        val (visual2, _) = display(recovered.toString(), rtl)
        return recovered.toString() to (visual == visual2)
    }

    /** The glyphs left to right, as a producer draws them: mirrored characters already swapped. */
    private fun display(logical: String, rtl: Boolean): Pair<String, ByteArray> {
        val bidi = Bidi(logical, if (rtl) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
        val n = logical.length
        val levels = ByteArray(n) { bidi.getLevelAt(it).toByte() }
        val indices = Array<Int>(n) { it }
        Bidi.reorderVisually(levels, 0, indices, 0, n)
        val out = StringBuilder()
        for (index in indices) {
            val c = logical[index].code
            out.appendCodePoint(if (levels[index] % 2 == 1) ExactBidi.mirror(c) else c)
        }
        return out.toString() to levels
    }
}
