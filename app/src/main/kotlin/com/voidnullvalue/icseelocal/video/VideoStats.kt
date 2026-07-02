package com.voidnullvalue.icseelocal.video

/** Decode statistics per the task brief -- exposed via diagnostics, never fabricated. */
data class VideoStats(
    val videoBytesReceived: Long = 0,
    val dvripMediaFrames: Long = 0,
    val codecFramesSubmitted: Long = 0,
    val framesDecoded: Long = 0,
    val framesDropped: Long = 0,
    val lastDecodedFrameTimestampMillis: Long? = null,
    val detectedCodec: DetectedCodec = DetectedCodec.UNKNOWN,
    val decoderErrors: Int = 0,
    val lastError: String? = null,
)
