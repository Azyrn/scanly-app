package com.skeler.scanely.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.transcribe.AudioPreparer
import com.skeler.scanely.core.transcribe.AudioRecorder
import com.skeler.scanely.core.transcribe.TranscribeOptions
import com.skeler.scanely.core.transcribe.TranscriptStore
import com.skeler.scanely.core.transcribe.TranscriptSummary
import com.skeler.scanely.core.transcribe.TranscriptionPrefs
import com.skeler.scanely.core.transcribe.TranscriptionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil

/** The recording or file waiting to be transcribed, with what it is expected to cost. */
data class PendingAudio(
    val source: TranscriptionRunner.Source,
    val durationMs: Long,
    /** Filled in once the file has been probed; null while probing. */
    val probed: Boolean = true
)

data class TranscribeUiState(
    val languages: List<String> = TRANSCRIBE_LANGUAGES_FALLBACK,
    /** The language a new transcription will use; null is auto-detect. */
    val language: String? = null,
    val keyTerms: List<String> = emptyList(),
    val pending: PendingAudio? = null,
    val pickError: String? = null,
    val recording: AudioRecorder.State = AudioRecorder.State.Idle,
    val job: TranscriptionRunner.State = TranscriptionRunner.State.Idle,
    val recent: List<TranscriptSummary> = emptyList(),
    val creditsRemaining: Int? = null,
    val maxAudioMinutes: Int = 10,
    val secondsPerCredit: Int = 180
) {
    val options: TranscribeOptions
        // Speakers, number formatting and filler removal are always on: they are what a transcript
        // is expected to look like, and a switch for each only made the screen harder to read.
        get() = TranscribeOptions(language = language, keyTerms = keyTerms)

    /** Credits the pending audio should cost. An estimate: the server bills what it processed. */
    val estimatedCredits: Int?
        get() = pending?.takeIf { it.probed && it.durationMs > 0 }?.let {
            ceil(it.durationMs / 1000.0 * options.passes / secondsPerCredit).toInt().coerceAtLeast(1)
        }

    val isBusy: Boolean get() = job is TranscriptionRunner.State.Working
}

/** The server's list, for the first frame before `/v1/me` has answered. */
internal val TRANSCRIBE_LANGUAGES_FALLBACK = listOf(
    "ar", "cs", "da", "de", "en", "es", "fa", "fil", "fr", "hi", "id", "it", "ja", "ko", "mk", "ms",
    "nl", "pl", "pt", "ro", "ru", "sv", "th", "tr", "vi"
)

