package com.skeler.scanely.ui.viewmodel

import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.skeler.scanely.core.transcribe.Transcript
import com.skeler.scanely.core.transcribe.TranscriptSegment
import com.skeler.scanely.core.transcribe.TranscriptStore
import com.skeler.scanely.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f
)

data class TranscriptUiState(
    val loading: Boolean = true,
    val transcript: Transcript? = null,
    val missing: Boolean = false,
    val player: PlayerState = PlayerState(),
    /** Segment and word under the playhead, for the highlight; -1 when none. */
    val activeSegment: Int = -1,
    val activeWord: Int = -1,
    val deleted: Boolean = false
)

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: TranscriptStore
) : ViewModel() {

    private val id = savedState.toRoute<Destination.Transcript>().id

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            val transcript = store.load(id)
            _state.update { it.copy(loading = false, transcript = transcript, missing = transcript == null) }
            store.audioFor(id)?.let(::preparePlayer)
        }
    }

    private fun preparePlayer(file: java.io.File) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _state.update { s -> s.copy(player = s.player.copy(playing = false)) }
                    stopTicker()
                }
            }
            player = mp
            _state.update { it.copy(player = PlayerState(available = true, durationMs = mp.duration.toLong())) }
        } catch (e: Exception) {
            // The transcript is still whole without its audio; only playback is lost.
            Log.w(TAG, "Audio unavailable for $id", e)
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopTicker()
            _state.update { it.copy(player = it.player.copy(playing = false)) }
        } else {
            mp.start()
            startTicker()
            _state.update { it.copy(player = it.player.copy(playing = true)) }
        }
    }

    /** Jumps to [ms] and plays from there — what tapping a word or a timestamp means. */
    fun seekAndPlay(ms: Long) {
        val mp = player ?: return
        mp.seekTo(ms.toInt().coerceIn(0, mp.duration))
        if (!mp.isPlaying) {
            mp.start()
            startTicker()
        }
        _state.update { it.copy(player = it.player.copy(playing = true)) }
        publishPosition(ms)
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        mp.seekTo(ms.toInt().coerceIn(0, mp.duration))
        publishPosition(ms)
    }

    fun skip(deltaMs: Long) {
        val mp = player ?: return
        seekTo(mp.currentPosition + deltaMs)
    }

    fun cycleSpeed() {
        val mp = player ?: return
        val next = SPEEDS[(SPEEDS.indexOf(_state.value.player.speed) + 1) % SPEEDS.size]
        val wasPlaying = mp.isPlaying
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(next) }
        // Setting a speed starts a paused player on some devices; keep the user's state.
        if (!wasPlaying && mp.isPlaying) mp.pause()
        _state.update { it.copy(player = it.player.copy(speed = next)) }
    }

    fun rename(title: String) = viewModelScope.launch {
        val clean = title.trim().ifEmpty { return@launch }
        store.rename(id, clean)?.let { t -> _state.update { it.copy(transcript = t) } }
    }

    fun renameSpeaker(speaker: Int, name: String) = viewModelScope.launch {
        store.renameSpeaker(id, speaker, name)?.let { t -> _state.update { it.copy(transcript = t) } }
    }

    fun delete() = viewModelScope.launch {
        releasePlayer()
        store.delete(id)
        _state.update { it.copy(deleted = true) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                player?.let { publishPosition(it.currentPosition.toLong()) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publishPosition(ms: Long) {
        val segments = _state.value.transcript?.segments.orEmpty()
        val (segment, word) = locate(segments, ms)
        _state.update {
            it.copy(
                player = it.player.copy(positionMs = ms),
                activeSegment = segment,
                activeWord = word
            )
        }
    }

    override fun onCleared() {
        releasePlayer()
    }

    private fun releasePlayer() {
        stopTicker()
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "TranscriptViewModel"
        private const val TICK_MS = 80L
        private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

        /**
         * The segment and word playing at [ms]: the last of each that has started. Binary search
         * over segments then words, so a three-hour transcript costs ~30 comparisons a tick.
         */
        internal fun locate(segments: List<TranscriptSegment>, ms: Long): Pair<Int, Int> {
            val s = lastStartedBefore(segments.size, ms) { segments[it].startMs }
            if (s < 0) return -1 to -1
            val segment = segments[s]
            if (ms > segment.endMs + GAP_TOLERANCE_MS) return s to -1
            val w = lastStartedBefore(segment.words.size, ms) { segment.words[it].startMs }
            return s to w
        }

        private const val GAP_TOLERANCE_MS = 400L

        private inline fun lastStartedBefore(size: Int, ms: Long, startOf: (Int) -> Long): Int {
            var lo = 0
            var hi = size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (startOf(mid) <= ms) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }
    }
}
