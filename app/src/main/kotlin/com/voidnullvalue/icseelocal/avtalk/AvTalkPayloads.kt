package com.voidnullvalue.icseelocal.avtalk

import java.util.Locale

/**
 * Plaintext JSON bodies copied structurally from the working issue #6 main.py.
 * AVTalk control/claim/monitor/keepalive messages are UTF-8 plus ONE trailing NUL.
 */
object AvTalkPayloads {
    fun sessionIdHex10(sessionId: UInt): String =
        "0x%010x".format(Locale.US, sessionId.toLong() and 0xffff_ffffL)

    fun sessionIdHex8(sessionId: UInt): String =
        "0x%08x".format(Locale.US, sessionId.toLong() and 0xffff_ffffL)

    fun decoderPram(sessionId: UInt): String =
        "{\"Name\":\"DecoderPram\",\"SessionID\":\"${sessionIdHex10(sessionId)}\"}"

    fun opMonitor(sessionId: UInt, profile: AvTalkProfile = AvTalkProfile()): String =
        "{\"Name\":\"OPMonitor\",\"OPMonitor\":{\"Action\":\"Claim\",\"Action1\":\"Start\"," +
            "\"Parameter\":{\"Channel\":${profile.channel},\"CombinMode\":\"NONE\",\"StreamType\":\"Main\",\"TransMode\":\"TCP\"}}," +
            "\"SessionID\":\"${sessionIdHex10(sessionId)}\"}"

    fun claim(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): String =
        "{\"Name\":\"AVTalk\",\"AVTalk\":{\"Action\":\"Claim\",\"Channel\":${p.channel},\"ProType\":${p.proType}," +
            "\"Video\":{\"Enc\":\"${p.videoEncoding}\",\"W\":${p.width},\"H\":${p.height},\"FPS\":${p.fps}}," +
            "\"Audio\":{\"Enc\":\"${p.audioEncoding}\",\"SB\":${p.audioSampleBits},\"SR\":${p.audioSampleRate}}}," +
            "\"SessionID\":\"${sessionIdHex10(sessionId)}\"}"

    fun action(sessionId: UInt, action: String, p: AvTalkProfile = AvTalkProfile()): String {
        require(action == "Start" || action == "Stop") { "AVTalk action must be Start or Stop" }
        return "{\"Name\":\"AVTalk\",\"SessionID\":\"${sessionIdHex8(sessionId)}\",\"AVTalk\":{" +
            "\"Action\":\"$action\",\"Channel\":${p.channel},\"ProType\":${p.proType},\"UseExt\":${p.useExt},\"mediatype\":${p.mediaType}," +
            "\"Video\":{\"Enc\":\"${p.videoEncoding}\",\"W\":${p.width},\"H\":${p.height},\"FPS\":${p.fps}}," +
            "\"Audio\":{\"Enc\":\"${p.audioEncoding}\",\"SB\":${p.audioSampleBits},\"SR\":${p.audioSampleRate}}}}"
    }

    fun start(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): String = action(sessionId, "Start", p)
    fun stop(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): String = action(sessionId, "Stop", p)

    fun keepAlive(sessionId: UInt): String =
        "{\"Name\":\"KeepAlive\",\"SessionID\":\"${sessionIdHex10(sessionId)}\"}"

    fun wire(json: String): ByteArray = json.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    fun decoderPramWire(sessionId: UInt): ByteArray = wire(decoderPram(sessionId))
    fun opMonitorWire(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): ByteArray = wire(opMonitor(sessionId, p))
    fun claimWire(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): ByteArray = wire(claim(sessionId, p))
    fun startWire(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): ByteArray = wire(start(sessionId, p))
    fun stopWire(sessionId: UInt, p: AvTalkProfile = AvTalkProfile()): ByteArray = wire(stop(sessionId, p))
    fun keepAliveWire(sessionId: UInt): ByteArray = wire(keepAlive(sessionId))
}