@HiltViewModel
class TranscribeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runner: TranscriptionRunner,
    private val recorder: AudioRecorder,
    private val preparer: AudioPreparer,
    private val prefs: TranscriptionPrefs,
    private val store: TranscriptStore,
    private val entitlements: EntitlementRepository
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private data class LocalState(val pending: PendingAudio? = null, val pickError: String? = null)

    val state: StateFlow<TranscribeUiState> = combine(
        prefs.saved,
        entitlements.entitlement,
        combine(runner.state, recorder.state, ::Pair),
        store.summaries,
        local
    ) { saved, me, (job, recording), recent, localState ->
        val languages = me?.transcribeLanguages?.takeIf { it.isNotEmpty() } ?: TRANSCRIBE_LANGUAGES_FALLBACK
        TranscribeUiState(
            languages = languages,
            language = resolveLanguage(saved.language, languages, Locale.getDefault()),
            keyTerms = saved.keyTerms,
            pending = localState.pending,
            pickError = localState.pickError,
            recording = recording,
            job = job,
            recent = recent,
            creditsRemaining = me?.pagesRemainingMonth,
            maxAudioMinutes = me?.maxAudioMinutes ?: 10,
            secondsPerCredit = me?.transcribeSecondsPerCredit?.takeIf { it > 0 } ?: 180
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscribeUiState())

    init {
        viewModelScope.launch { store.refresh() }
        entitlements.refresh()
    }

    fun onFilePicked(uri: Uri) {
        val title = UriNames.displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Audio"
        val source = TranscriptionRunner.Source.Picked(uri, title)
        local.value = LocalState(pending = PendingAudio(source, 0, probed = false))
        viewModelScope.launch {
            try {
                val probe = preparer.probe(uri)
                local.update {
                    if (it.pending?.source == source) {
                        it.copy(pending = PendingAudio(source, probe.durationMs))
                    } else {
                        it
                    }
                }
            } catch (e: AudioPreparer.PrepareException) {
                local.value = LocalState(pickError = e.message)
            }
        }
    }

    fun startRecording(): Boolean {
        local.value = LocalState()
        return recorder.start()
    }

    fun togglePause() {
        val recording = recorder.state.value as? AudioRecorder.State.Recording ?: return
        if (recording.paused) recorder.resume() else recorder.pause()
    }

    fun discardRecording() = recorder.discard()

    fun stopRecording() {
        val elapsed = (recorder.state.value as? AudioRecorder.State.Recording)?.elapsedMs ?: 0L
        val file: File = recorder.stop() ?: run {
            local.value = LocalState(pickError = "Nothing was recorded. Check the microphone and try again.")
            return
        }
        val title = "Recording, " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date())
        local.value = LocalState(pending = PendingAudio(TranscriptionRunner.Source.Recorded(file, title), elapsed))
    }

    fun clearPending() {
        (local.value.pending?.source as? TranscriptionRunner.Source.Recorded)?.file?.delete()
        local.value = LocalState()
        runner.acknowledge()
    }

    fun dismissError() {
        local.update { it.copy(pickError = null) }
        runner.acknowledge()
    }

    fun transcribe() {
        val pending = local.value.pending ?: return
        viewModelScope.launch {
            runner.start(pending.source, state.first().options)
        }
    }

    fun cancel() = runner.cancel()

    fun retrySave() {
        runner.retrySave()
    }

    /** The screen has opened the finished transcript; the pending audio now belongs to it. */
    fun onOpened() {
        local.value = LocalState()
        runner.acknowledge()
    }

    fun setLanguage(code: String?) = viewModelScope.launch {
        prefs.setLanguage(code ?: TranscriptionPrefs.AUTO)
    }

    /** Applies a glossary edit. Returns what could not be added and why, for the sheet to show. */
    fun editKeyTerms(edit: KeyTermEdit): String? {
        val current = state.value.keyTerms
        val (terms, note) = when (edit) {
            is KeyTermEdit.Add -> addKeyTerms(current, edit.raw)
            is KeyTermEdit.Remove -> (current - edit.term) to null
            KeyTermEdit.Clear -> emptyList<String>() to null
        }
        if (terms != current) viewModelScope.launch { prefs.setKeyTerms(terms) }
        return note
    }

    fun deleteTranscript(id: String) = viewModelScope.launch { store.delete(id) }

    companion object {
        /**
         * An explicit choice wins. Before one is made, the device's own language when the
         * transcriber supports it — auto-detect with formatting costs a second pass, so it is not
         * the default.
         */
        internal fun resolveLanguage(saved: String?, supported: List<String>, device: Locale): String? {
            // Java still reports some languages by their withdrawn ISO 639 codes.
            val deviceCode = when (val code = device.language) {
                "in" -> "id"
                "tl" -> "fil"
                else -> code
            }
            return when {
                saved == TranscriptionPrefs.AUTO -> null
                saved != null && saved in supported -> saved
                else -> deviceCode.takeIf { it in supported }
            }
        }

        /**
         * Adds one term or a pasted list — commas, semicolons and new lines all separate — the way
         * the server will accept them: cleaned, de-duplicated ignoring case, and capped.
         */
        internal fun addKeyTerms(current: List<String>, raw: String): Pair<List<String>, String?> {
            val candidates = raw.split(',', '\n', ';').map(String::trim).filter(String::isNotEmpty)
            val cleaned = candidates.mapNotNull(TranscriptionPrefs::cleanKeyTerm)
            val seen = current.map { it.lowercase() }.toMutableSet()
            val added = cleaned.filter { seen.add(it.lowercase()) }
            val kept = added.take((TranscribeOptions.MAX_KEY_TERMS - current.size).coerceAtLeast(0))
            val note = when {
                added.size > kept.size ->
                    "Up to ${TranscribeOptions.MAX_KEY_TERMS} key terms. ${added.size - kept.size} weren't added."
                cleaned.size < candidates.size ->
                    "Key terms can be up to ${TranscribeOptions.MAX_KEY_TERM_CHARS} characters."
                else -> null
            }
            return (current + kept) to note
        }
    }
}

sealed interface KeyTermEdit {
    data class Add(val raw: String) : KeyTermEdit
    data class Remove(val term: String) : KeyTermEdit
    data object Clear : KeyTermEdit
}
