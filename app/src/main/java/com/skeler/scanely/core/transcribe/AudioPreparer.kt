package com.skeler.scanely.core.transcribe

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Turns whatever the user picked — a voice memo, a WhatsApp note, a two-hour meeting, a video — into
 * one file the Worker will accept for their plan.
 *
 * A recording that is already a format the transcriber reads, and already inside the plan's upload
 * cap, goes up untouched: re-encoding it could only lose accuracy. Everything else — video, formats
 * the Worker cannot measure (AMR, 3GP), and files over the cap — is decoded and re-encoded as mono
 * 16 kHz AAC, at a bitrate chosen so the plan's longest recording still fits its upload cap. Speech
 * recognition needs nothing above 8 kHz of bandwidth, so this is where the size comes from, not
 * from quality.
 */
@Singleton
class AudioPreparer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    class PreparedAudio(
        val file: File,
        val mimeType: String,
        val durationMs: Long,
        val transcoded: Boolean
    )

    class PrepareException(message: String) : Exception(message)

    data class Probe(val durationMs: Long, val sizeBytes: Long, val mimeType: String?, val hasVideo: Boolean)

    /** Duration and shape, read without decoding — for the cost estimate before anything is sent. */
    suspend fun probe(uri: Uri, mimeTypeHint: String? = null): Probe = withContext(Dispatchers.IO) {
        val tracks = try {
            trackMimes(uri)
        } catch (e: IOException) {
            Log.w(TAG, "probe failed", e)
            throw PrepareException("This file couldn't be opened as audio or video.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "probe failed", e)
            throw PrepareException("This file couldn't be opened as audio or video.")
        } catch (e: SecurityException) {
            Log.w(TAG, "probe lost access", e)
            throw PrepareException("Scanly no longer has access to this file. Pick it again.")
        }
        val audio = tracks.filter { it.first.startsWith("audio/") }
        if (audio.isEmpty()) throw PrepareException("This file has no audio to transcribe.")
        val trackUs = audio.maxOf { it.second }
        Probe(
            durationMs = if (trackUs > 0) trackUs / MICROS_PER_MS else retrieverDuration(uri),
            sizeBytes = sizeOf(uri),
            // file:// URIs (our own recordings) have no resolver type; their creator knows it.
            mimeType = context.contentResolver.getType(uri) ?: mimeTypeHint,
            hasVideo = tracks.any { it.first.startsWith("video/") }
        )
    }

    /** Each track's MIME type and duration in microseconds (0 when the track does not say). */
    private fun trackMimes(uri: Uri): List<Pair<String, Long>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return (0 until extractor.trackCount).map { i ->
                val format = extractor.getTrackFormat(i)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                format.getString(MediaFormat.KEY_MIME).orEmpty() to duration
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Produces the upload. [maxUploadBytes] and [maxAudioMs] are the plan's limits from `/v1/me`;
     * the server enforces both again, so these checks only spare the user a wasted upload.
     */
    suspend fun prepare(
        uri: Uri,
        maxUploadBytes: Long,
        maxAudioMs: Long,
        forceTranscode: Boolean = false,
        mimeTypeHint: String? = null,
        onProgress: (Float) -> Unit
    ): PreparedAudio = withContext(Dispatchers.IO) {
        val probe = probe(uri, mimeTypeHint)
        if (probe.durationMs > maxAudioMs) {
            throw PrepareException(
                "This recording is ${TranscriptFormats.clock(probe.durationMs)} long. Your plan " +
                    "transcribes up to ${maxAudioMs / MS_PER_MINUTE} minutes at a time."
            )
        }

        val extension = passThroughExtension(probe.mimeType)?.takeUnless { forceTranscode }
        if (extension != null && fitsAsIs(probe, maxUploadBytes)) {
            return@withContext copyAsIs(uri, extension, probe).also { onProgress(1f) }
        }

        val bitrate = targetBitrate(probe.durationMs, maxUploadBytes)
        val out = File(workDir(), "${UUID.randomUUID()}.m4a")
        try {
            transcode(uri, out, bitrate, probe.durationMs, onProgress)
        } catch (e: PrepareException) {
            out.delete()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            out.delete()
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // MediaCodec reports a codec it cannot run as IllegalStateException, CodecException or
            // IOException depending on device; every one of them means the same thing to the user.
            out.delete()
            Log.w(TAG, "transcode failed", e)
            throw PrepareException("This file's audio couldn't be converted. Try another file.")
        }
        if (out.length() > maxUploadBytes) {
            out.delete()
            throw PrepareException("This recording is too large for your plan even after compressing it.")
        }
        PreparedAudio(out, "audio/mp4", probe.durationMs, transcoded = true)
    }

    /** Audio-only, and already inside the plan's upload cap. */
    private fun fitsAsIs(probe: Probe, maxUploadBytes: Long): Boolean =
        !probe.hasVideo && probe.sizeBytes in 1..maxUploadBytes

    private fun copyAsIs(uri: Uri, extension: String, probe: Probe): PreparedAudio {
        val out = File(workDir(), "${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw PrepareException("This file couldn't be read.")
        return PreparedAudio(out, probe.mimeType!!, probe.durationMs, transcoded = false)
    }

    /**
     * Leftover uploads from a run that was killed. Called when a new one starts. Recordings
     * (`rec-*`) are spared: one that failed to transcribe is kept for the retry.
     */
    fun clearWorkDir() {
        workDir().listFiles()?.filterNot { it.name.startsWith("rec-") }?.forEach { it.delete() }
    }

    private fun workDir(): File = File(context.cacheDir, "transcribe").apply { mkdirs() }

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it > 0 } ?: -1L

    private fun retrieverDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private suspend fun transcode(
        uri: Uri,
        out: File,
        bitrate: Int,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ) = AacTranscoder(context, uri, out, bitrate, durationMs, onProgress).run()

    companion object {
        private const val TAG = "AudioPreparer"
        private const val MICROS_PER_MS = 1000L
        private const val MS_PER_SECOND = 1000
        private const val MS_PER_MINUTE = 60_000L
        private const val BITS_PER_BYTE = 8

        /** Share of the upload cap the audio may use; the rest is MP4 container overhead. */
        private const val PAYLOAD_SHARE = 0.92

        /** Mono 16 kHz AAC-LC: 32 kbps is transparent for speech; below 16 kbps it is not. */
        private const val MAX_BITRATE = 32_000
        private const val MIN_BITRATE = 16_000

        /**
         * The bitrate that fits [durationMs] into [maxUploadBytes] with headroom for the container,
         * never above what speech needs nor below where AAC-LC starts to smear consonants.
         */
        internal fun targetBitrate(durationMs: Long, maxUploadBytes: Long): Int {
            if (durationMs <= 0) return MAX_BITRATE
            val seconds = durationMs.toDouble() / MS_PER_SECOND
            val fits = (maxUploadBytes * BITS_PER_BYTE * PAYLOAD_SHARE / seconds).toInt()
            return fits.coerceIn(MIN_BITRATE, MAX_BITRATE)
        }

        /**
         * Containers the Worker measures and xAI reads, by MIME type, and the extension each is
         * saved under. Anything absent — AMR, 3GP, video — is transcoded instead.
         */
        internal fun passThroughExtension(mimeType: String?): String? =
            PASS_THROUGH[mimeType?.lowercase()]

        private val PASS_THROUGH = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/aac-adts" to "aac",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/wave" to "wav",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/ogg" to "ogg",
            "application/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/webm" to "webm"
        )
    }
}

