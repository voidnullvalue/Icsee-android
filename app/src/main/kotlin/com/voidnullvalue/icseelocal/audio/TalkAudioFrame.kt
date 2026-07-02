package com.voidnullvalue.icseelocal.audio

/**
 * Wraps 320 bytes of G.711 A-law PCM in the exact 8-byte sub-header
 * confirmed on the wire for both outbound (msg 1432) and inbound (msg 1433)
 * talk audio -- see PROTOCOL_NOTES.md "Talk channel". The header was
 * observed as a byte-for-byte **constant** across every one of the 80
 * audio frames in the capture: `00 00 01 FA 0E 02 40 01`. The semantic
 * meaning of bytes 2-5 beyond "type=audio marker, trailing 2 bytes =
 * payload length (0x0140 LE = 320)" is unresolved -- only one audio format
 * was ever exercised in the capture -- but since this app always uses that
 * exact format (G711_ALAW / 8-bit / 8kHz, matching the confirmed `OPTalk`
 * claim JSON), reusing the literal observed header for outbound frames is
 * reproducing real evidence, not guessing.
 */
object TalkAudioFrame {
    const val AUDIO_PAYLOAD_SIZE = 320

    /** 8-byte sub-header + 320-byte payload; the DVRIP frame adds its own 20-byte header for a 348-byte total, matching the task brief. */
    const val FRAME_TOTAL_SIZE = 328

    val CONFIRMED_HEADER: ByteArray = byteArrayOf(0x00, 0x00, 0x01, 0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01)

    fun wrap(alawSamples: ByteArray): ByteArray {
        require(alawSamples.size == AUDIO_PAYLOAD_SIZE) {
            "expected exactly $AUDIO_PAYLOAD_SIZE A-law bytes (the only size confirmed on the wire), got ${alawSamples.size}"
        }
        return CONFIRMED_HEADER + alawSamples
    }

    /** Extracts the raw A-law payload from an inbound (camera->client) talk frame, or null if it doesn't match the confirmed shape. */
    fun unwrap(frame: ByteArray): ByteArray? {
        if (frame.size != FRAME_TOTAL_SIZE) return null
        if (!frame.copyOfRange(0, 3).contentEquals(byteArrayOf(0, 0, 1))) return null
        return frame.copyOfRange(8, frame.size)
    }
}
