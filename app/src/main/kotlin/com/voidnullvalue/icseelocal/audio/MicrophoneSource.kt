package com.voidnullvalue.icseelocal.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException

/**
 * Captures mono microphone audio directly at 8kHz/16-bit (a standard,
 * widely-supported AudioRecord configuration -- no separate resampling
 * step needed) and G.711 A-law-encodes it in [TalkAudioFrame.AUDIO_PAYLOAD_SIZE]-
 * sample chunks matching the wire-confirmed talk frame size. Never writes
 * captured audio to disk.
 *
 * Audio source, sample rate, channel and format are matched byte-for-byte to
 * the reference iCSee app's own recorder (decompiled `XMRecordingManager`:
 * `new AudioRecord(1, 8000, 2, 2, ...)` == source MIC, 8kHz, mono, PCM 16-bit).
 * In particular the source is MIC, *not* VOICE_COMMUNICATION: the latter pulls
 * in device echo-cancel/telephony routing that on many phones fails to
 * initialize or yields silence unless the AudioManager is in communication
 * mode, which is exactly the "talk button does nothing" failure this app hit.
 */
class MicrophoneSource(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun captureAlawChunks(): Flow<ByteArray> = flow {
        // Checked immediately before use (not just via the separate hasPermission()
        // helper) so Android Lint's dataflow analysis for AudioRecord recognizes the
        // guard; the @SuppressLint above covers the residual TOCTOU gap between this
        // check and the constructor call, which is unavoidable with this API shape.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBufferSize > 0) { "device does not support 8kHz mono 16-bit PCM capture" }
        val bufferSize = maxOf(minBufferSize, TalkAudioFrame.AUDIO_PAYLOAD_SIZE * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        // AudioRecord's constructor can silently leave the object in
        // STATE_UNINITIALIZED (no exception) if the requested source/format
        // isn't actually available -- must check explicitly or capture just
        // does nothing.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            val msg = "AudioRecord failed to initialize (state=${record.state})"
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
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
                    // A non-positive result is an error code (e.g. ERROR_INVALID_OPERATION,
                    // ERROR_DEAD_OBJECT), not "no data yet" -- record.read() blocks until
                    // data is available in blocking mode, so this must throw rather than
                    // silently end the flow, or capture stops sending audio with no
                    // indication to the user that talk is no longer actually working.
                    if (n <= 0) throw IOException("AudioRecord.read() failed: $n")
                    read += n
                }
                emit(GAlaw.encodeBuffer(pcmBuffer))
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }.flowOn(Dispatchers.IO)
    // Capture is collected from viewModelScope (Dispatchers.Main); without this
    // the blocking AudioRecord.read() would run on the UI thread, stalling it for
    // ~40ms per 320-sample frame for the whole duration of talk.

    companion object {
        private const val TAG = "MicrophoneSource"
        private const val SAMPLE_RATE_HZ = 8000
    }
}
