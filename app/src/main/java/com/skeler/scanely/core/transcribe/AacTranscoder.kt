package com.skeler.scanely.core.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes the first audio track of [uri] and re-encodes it as mono AAC-LC in an MPEG-4 file.
 *
 * One pass through four stages that run interleaved, each a small method below: the extractor
 * feeds the decoder, decoded PCM is downmixed and resampled into [pending], [pending] feeds the
 * encoder, and the encoder's output goes to the muxer. [pending] is bounded, so a fast decoder
 * cannot run ahead of the encoder and hold an hour of PCM in memory.
 */
internal class AacTranscoder(
    private val context: Context,
    private val uri: Uri,
    private val out: File,
    private val bitrate: Int,
    private val durationMs: Long,
    private val onProgress: (Float) -> Unit
) {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1

    private var outRate = TARGET_RATE
    private var channels = 1
    private var floatPcm = false
    private lateinit var resampler: MonoResampler
    private val pending = ShortQueue()
    private var samplesQueued = 0L

    private var extractorDone = false
    private var decoderDone = false
    private var encoderInputDone = false
    private var finished = false
    private val info = MediaCodec.BufferInfo()

    suspend fun run() {
        try {
            open()
            while (!finished) {
                currentCoroutineContext().ensureActive()
                feedDecoder()
                drainDecoder()
                feedEncoder()
                drainEncoder()
            }
            onProgress(1f)
        } finally {
            close()
        }
    }

    private fun open() {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw AudioPreparer.PrepareException("This file has no audio to transcribe.")
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        outRate = minOf(TARGET_RATE, inRate)
        channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        resampler = MonoResampler(inRate, outRate)

        decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(inFormat, null, null, 0)
            start()
        }
        val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ENCODER_INPUT_BYTES)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun feedDecoder() {
        if (extractorDone) return
        val codec = decoder ?: return
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            extractorDone = true
            return
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        if (durationMs > 0) {
            onProgress((extractor.sampleTime / MICROS_PER_MS / durationMs).coerceIn(0f, MAX_PROGRESS))
        }
        extractor.advance()
    }

    private fun drainDecoder() {
        if (decoderDone || pending.size >= PENDING_LIMIT) return
        val codec = decoder ?: return
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // The real PCM shape; HE-AAC's SBR, for one, doubles the rate the track declared.
            val format = codec.outputFormat
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            resampler = MonoResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), outRate)
            return
        }
        if (index < 0) return
        val buffer = codec.getOutputBuffer(index)!!
        buffer.position(info.offset).limit(info.offset + info.size)
        resampler.process(downmix(buffer.order(ByteOrder.nativeOrder()), channels, floatPcm), pending)
        codec.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
    }

    private fun feedEncoder() {
        if (encoderInputDone || (pending.size == 0 && !decoderDone)) return
        val codec = encoder ?: return
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
        val count = minOf(pending.size, buffer.capacity() / 2)
        val pts = samplesQueued * MICROS_PER_SECOND / outRate
        if (count == 0) {
            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            encoderInputDone = true
            return
        }
        pending.drainInto(buffer.asShortBuffer(), count)
        codec.queueInputBuffer(index, 0, count * 2, pts, 0)
        samplesQueued += count
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val mux = muxer ?: return
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            muxerTrack = mux.addTrack(codec.outputFormat)
            mux.start()
            return
        }
        if (index < 0) return
        val buffer = codec.getOutputBuffer(index)!!
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!isConfig && info.size > 0 && muxerTrack >= 0) {
            buffer.position(info.offset).limit(info.offset + info.size)
            mux.writeSampleData(muxerTrack, buffer, info)
        }
        codec.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished = true
    }

    private fun close() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { if (muxerTrack >= 0) muxer?.stop() }
        runCatching { muxer?.release() }
        extractor.release()
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val TIMEOUT_US = 10_000L
        const val ENCODER_INPUT_BYTES = 16 * 1024
        const val PENDING_LIMIT = 64 * 1024
        const val MICROS_PER_SECOND = 1_000_000L
        const val MICROS_PER_MS = 1000f

        /** Held back from 1 until the muxer has actually finished the file. */
        const val MAX_PROGRESS = 0.99f

        fun downmix(buffer: ByteBuffer, channels: Int, floatPcm: Boolean): ShortArray {
            val ch = channels.coerceAtLeast(1)
            return if (floatPcm) {
                val floats = buffer.asFloatBuffer()
                ShortArray(floats.remaining() / ch) { f ->
                    var sum = 0f
                    for (c in 0 until ch) sum += floats.get(f * ch + c)
                    ((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
                }
            } else {
                val shorts = buffer.asShortBuffer()
                ShortArray(shorts.remaining() / ch) { f ->
                    var sum = 0
                    for (c in 0 until ch) sum += shorts.get(f * ch + c)
                    (sum / ch).toShort()
                }
            }
        }
    }
}
