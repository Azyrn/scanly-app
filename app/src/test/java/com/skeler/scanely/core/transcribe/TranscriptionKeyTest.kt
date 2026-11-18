package com.skeler.scanely.core.transcribe

import android.content.Context
import android.net.Uri
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.GenerativeAiService
import com.skeler.scanely.core.ai.TranscriptionResult
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.testing.EnglishStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import java.io.File
import java.nio.file.Files

/**
 * N3: the Worker finishes and bills a transcription even when the phone is gone (routes/transcribe.ts
 * keeps the work alive and stores the answer under the idempotency key for 24 hours), and replays
 * that answer to the same key without billing again. The key was a fresh random one per attempt,
 * held only in memory: a dropped connection or a killed app, then Try again, uploaded the same
 * recording under a new key and paid for it twice. The same audio with the same options is now the
 * same key, in any run of the app.
 */
class TranscriptionKeyTest {
    private val dir: File = Files.createTempDirectory("transcribe-key").toFile()
    private val audio = File(dir, "upload.m4a").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
    private val keys = mutableListOf<String>()

    /** Like the real preparer: a fresh copy of the recording each time (a failed attempt deletes its copy). */
    private val preparer = mock(AudioPreparer::class.java) { call: InvocationOnMock ->
        if (call.method.name != "prepare") return@mock null
        val copy = File.createTempFile("prepared", ".m4a", dir).also { audio.copyTo(it, overwrite = true) }
        AudioPreparer.PreparedAudio(copy, "audio/mp4", 60_000, transcoded = false)
    }
    private val service = mock(GenerativeAiService::class.java) { call: InvocationOnMock ->
        if (call.method.name != "transcribe") return@mock null
        keys += call.arguments[3] as String
        TranscriptionResult.Failure(AiResult.Error("The connection was lost."))
    }
    private val entitlements = mock(EntitlementRepository::class.java) { call: InvocationOnMock ->
        if (call.method.name == "getEntitlement") MutableStateFlow(null) else null
    }

    private fun runner() = TranscriptionRunner(
        EnglishStrings.context(), preparer, service, TranscriptStore(mock(Context::class.java)), entitlements
    )

    private val source = TranscriptionRunner.Source.Picked(mock(Uri::class.java), "Meeting")

    private fun TranscriptionRunner.once(options: TranscribeOptions = TranscribeOptions()) = runBlocking {
        start(source, options)
        withTimeout(10_000) { state.first { it is TranscriptionRunner.State.Failed } }
        acknowledge()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun `trying the same recording again reuses its key, in this run and after the app restarts`() {
        val runner = runner()
        runner.once()
        runner.once()
        runner().once()
        assertEquals(3, keys.size)
        assertEquals("one key for one recording: $keys", 1, keys.toSet().size)
    }

    @Test fun `other options or other audio are a different request`() {
        runner().once()
        runner().once(TranscribeOptions(language = "en"))
        audio.writeBytes(ByteArray(4096) { 7 })
        runner().once()
        assertEquals(3, keys.toSet().size)
        assertNotEquals(keys[0], keys[2])
    }
}
