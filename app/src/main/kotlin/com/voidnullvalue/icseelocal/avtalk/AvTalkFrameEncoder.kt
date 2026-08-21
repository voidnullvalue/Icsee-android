package com.voidnullvalue.icseelocal.avtalk

import java.time.LocalDateTime

/** Byte-for-byte CSTDStream::NewFrame-compatible AVTalk media framing. */
object AvTalkFrameEncoder {
    const val MAX_VIDEO_PAYLOAD = 0x00ff_ffff
    const val MAX_AUDIO_PAYLOAD = 0xffff

    val SAMPLE_RATE_CODES: Map<Int, Int> = linkedMapOf(
        4000 to 1,
        8000 to 2,
        11025 to 3,
        16000 to 4,
        20000 to 5,
        22050 to 6,
        32000 to 7,
        44100 to 8,
        48000 to 9,
    )

    fun h265KeyFrame(
        annexB: ByteArray,
        profile: AvTalkProfile = AvTalkProfile(),
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): ByteArray = videoKeyFrame(annexB, profile, timestamp, h265 = true)

    fun h265InterFrame(annexB: ByteArray): ByteArray = videoInterFrame(annexB)

    fun alawAudio(samples: ByteArray, profile: AvTalkProfile = AvTalkProfile()): ByteArray =
        audio(samples, profile.audioCodecId, profile.audioSampleRate)

    fun videoKeyFrame(
        annexB: ByteArray,
        profile: AvTalkProfile,
        timestamp: LocalDateTime,
        h265: Boolean,
    ): ByteArray {
        require(annexB.size <= MAX_VIDEO_PAYLOAD) { "video payload exceeds FunSDK 24-bit length field" }
        require(timestamp.year in 2000..2063) { "FunSDK packed timestamp supports years 2000..2063" }
        val widthUnits = profile.width / 8
        val heightUnits = profile.height / 8
        val header = ByteArray(16)
        header[2] = 0x01
        header[3] = if (h265) 0xFC.toByte() else 0xFE.toByte()
        var codecAndDimensions = profile.videoCodecId and 0x0f
        codecAndDimensions = codecAndDimensions or (((widthUnits ushr 8) and 0x03) shl 4)
        codecAndDimensions = codecAndDimensions or (((heightUnits ushr 8) and 0x03) shl 6)
        header[4] = codecAndDimensions.toByte()
        header[5] = (profile.fps and 0x1f).toByte()
        header[6] = widthUnits.toByte()
        header[7] = heightUnits.toByte()

        val packedTime = packCalendarTimestamp(timestamp)
        putUInt32Le(header, 8, packedTime)
        putUInt24Le(header, 12, annexB.size)
        // byte 15 remains zero, exactly as the zero-initialized native XData buffer.
        return header + annexB
    }

    fun videoInterFrame(annexB: ByteArray): ByteArray {
        require(annexB.size <= MAX_VIDEO_PAYLOAD) { "video payload exceeds FunSDK 24-bit length field" }
        val header = ByteArray(8)
        header[2] = 0x01
        header[3] = 0xFD.toByte()
        putUInt24Le(header, 4, annexB.size)
        return header + annexB
    }

    fun audio(samples: ByteArray, codecId: Int, sampleRate: Int): ByteArray {
        require(codecId in 0..255) { "audio codec must fit one byte" }
        require(samples.size <= MAX_AUDIO_PAYLOAD) { "audio payload exceeds FunSDK 16-bit length field" }
        val rateCode = SAMPLE_RATE_CODES[sampleRate] ?: error("unsupported FunSDK sample rate $sampleRate")
        val header = ByteArray(8)
        header[2] = 0x01
        header[3] = 0xFA.toByte()
        header[4] = codecId.toByte()
        header[5] = rateCode.toByte()
        header[6] = (samples.size and 0xff).toByte()
        header[7] = ((samples.size ushr 8) and 0xff).toByte()
        return header + samples
    }

    fun packCalendarTimestamp(t: LocalDateTime): Int =
        ((t.year - 2000) shl 26) or
            (t.monthValue shl 22) or
            (t.dayOfMonth shl 17) or
            (t.hour shl 12) or
            (t.minute shl 6) or
            t.second

    private fun putUInt24Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xff).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xff).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xff).toByte()
    }

    private fun putUInt32Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xff).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xff).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xff).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
