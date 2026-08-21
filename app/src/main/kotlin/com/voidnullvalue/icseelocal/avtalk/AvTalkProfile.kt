package com.voidnullvalue.icseelocal.avtalk

/** Wire parameters advertised to the camera for an AVTalk session. */
data class AvTalkProfile(
    val channel: Int = 0,
    val proType: Int = 0,
    val useExt: Int? = null,
    val videoEncoding: String = "H265",
    val videoCodecId: Int = 1,
    val width: Int = 240,
    val height: Int = 320,
    val fps: Int = 10,
    val audioEncoding: String = "g711a",
    val audioCodecId: Int = 14,
    val audioSampleBits: Int = 16,
    val audioSampleRate: Int = 8000,
) {
    init {
        require(channel >= 0) { "channel must be non-negative" }
        require(videoEncoding.equals("H265", ignoreCase = true)) { "this AVTalk uplink reimplementation emits H.265 CSTDStream frames" }
        require(audioEncoding.equals("g711a", ignoreCase = true)) { "this AVTalk uplink reimplementation emits G.711 A-law audio" }
        require(audioCodecId == 14) { "FunSDK G.711 A-law codec id must be 14" }
        require(proType >= 0) { "proType must be non-negative" }
        require(videoCodecId in 0..15) { "video codec id must fit the FunSDK frame nibble" }
        require(width > 0 && width % 8 == 0 && width / 8 <= 0x3ff) { "width must be a positive multiple of 8 representable by the FunSDK header" }
        require(height > 0 && height % 8 == 0 && height / 8 <= 0x3ff) { "height must be a positive multiple of 8 representable by the FunSDK header" }
        require(fps in 1..31) { "fps must fit the 5-bit FunSDK field" }
        require(audioCodecId in 0..255) { "audio codec id must fit one byte" }
        require(audioSampleBits > 0) { "audio sample bits must be positive" }
        require(audioSampleRate in AvTalkFrameEncoder.SAMPLE_RATE_CODES) { "unsupported FunSDK audio sample rate: $audioSampleRate" }
    }
}
