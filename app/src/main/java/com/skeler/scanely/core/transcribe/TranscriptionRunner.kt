package com.skeler.scanely.core.transcribe

import android.net.Uri
import com.skeler.scanely.core.ai.AiEvent
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.AiStage
import com.skeler.scanely.core.ai.GenerativeAiService
import com.skeler.scanely.core.ai.TranscriptionResult
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.network.TranscriptDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one transcription from picked file to saved transcript, in the application's scope.
 *
 * Not in a ViewModel on purpose. Once the upload has gone, the credits are spent whether or not
 * anyone is watching, so leaving the screen must not cancel the job and drop a transcript that was
 * paid for. The runner finishes, saves to [TranscriptStore], and the screen — whenever it is next
 * open — shows the result.
 */
@Singleton
class TranscriptionRunner @Inject constructor(
    private val preparer: AudioPreparer,
    private val service: GenerativeAiService,
    private val store: TranscriptStore,
    private val entitlements: EntitlementRepository
) {
    sealed interface Source {
        val title: String
        data class Picked(val uri: Uri, override val title: String) : Source
        data class Recorded(val file: File, override val title: String) : Source
    }

    enum class Phase { PREPARING, UPLOADING, TRANSCRIBING }

    sealed interface State {
        data object Idle : State
        data class Working(
            val phase: Phase,
            /** 0..1 for preparing and uploading; null while waiting on the transcriber. */
            val progress: Float?,
            val detail: String? = null,
            /** False once the upload has gone: from then on the credits are being spent. */
            val cancellable: Boolean = true
        ) : State
        data class Done(val transcriptId: String) : State
        data class Failed(val error: AiResult.Error) : State

        /**
         * Transcribed and paid for, but the save failed. The transcript is held in memory for
         * [retrySave]; transcribing again would upload and bill the audio a second time.
         */
        data class SaveFailed(val error: AiResult.Error) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    private class Unsaved(val source: Source, val transcript: TranscriptDto, val audio: File)
    private var unsaved: Unsaved? = null

    fun start(source: Source, options: TranscribeOptions) {
        // A paid transcript waiting on a save is saved or discarded first, never billed again.
        if (job?.isActive == true || unsaved != null) return
        job = scope.launch { run(source, options) }
    }

    /** Only honoured before the upload finishes; after that the work is paid for. */
    fun cancel() {
        val working = _state.value as? State.Working ?: return
        if (!working.cancellable) return
        job?.cancel()
        _state.value = State.Idle
    }

    /** Clears a finished or failed state once the screen has acted on it. */
    fun acknowledge() {
        when (_state.value) {
            is State.Done, is State.Failed -> _state.value = State.Idle
            // Dismissing gives up the transcript; the audio stays for a fresh attempt.
            is State.SaveFailed -> {
                unsaved = null
                _state.value = State.Idle
            }
            else -> Unit
        }
    }

    /** Saves the held transcript again. No network: it was transcribed and billed already. */
    fun retrySave(): Job? {
        val pending = unsaved ?: return null
        if (job?.isActive == true) return null
        return scope.launch { keep(pending.source, pending.transcript, pending.audio) }.also { job = it }
    }

    private suspend fun run(source: Source, options: TranscribeOptions) {
        val me = entitlements.entitlement.value
        val limits = Limits(
            maxUpload = me?.maxUploadBytes ?: DEFAULT_MAX_UPLOAD,
            maxAudioMs = (me?.maxAudioMinutes ?: DEFAULT_MAX_MINUTES) * MS_PER_MINUTE
        )
        preparer.clearWorkDir()

        val attempt = attempt(source, options, limits, forceTranscode = false)
            ?.let { first ->
                // A file passed through untouched that the Worker cannot measure — an unusual MP4
                // layout, say — gets one more chance as a re-encoded copy. Nothing was billed.
                if (first.isUnmeasurable) {
                    first.prepared.file.delete()
                    attempt(source, options, limits, forceTranscode = true)
                } else {
                    first
                }
            } ?: return

        when (val result = attempt.result) {
            is TranscriptionResult.Success -> keep(source, result.transcript, attempt.prepared.file)
            is TranscriptionResult.Failure -> {
                attempt.prepared.file.delete()
                // Quota may have moved, and the paywall reads it.
                entitlements.refresh()
                fail(result.error)
            }
        }
    }

    /**
     * Saves a transcript that has been paid for. A failure here — a full disk, say — holds it for
     * [retrySave] rather than throwing out of the runner's scope, which crashed the app and left the
     * screen on "transcribing" with the credits spent and nothing to show.
     */
    internal suspend fun keep(source: Source, transcript: TranscriptDto, audio: File) {
        val saved = try {
            store.save(source.title, transcript, audio)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            unsaved = Unsaved(source, transcript, audio)
            entitlements.refresh()
            _state.value = State.SaveFailed(
                AiResult.Error(
                    "Your transcript is ready but couldn't be saved on this device " +
                        "(${e.message ?: e.javaClass.simpleName}). Free some space and tap Save again — " +
                        "you will not be charged again."
                )
            )
            return
        }
        unsaved = null
        if (source is Source.Recorded) source.file.delete()
        entitlements.refresh()
        _state.value = State.Done(saved.id)
    }

    private class Limits(val maxUpload: Long, val maxAudioMs: Long)

    private class Attempt(val prepared: AudioPreparer.PreparedAudio, val result: TranscriptionResult) {
        val isUnmeasurable: Boolean
            get() = result is TranscriptionResult.Failure &&
                result.error.code == "unsupported_format" && !prepared.transcoded
    }

    /** Prepares and uploads once, under a fresh idempotency key. Null if preparing failed. */
    private suspend fun attempt(
        source: Source,
        options: TranscribeOptions,
        limits: Limits,
        forceTranscode: Boolean
    ): Attempt? {
        _state.value = State.Working(Phase.PREPARING, 0f)
        val prepared = try {
            prepare(source, limits.maxUpload, limits.maxAudioMs, forceTranscode)
        } catch (e: AudioPreparer.PrepareException) {
            fail(AiResult.Error(e.message ?: "This recording couldn't be prepared."))
            return null
        }
        return Attempt(prepared, upload(prepared, options, UUID.randomUUID().toString()))
    }

    private suspend fun prepare(
        source: Source,
        maxUpload: Long,
        maxAudioMs: Long,
        forceTranscode: Boolean
    ): AudioPreparer.PreparedAudio {
        val (uri, hint) = when (source) {
            is Source.Picked -> source.uri to null
            is Source.Recorded -> Uri.fromFile(source.file) to AudioRecorder.MIME_TYPE
        }
        return preparer.prepare(uri, maxUpload, maxAudioMs, forceTranscode, hint) { fraction ->
            _state.value = State.Working(Phase.PREPARING, fraction)
        }
    }

    private suspend fun upload(
        prepared: AudioPreparer.PreparedAudio,
        options: TranscribeOptions,
        key: String
    ): TranscriptionResult {
        _state.value = State.Working(Phase.UPLOADING, 0f)
        return service.transcribe(
            file = prepared.file,
            mimeType = prepared.mimeType,
            options = options,
            idempotencyKey = key,
            onUploadProgress = { fraction ->
                _state.value = if (fraction >= 1f) {
                    State.Working(Phase.TRANSCRIBING, null, cancellable = false)
                } else {
                    State.Working(Phase.UPLOADING, fraction)
                }
            },
            emit = { event -> onEvent(event) }
        )
    }

    /** Surfaces the executor's retry countdown; its other stages are already covered above. */
    private fun onEvent(event: AiEvent) {
        if (event is AiEvent.Stage && event.stage == AiStage.PROCESSING && event.message != null) {
            val current = _state.value as? State.Working ?: return
            _state.value = current.copy(detail = event.message)
        }
    }

    private fun fail(error: AiResult.Error) {
        // A recording is kept on failure so the user can try again without re-recording.
        _state.value = State.Failed(error)
    }

    private companion object {
        const val DEFAULT_MAX_UPLOAD = 5L * 1024 * 1024
        const val DEFAULT_MAX_MINUTES = 10
        const val MS_PER_MINUTE = 60_000L
    }
}
