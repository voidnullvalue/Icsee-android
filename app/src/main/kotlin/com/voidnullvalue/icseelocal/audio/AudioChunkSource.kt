package com.voidnullvalue.icseelocal.audio

import kotlinx.coroutines.flow.Flow

/**
 * Anything that can supply a stream of G.711 A-law-encoded [TalkAudioFrame.AUDIO_PAYLOAD_SIZE]-byte
 * chunks for [com.voidnullvalue.icseelocal.audio.TalkController] to send over the talk channel.
 * [MicrophoneSource] is the live-mic implementation; [FileAudioSource] plays a local audio file
 * through the same pipeline instead.
 */
interface AudioChunkSource {
    fun captureAlawChunks(): Flow<ByteArray>
}
