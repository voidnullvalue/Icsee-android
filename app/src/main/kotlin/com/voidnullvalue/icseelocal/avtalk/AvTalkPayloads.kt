package com.voidnullvalue.icseelocal.avtalk

/** Exact JSON field ordering/format used by the recovered FunSDK AVTalk builders. */
object AvTalkPayloads {
    fun sessionIdHex(sessionId: UInt): String = "0x%010X".format(sessionId.toLong())

    fun encodeCapability(sessionId: UInt): String =
        "{\"Name\":\"EncodeCapability\",\"SessionID\":\"${sessionIdHex(sessionId)}\"}"

    fun claim(sessionId: UInt, profile: AvTalkProfile = AvTalkProfile()): String =
        avTalkControl("Claim", sessionId, profile)

    fun start(sessionId: UInt, profile: AvTalkProfile = AvTalkProfile()): String =
        avTalkControl("Start", sessionId, profile)

    fun stop(sessionId: UInt, profile: AvTalkProfile = AvTalkProfile()): String =
        avTalkControl("Stop", sessionId, profile)

    /** 1417 is plaintext JSON followed by a single NUL (no LF) in FunSDK. */
    fun claimWirePayload(sessionId: UInt, profile: AvTalkProfile = AvTalkProfile()): ByteArray =
        claim(sessionId, profile).toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    private fun avTalkControl(action: String, sessionId: UInt, p: AvTalkProfile): String = buildString {
        append("{\"Name\":\"AVTalk\",\"AVTalk\":{\"Action\":\"")
        append(action)
        append("\",\"Channel\":")
        append(p.channel)
        append(",\"ProType\":")
        append(p.proType)
        if (p.useExt != null && p.useExt != 0) {
            append(",\"UseExt\":")
            append(p.useExt)
        }
        append(",\"Video\":{\"Enc\":\"")
        append(p.videoEncoding)
        append("\",\"W\":")
        append(p.width)
        append(",\"H\":")
        append(p.height)
        append(",\"FPS\":")
        append(p.fps)
        append("},\"Audio\":{\"Enc\":\"")
        append(p.audioEncoding)
        append("\",\"SB\":")
        append(p.audioSampleBits)
        append(",\"SR\":")
        append(p.audioSampleRate)
        append("}},\"SessionID\":\"")
        append(sessionIdHex(sessionId))
        append("\"}")
    }
}
