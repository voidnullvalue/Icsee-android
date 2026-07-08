package com.voidnullvalue.icseelocal.audio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Captures whatever audio this app is currently playing (e.g. a WebView showing an
 * embedded video) via [AudioPlaybackCaptureConfiguration] and re-encodes it into the
 * same G.711 A-law chunks [MicrophoneSource] produces from the mic -- a drop-in
 * [AudioChunkSource] for [TalkController], so the camera speaker plays back
 * whatever's already playing on-screen instead of (or as well as) the user's voice.
 *
 * This mirrors casting/mirroring audio to a speaker: nothing is downloaded, stored,
 * or redistributed anywhere -- it's a live relay of audio already playing through
 * this app's own playback, requiring the user's explicit MediaProjection consent
 * (the system "Start recording or casting?" dialog) every time, same as screen
 * recording. Requires API 29 (Android 10)+; [android.permission.RECORD_AUDIO] must
 * also be granted (Android gates all `AudioRecord` construction on it, playback
 * capture included).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureAudioSource(private val mediaProjection: MediaProjection) : AudioChunkSource {
    private val beatDetector = BeatDetector()
    private val _beats = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per detected beat onset in the captured audio -- see [BeatDetector]. */
    val beats: SharedFlow<Unit> = _beats.asSharedFlow()

    override fun captureAlawChunks(): Flow<ByteArray> = flow {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBufferSize > 0) { "device does not support 8kHz mono 16-bit PCM capture" }
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBufferSize, TalkAudioFrame.AUDIO_PAYLOAD_SIZE * 2 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Playback-capture AudioRecord failed to initialize (state=${record.state})")
        }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord.startRecording() did not enter RECORDING state")
            }
            val pcmBuffer = ShortArray(TalkAudioFrame.AUDIO_PAYLOAD_SIZE)
            while (kotlin.coroutines.coroutineContext.isActive) {
                var read = 0
                while (read < pcmBuffer.size) {
                    val n = record.read(pcmBuffer, read, pcmBuffer.size - read)
                    if (n <= 0) throw IOException("AudioRecord.read() failed: $n")
                    read += n
                }
                if (beatDetector.onChunk(pcmBuffer)) _beats.tryEmit(Unit)
                emit(GAlaw.encodeBuffer(pcmBuffer))
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SAMPLE_RATE_HZ = 8000
    }
}
