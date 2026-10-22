package com.skeler.scanely.core.transcribe

import android.content.Context
import com.skeler.scanely.core.ai.GenerativeAiService
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.network.TranscriptDto
import com.skeler.scanely.core.network.TranscriptSegmentDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * M10: once xAI has answered, the credits are spent. A transcript that then fails to save must not
 * crash the runner or leave it spinning, and must be savable again from memory — sending the audio
 * again would bill it a second time.
 *
 * The failure is real: the store's directory is blocked by a plain file, so the write throws.
 */
class TranscriptSaveFailureTest {

    private val filesDir: File = Files.createTempDirectory("transcripts-test").toFile()
    private val blocker = File(filesDir, "transcripts").apply { writeText("not a directory") }
    private val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(filesDir) }
    private val store = TranscriptStore(context)
    private val service = mock(GenerativeAiService::class.java)
    private val runner = TranscriptionRunner(
        mock(AudioPreparer::class.java),
        service,
        store,
        mock(EntitlementRepository::class.java)
    )

    private val recording = File(filesDir, "take.m4a").apply { writeBytes(ByteArray(16)) }
    private val upload = File(filesDir, "upload.m4a").apply { writeBytes(ByteArray(16)) }
    private val transcript = TranscriptDto(segments = listOf(TranscriptSegmentDto(text = "paid for")))

    @After fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `a transcript that cannot be saved is held, not lost, and saves again without the network`() = runBlocking {
        runner.keep(TranscriptionRunner.Source.Recorded(recording, "Meeting"), transcript, upload)

        val held = runner.state.value
        assertTrue("held for a retry: $held", held is TranscriptionRunner.State.SaveFailed)
        val message = (held as TranscriptionRunner.State.SaveFailed).error.message
        assertTrue(message, message.contains("not be charged again"))
        assertTrue("the recording is kept until the transcript is safe", recording.exists())

        blocker.delete()
        runner.retrySave()?.join()

        val done = runner.state.value
        assertTrue("saved on retry: $done", done is TranscriptionRunner.State.Done)
        val saved = store.load((done as TranscriptionRunner.State.Done).transcriptId)
        assertNotNull(saved)
        assertEquals("Meeting", saved!!.title)
        verifyNoInteractions(service)
    }
}
