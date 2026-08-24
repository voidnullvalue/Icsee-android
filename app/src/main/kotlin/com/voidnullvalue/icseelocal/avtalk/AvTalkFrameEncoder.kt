package com.voidnullvalue.icseelocal.avtalk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte-for-byte 1419 sub-framing from the working issue #6 main.py. */
object AvTalkFrameEncoder {
    const val AUDIO_PAYLOAD_SIZE = 320

    fun h265KeyFrame(
        annexB: ByteArray,
        profile: AvTalkProfile = AvTalkProfile(),
        unixSeconds: Long = System.currentTimeMillis() / 1000L,
    ): ByteArray {
        require(annexB.size >= 0) { "invalid video size" }
        require(unixSeconds in 0..0xffff_ffffL) { "timestamp must fit uint32" }
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x00, 0x00, 0x01, 0xFC.toByte()))
        header.put(profile.videoCodecId.toByte())
        header.put(profile.fps.toByte())
        header.put((profile.width / 8).toByte())
        header.put((profile.height / 8).toByte())
        header.putInt(unixSeconds.toInt())
        header.putInt(annexB.size)
        return header.array() + annexB
    }

    fun h265InterFrame(annexB: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x00, 0x00, 0x01, 0xFD.toByte()))
        header.putInt(annexB.size)
        return header.array() + annexB
    }

    fun alawAudio(samples: ByteArray, profile: AvTalkProfile = AvTalkProfile()): ByteArray {
        require(samples.size == AUDIO_PAYLOAD_SIZE) {
            "working AVTalk flow uses exactly $AUDIO_PAYLOAD_SIZE A-law bytes per audio frame"
        }
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x00, 0x00, 0x01, 0xFA.toByte()))
        header.put(profile.audioCodecId.toByte())
        header.put(2) // main.py rateIdx=2 for 8 kHz
        header.putShort(samples.size.toShort())
        return header.array() + samples
    }
}
