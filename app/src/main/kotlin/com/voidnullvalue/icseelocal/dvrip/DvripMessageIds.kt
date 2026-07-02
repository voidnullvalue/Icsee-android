package com.voidnullvalue.icseelocal.dvrip

/**
 * Message id catalog. See PROTOCOL_NOTES.md for the pcap evidence behind
 * each entry -- ids marked "inferred" were seen on the wire but their
 * semantics come only from request/response ordering, not from readable
 * content (their payloads are encrypted).
 */
object DvripMessageIds {
    const val LOGIN_REQUEST = 1000
    const val LOGIN_RESPONSE = 1001

    const val KEEPALIVE_REQUEST = 1006
    /** Inferred from ordering only -- content is encrypted, never independently confirmed. */
    const val KEEPALIVE_RESPONSE = 1007

    /** Per task brief; never observed in the capture (see PROTOCOL_NOTES.md "Login"). */
    const val PRE_LOGIN_NEGOTIATE_REQUEST = 1010
    const val PRE_LOGIN_NEGOTIATE_RESPONSE = 1011

    const val PTZ_CONTROL_REQUEST = 1400
    /** Inferred from ordering; Ret value not independently confirmed (encrypted). */
    const val PTZ_CONTROL_RESPONSE = 1401

    /** OPMonitor claim/start request. Confirmed plaintext JSON in capture. */
    const val MONITOR_REQUEST = 1413
    /** OPMonitor claim/start response. Confirmed plaintext JSON in capture. */
    const val MONITOR_RESPONSE = 1414

    /** Combined video+audio media stream, chunked at up to 8192 payload bytes. */
    const val MEDIA_STREAM = 1412

    /**
     * OPTalk lifecycle control (`{"Name":"OPTalk","OPTalk":{"Action":...}}`),
     * sent on the *control* connection. `Action:"Start"` is the step that
     * actually opens the camera's speaker for upstream audio -- LIVE-CONFIRMED
     * 2026-07-02: the [TALK_CLAIM_REQUEST] claim alone returns Ret:100 but plays
     * nothing; a plaintext OPTalk `Start` here returns Ret:100 with the camera's
     * expected AudioFormat and the speaker then plays the streamed frames.
     * `Action:"Stop"` releases it. Response comes back on [OPTALK_CONTROL_RESPONSE].
     */
    const val OPTALK_CONTROL_REQUEST = 1430

    const val TALK_CLAIM_REQUEST = 1434
    const val TALK_CLAIM_RESPONSE = 1435
    /** OPTalk control response (Ret for [OPTALK_CONTROL_REQUEST]); also a talk teardown ack. */
    const val OPTALK_CONTROL_RESPONSE = 1431
    /** Also observed as a talk teardown acknowledgement. */
    const val TALK_ACK = 1431
    const val TALK_AUDIO_UPSTREAM = 1432
    const val TALK_AUDIO_DOWNSTREAM = 1433

    /** Client's UDP discovery probe (zero-length payload), broadcast to 255.255.255.255:34569. */
    const val DISCOVERY_PROBE = 1530

    /**
     * Message ids the task brief advertises as not passing through the
     * generic post-login AES envelope. Note this does *not* mean their
     * bytes are unencrypted plaintext in every case: message 1000's payload
     * is itself a base64-encoded ~432-byte encrypted blob in the capture,
     * but that encryption is performed by the login flow itself (RSA/AES
     * credential wrapping), not by [com.voidnullvalue.icseelocal.crypto.SessionCrypto]'s
     * generic per-message envelope. `shouldEncrypt()` on that interface is
     * about the generic envelope only.
     */
    val TRANSPORT_UNENCRYPTED_IDS: Set<Int> = setOf(
        1000, 1001, 1008, 1009, 1010, 1011,
        1050, 1054,
        1412, 1413, 1414,
        1422, 1424, 1425, 1426,
        1432, 1433, 1434, 1435,
        1449, 1522, 1572, 1576, 1580, 1582,
        1645, 2062, 2063, 2123, 2140, 3016, 3502,
    )
}
