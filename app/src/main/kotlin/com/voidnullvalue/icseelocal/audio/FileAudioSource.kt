package com.voidnullvalue.icseelocal.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Plays a local audio file through the same [TalkAudioFrame]/G.711-A-law pipeline
 * [MicrophoneSource] uses for live mic capture -- a drop-in [AudioChunkSource] for
 * [TalkController], so the camera speaker can play a pre-recorded track.
 *
 * Replaces an earlier attempt that captured a WebView's own audio playback via
 * Android's [android.media.AudioPlaybackCaptureConfiguration] (MediaProjection-
 * based "casting" capture): that approach didn't crash, but produced no audio --
 * consistent with Android's playback-capture API deliberately refusing to capture
 * DRM-protected content, which is what embedded YouTube audio typically is. This
 * sidesteps that entirely by decoding a file directly rather than capturing
 * whatever's already playing through the OS audio mixer, so there's no
 * capture-layer DRM restriction to hit.
 *
 * Deliberately reads from local device storage rather than bundling any audio as
 * an app asset: this repo is public, and shipping a copyrighted commercial
 * recording in a publicly-distributed APK/repo is a real problem, not a
 * hypothetical one. Whatever file you want to use here is yours to supply --
 * `adb push your-track.mp3 <trackFile() path>` (or copy it with a file manager)
 * before triggering playback.
 */
class FileAudioSource(context: Context, fileName: String = "funkytown.mp3") : AudioChunkSource {
    private val musicDir = File(context.getExternalFilesDir(null), "Music")
    private val file = File(musicDir, fileName)
    private val beatDetector = BeatDetector()
    private val _beats = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per detected beat onset in the track -- see [BeatDetector]. */
    val beats: SharedFlow<Unit> = _beats.asSharedFlow()

    fun trackFile(): File = file
    fun isAvailable(): Boolean = file.exists() && file.length() > 0

    override fun captureAlawChunks(): Flow<ByteArray> = flow {
        if (!isAvailable()) {
            throw IOException(
                "No local track at ${file.path}. Supply your own copy of the file there " +
                    "(e.g. `adb push yourtrack.mp3 ${file.path}`) before starting.",
            )
        }
        val mono8k = decodeToMono8kHzPcm(file)
        var offset = 0
        while (offset < mono8k.size && kotlin.coroutines.coroutineContext.isActive) {
            val end = minOf(offset + TalkAudioFrame.AUDIO_PAYLOAD_SIZE, mono8k.size)
            val chunk = ShortArray(TalkAudioFrame.AUDIO_PAYLOAD_SIZE)
            mono8k.copyOfRange(offset, end).copyInto(chunk)
            if (beatDetector.onChunk(chunk)) _beats.tryEmit(Unit)
            emit(GAlaw.encodeBuffer(chunk))
            offset += TalkAudioFrame.AUDIO_PAYLOAD_SIZE
            // 320 samples at 8kHz == 40ms/frame -- paces emission to real playback
            // time, matching the cadence MicrophoneSource's live capture naturally has.
            delay(40)
        }
    }.flowOn(Dispatchers.IO)

    /** Decodes the whole file up front (simplest correct implementation for a short clip -- a full
     *  track decoded to 16-bit PCM is a few tens of MB, trivial for a modern phone) rather than
     *  streaming/resampling incrementally across codec output boundaries. */
    private fun decodeToMono8kHzPcm(file: File): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        val fmt = requireNotNull(format) { "No audio track found in ${file.path}" }
        extractor.selectTrack(trackIndex)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val pcmOut = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    outBuf.clear()
                    codec.releaseOutputBuffer(outIndex, false)
                    pcmOut.write(chunk)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val rawBytes = pcmOut.toByteArray()
        val samples = ShortArray(rawBytes.size / 2)
        ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        val mono = if (channels <= 1) samples else downmixToMono(samples, channels)
        return resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE_HZ)
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        val frames = interleaved.size / channels
        return ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            (sum / channels).toShort()
        }
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        return ShortArray(outLength) { i ->
            val srcPos = i.toDouble() * fromRate / toRate
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input.getOrElse(idx) { 0 }
            val b = input.getOrElse(idx + 1) { a }
            (a + (b - a) * frac).toInt().toShort()
        }
    }

    companion object {
        private const val TARGET_SAMPLE_RATE_HZ = 8000
    }
}
