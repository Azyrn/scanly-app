package com.skeler.scanely.core.transcribe

import com.skeler.scanely.core.network.TranscriptDto
import com.skeler.scanely.core.network.TranscriptSegmentDto
import com.skeler.scanely.ui.viewmodel.TranscriptViewModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer

/**
 * `contracts/transcribe-response.json` is the Worker's real output for a recorded Grok Voice
 * Transcribe response (worker/test/fixtures/stt/r1_clean_en_fmt.json); the Worker's own test fails if
 * its output drifts from it. Everything here is decoded from that file, not from a hand-made shape.
 */
class TranscriptFormatsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun contract(): TranscriptDto = json.decodeFromString(
        TranscriptDto.serializer(),
        checkNotNull(javaClass.getResource("/transcribe-response.json")).readText()
    )

    private fun transcript(names: Map<Int, String> = emptyMap()) = StoredTranscript(
        id = "00000000-0000-0000-0000-000000000001",
        title = "Invoice call",
        createdAt = 0,
        transcript = contract(),
        speakerNames = names
    ).toTranscript()

    @Test
    fun `the contract decodes into speaker-labelled timed segments`() {
        val t = transcript()
        assertEquals(3, t.speakerCount)
        assertEquals(listOf(0, 1, 0, 2, 0), t.segments.map { it.speaker })
        assertEquals(1, t.creditsUsed)
        assertEquals(41_313L, t.durationMs)
        assertTrue(t.formatted)
    }

    @Test
    fun `every word range covers exactly one word of its segment`() {
        for (segment in transcript().segments) {
            var previousEnd = 0
            for (word in segment.words) {
                val text = segment.text.substring(word.from, word.to)
                assertEquals("no spaces inside a word: '$text'", text.trim(), text)
                assertFalse(text.contains(' '))
                assertTrue(word.from >= previousEnd)
                assertTrue(word.endMs >= word.startMs && word.startMs >= segment.startMs)
                previousEnd = word.to
            }
            assertEquals(segment.text.length, segment.words.last().to)
        }
    }

    @Test
    fun `a formatted amount is one seekable word timed over what was said`() {
        val first = transcript().segments.first()
        val amount = first.words.single { first.text.substring(it.from, it.to) == "$342.50" }
        // "three hundred forty-two dollars and fifty cents" took well over a second to say.
        assertTrue(amount.endMs - amount.startMs > 1_000)
    }

    @Test
    fun `malformed word triples are clamped, never thrown`() {
        val dto = TranscriptDto(
            segments = listOf(
                TranscriptSegmentDto(
                    speaker = 0, start = 1.0, end = 2.0, text = "hello world",
                    words = listOf(listOf(0, 100, 0), listOf(50, 10, 999), listOf(1, 2), listOf(-5, 5, 6))
                )
            )
        )
        val words = dto.toSegments().single().words
        assertTrue(words.all { it.from in 0..11 && it.to in it.from..11 && it.endMs >= it.startMs })
    }

    @Test
    fun `plain text opens each paragraph with its speaker and time`() {
        val text = TranscriptFormats.plainText(transcript(mapOf(0 to "Sara", 1 to "Omar")))
        assertTrue(text.startsWith("Sara · 0:00\nHi Sebastian, it's Sarah from Scanly."))
        assertTrue(text.contains("\n\nOmar · 0:11\nOkay, got it."))
        assertTrue(text.contains("Speaker 3 · 0:33\nD'accord"))
    }

    @Test
    fun `markdown escapes speech that would read as formatting`() {
        val md = TranscriptFormats.markdown(transcript())
        assertTrue(md.startsWith("# Invoice call\n\n*0:41 · "))
        assertTrue(md.contains("**Speaker 1** · 0:00"))
        val stray = TranscriptDto(segments = listOf(TranscriptSegmentDto(text = "5 * 3 = 15 #tag")))
        val escaped = TranscriptFormats.markdown(
            StoredTranscript("00000000-0000-0000-0000-000000000002", "t", 0, stray).toTranscript()
        )
        assertTrue(escaped.contains("5 \\* 3 = 15 \\#tag"))
    }

    @Test
    fun `srt cues are short, ordered, and never overlap`() {
        val srt = TranscriptFormats.srt(transcript())
        val blocks = srt.trim().split("\n\n")
        assertTrue(blocks.size > 5)
        assertTrue(blocks.first().startsWith("1\n00:00:00,080 --> "))
        val cues = SubtitleCues.cut(transcript())
        cues.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        cues.forEach { cue ->
            assertTrue("cue too long: ${cue.text}", cue.text.length <= 84 || !cue.text.contains(' '))
            assertTrue(cue.endMs - cue.startMs <= 7_000)
        }
        // Every cue line fits a subtitle row after wrapping.
        blocks.flatMap { it.lines().drop(2) }.forEach { assertTrue(it, it.length <= 42 + "Speaker 1: ".length) }
    }

    @Test
    fun `webvtt names speakers with voice spans`() {
        val vtt = TranscriptFormats.vtt(transcript(mapOf(1 to "Omar")))
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.080 --> "))
        assertTrue(vtt.contains("<v Speaker 1>Hi Sebastian"))
        assertTrue(vtt.contains("<v Omar>Okay, got it."))
    }

    @Test
    fun `clock switches to hours only when it needs them`() {
        assertEquals("0:00", TranscriptFormats.clock(0))
        assertEquals("0:41", TranscriptFormats.clock(41_313))
        assertEquals("59:59", TranscriptFormats.clock(3_599_999))
        assertEquals("1:00:00", TranscriptFormats.clock(3_600_000))
    }

    @Test
    fun `the playhead finds the spoken word by binary search`() {
        val segments = transcript().segments
        assertEquals(-1 to -1, TranscriptViewModel.locate(segments, 0))
        val (s, w) = TranscriptViewModel.locate(segments, 12_500)
        assertEquals(1, s)
        val seg = segments[s]
        assertTrue(seg.words[w].startMs <= 12_500)
        assertTrue(seg.words.getOrNull(w + 1)?.startMs?.let { it > 12_500 } ?: true)
        // In the silence between turns nothing is highlighted.
        val gap = segments[0].endMs + 600
        assertEquals(-1, TranscriptViewModel.locate(segments, gap).second)
    }

    @Test
    fun `auto-detect with formatting is quoted as two passes`() {
        assertEquals(2, TranscribeOptions(language = null, formatNumbers = true).passes)
        assertEquals(1, TranscribeOptions(language = null, formatNumbers = false).passes)
        assertEquals(1, TranscribeOptions(language = "en", formatNumbers = true).passes)
    }

    @Test
    fun `key terms are cleaned the way the server cleans them`() {
        assertEquals("Mistral OCR", TranscriptionPrefs.cleanKeyTerm("  Mistral\t\nOCR "))
        assertNull(TranscriptionPrefs.cleanKeyTerm("   "))
        assertNull(TranscriptionPrefs.cleanKeyTerm("x".repeat(51)))
        assertEquals("x".repeat(50), TranscriptionPrefs.cleanKeyTerm("x".repeat(50)))
    }

    @Test
    fun `the bitrate fits the plan's longest recording under its upload cap`() {
        val mb = 1024L * 1024
        // Free: 10 minutes in 5 MB is roomy; speech never needs more than 32 kbps.
        assertEquals(32_000, AudioPreparer.targetBitrate(10 * 60_000L, 5 * mb))
        // Pro: three hours must fit 30 MB.
        val pro = AudioPreparer.targetBitrate(180 * 60_000L, 30 * mb)
        assertTrue(pro in 16_000..32_000)
        assertTrue(pro / 8.0 * 180 * 60 < 30 * mb)
        // Never below the floor where AAC-LC smears consonants.
        assertEquals(16_000, AudioPreparer.targetBitrate(24 * 3_600_000L, 5 * mb))
    }

    @Test
    fun `only containers the worker can measure pass through untouched`() {
        assertEquals("m4a", AudioPreparer.passThroughExtension("audio/mp4"))
        assertEquals("mp3", AudioPreparer.passThroughExtension("audio/mpeg"))
        assertEquals("aac", AudioPreparer.passThroughExtension("audio/aac-adts"))
        assertEquals("ogg", AudioPreparer.passThroughExtension("audio/ogg"))
        assertNull(AudioPreparer.passThroughExtension("audio/amr"))
        assertNull(AudioPreparer.passThroughExtension("audio/3gpp"))
        assertNull(AudioPreparer.passThroughExtension("video/mp4"))
        assertNull(AudioPreparer.passThroughExtension(null))
    }

    @Test
    fun `resampling to 16 kHz keeps duration and averages each window`() {
        for (rate in listOf(48_000, 44_100, 22_050, 32_000)) {
            val resampler = MonoResampler(rate, 16_000)
            val out = ShortQueue()
            // One second of input fed in awkward chunk sizes, as a decoder delivers it.
            val second = ShortArray(rate) { 1000 }
            var fed = 0
            while (fed < rate) {
                val n = minOf(1_237, rate - fed)
                resampler.process(second.copyOfRange(fed, fed + n), out)
                fed += n
            }
            assertTrue("$rate Hz gave ${out.size} samples", out.size in 15_999..16_000)
            assertTrue(out.toArray().all { it.toInt() == 1000 })
        }
        // Equal rates pass straight through.
        val same = ShortQueue()
        MonoResampler(16_000, 16_000).process(shortArrayOf(1, 2, 3), same)
        assertEquals(listOf<Short>(1, 2, 3), same.toArray().toList())
    }

    @Test
    fun `the sample queue grows and drains without losing order`() {
        val queue = ShortQueue()
        val values = ShortArray(50_000) { (it % 30_000).toShort() }
        queue.addAll(values.copyOfRange(0, 20_000))
        val first = ShortBuffer.allocate(15_000)
        queue.drainInto(first, 15_000)
        queue.addAll(values.copyOfRange(20_000, 50_000))
        repeat(10) { queue.add(7) }
        assertEquals(35_010, queue.size)
        val rest = queue.toArray()
        assertEquals(values.copyOfRange(15_000, 50_000).toList(), rest.take(35_000))
        assertEquals(List(10) { 7.toShort() }, rest.takeLast(10))
    }
}
