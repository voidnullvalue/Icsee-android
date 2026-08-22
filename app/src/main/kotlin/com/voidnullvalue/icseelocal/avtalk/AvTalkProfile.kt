package com.voidnullvalue.icseelocal.avtalk

/** Wire parameters used by the issue #6 working main.py reference implementation. */
data class AvTalkProfile(
    val channel: Int = 0,
    val proType: Int = 0,
    val useExt: Int = -1,
    val mediaType: Int = 0,
    val videoEncoding: String = "H265",
    /** main.py puts literal codec byte 3 in the 0xFC key-frame header. */
    val videoCodecId: Int = 3,
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
        require(proType >= 0) { "proType must be non-negative" }
        require(videoEncoding.equals("H265", ignoreCase = true)) { "AVTalk video must be H265" }
        require(videoCodecId in 0..255) { "video codec id must fit one byte" }
        require(width > 0 && width % 8 == 0 && width / 8 <= 255) { "width must be a positive multiple of 8" }
        require(height > 0 && height % 8 == 0 && height / 8 <= 255) { "height must be a positive multiple of 8" }
        require(fps in 1..255) { "fps must fit one byte" }
        require(audioEncoding.equals("g711a", ignoreCase = true)) { "AVTalk audio must be g711a" }
        require(audioCodecId == 14) { "main.py uses codec id 14 for G.711 A-law" }
        require(audioSampleBits == 16) { "working claim advertises SB=16" }
        require(audioSampleRate == 8000) { "working claim advertises SR=8000" }
    }
}