/**
 * Mono 16-bit resampling for speech. Downsampling averages each output sample's window of input —
 * a box filter, which is enough anti-aliasing for recognition. Upsampling never happens: the
 * target rate is capped at the source's, and an equal rate passes straight through.
 */
internal class MonoResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate

    /** Input samples still owed to the current output window; carries the fraction across. */
    private var remaining = step
    private var sum = 0L
    private var count = 0

    fun process(input: ShortArray, out: ShortQueue) {
        if (inRate <= outRate) {
            out.addAll(input)
            return
        }
        for (sample in input) {
            sum += sample
            count++
            remaining -= 1.0
            if (remaining <= 0.0) {
                out.add((sum.toDouble() / count).roundToInt().toShort())
                sum = 0
                count = 0
                remaining += step
            }
        }
    }
}

/** A growable queue of PCM samples between the decoder and the encoder. */
internal class ShortQueue {
    private var data = ShortArray(8192)
    private var head = 0
    private var tail = 0
    val size: Int get() = tail - head

    fun add(value: Short) {
        ensure(1)
        data[tail++] = value
    }

    fun addAll(values: ShortArray) {
        ensure(values.size)
        values.copyInto(data, tail)
        tail += values.size
    }

    fun drainInto(target: java.nio.ShortBuffer, count: Int) {
        target.put(data, head, count)
        head += count
        if (head == tail) {
            head = 0
            tail = 0
        }
    }

    fun toArray(): ShortArray = data.copyOfRange(head, tail)

    private fun ensure(extra: Int) {
        if (tail + extra <= data.size) return
        val live = size
        val target = if (live + extra <= data.size) data else ShortArray(maxOf(data.size * 2, live + extra))
        data.copyInto(target, 0, head, tail)
        data = target
        head = 0
        tail = live
    }
}
