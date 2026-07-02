package com.voidnullvalue.icseelocal.dvrip

/**
 * A complete DVRIP frame: header plus raw payload bytes. [payload] may be
 * plaintext JSON, base64 ciphertext, or opaque binary media -- this type
 * makes no assumption about which; see [DvripPayloads] and `SessionCrypto`.
 */
class DvripFrame(
    val header: DvripHeader,
    val payload: ByteArray,
) {
    fun encode(): ByteArray = header.encode() + payload

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DvripFrame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    override fun toString(): String =
        "DvripFrame(session=${header.sessionHex}, seq=${header.sequence}, " +
            "msgId=${header.messageId}, len=${header.payloadLength})"

    companion object {
        fun of(session: UInt, sequence: UInt, messageId: Int, payload: ByteArray, type: Int = DvripHeader.DEFAULT_TYPE): DvripFrame {
            val header = DvripHeader(type, session, sequence, messageId, payload.size)
            return DvripFrame(header, payload)
        }
    }
}
