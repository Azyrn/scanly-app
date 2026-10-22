package com.skeler.scanely.core.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skeler.scanely.MainActivity
import com.skeler.scanely.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records from the microphone into mono 16 kHz AAC — speech-grade, and a format the Worker reads
 * without any re-encoding (14 MB an hour, so a free plan's ten minutes is ~2.4 MB).
 *
 * A singleton rather than part of a screen's ViewModel, because a recording outlives the screen:
 * a meeting is recorded with the phone locked in a pocket. While it runs, [RecordingService] holds
 * the process in the foreground with a microphone-type notification — without one, Android cuts
 * background microphone access within seconds.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    sealed interface State {
        data object Idle : State
        data class Recording(
            val elapsedMs: Long,
            val paused: Boolean,
            /** 0..1, the loudest sample since the last tick, for the level meter. */
            val level: Float
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var ticker: Job? = null
    private var accumulatedMs = 0L
    private var resumedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts recording. The caller must already hold RECORD_AUDIO. */
    fun start(): Boolean {
        if (recorder != null) return true
        val file = File(File(context.cacheDir, "transcribe").apply { mkdirs() }, "rec-${UUID.randomUUID()}.m4a")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = created
            output = file
            accumulatedMs = 0L
            resumedAt = SystemClock.elapsedRealtime()
            RecordingService.start(context)
            startTicker()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            created.release()
            file.delete()
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        val current = _state.value as? State.Recording ?: return
        if (current.paused) return
        runCatching { r.pause() }.onSuccess {
            accumulatedMs += SystemClock.elapsedRealtime() - resumedAt
            _state.value = current.copy(paused = true, level = 0f)
        }
    }

    fun resume() {
        val r = recorder ?: return
        val current = _state.value as? State.Recording ?: return
        if (!current.paused) return
        runCatching { r.resume() }.onSuccess {
            resumedAt = SystemClock.elapsedRealtime()
            _state.value = current.copy(paused = false)
        }
    }

    /** Stops and returns the finished file, or null if nothing usable was captured. */
    fun stop(): File? {
        val r = recorder ?: return null
        val file = output
        val finished = runCatching { r.stop() }.isSuccess
        r.release()
        cleanUp()
        // MediaRecorder throws from stop() when it captured nothing, e.g. stopped instantly.
        return file?.takeIf { finished && it.length() > 0 } ?: run {
            file?.delete()
            null
        }
    }

    /** Stops and throws the recording away. */
    fun discard() {
        stop()?.delete()
    }

    private fun cleanUp() {
        recorder = null
        output = null
        ticker?.cancel()
        ticker = null
        _state.value = State.Idle
        RecordingService.stop(context)
    }

    private fun startTicker() {
        ticker?.cancel()
        _state.value = State.Recording(elapsedMs = 0, paused = false, level = 0f)
        ticker = scope.launch {
            while (isActive && tick()) delay(TICK_MS)
        }
    }

    /** Publishes elapsed time and input level. False once the recording has ended. */
    private fun tick(): Boolean {
        val r = recorder ?: return false
        val current = _state.value as? State.Recording ?: return false
        if (!current.paused) {
            val level = runCatching { r.maxAmplitude }.getOrDefault(0) / MAX_AMPLITUDE
            _state.value = current.copy(
                elapsedMs = accumulatedMs + SystemClock.elapsedRealtime() - resumedAt,
                level = level.coerceIn(0f, 1f)
            )
        }
        return true
    }

    companion object {
        /** What [start] writes: AAC in an MPEG-4 container. */
        const val MIME_TYPE = "audio/mp4"
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 32_000
        private const val TICK_MS = 100L
        private const val MAX_AMPLITUDE = 32_767f
    }
}

/**
 * Keeps a recording alive while the app is in the background. Does no work of its own: the
 * [AudioRecorder] singleton owns the microphone, and this only carries the notification Android
 * requires for a foreground microphone.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            // Refused (e.g. started from the background): the recording still runs while visible.
            Log.w("RecordingService", "Could not enter the foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Audio to Text is recording"
                }
            )
        }
        // The launcher's own intent brings the running task forward rather than starting another.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle("Recording")
            .setContentText("Scanly is recording for Audio to Text. Tap to return.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
