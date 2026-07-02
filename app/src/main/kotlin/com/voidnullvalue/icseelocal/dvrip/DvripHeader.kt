package com.voidnullvalue.icseelocal.dvrip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DVRIP frame header, 20 bytes, little-endian.
 *
 * Confirmed byte-for-byte against every frame in /root/pcap.pcap (see
 * PROTOCOL_NOTES.md): magic 0xFF, type observed as 0x01 on every captured
 * frame, session/sequence/message-id/payload-length as specified.
 *
 * offset 0   1B  magic = 0xFF
 * offset 1   1B  type/reserved
 * offset 2   2B  reserved = 0
 * offset 4   4B  session id, LE
 * offset 8   4B  sequence, LE
 * offset 12  2B  reserved = 0
 * offset 14  2B  message id, LE
 * offset 16  4B  payload length, LE
 */
data class DvripHeader(
    val type: Int,
    val session: UInt,
    val sequence: UInt,
    val messageId: Int,
    val payloadLength: Int,
) {
    init {
        require(type in 0..0xFF) { "type byte out of range: $type" }
        require(messageId in 0..0xFFFF) { "message id out of range: $messageId" }
        require(payloadLength in 0..MAX_PAYLOAD_LEN) { "payload length out of bounds: $payloadLength" }
    }

    val sessionHex: String get() = "0x%08X".format(session.toLong())

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByte())
        buf.put(type.toByte())
        buf.putShort(0)
        buf.putInt(session.toInt())
        buf.putInt(sequence.toInt())
        buf.putShort(0)
        buf.putShort(messageId.toShort())
        buf.putInt(payloadLength)
        return buf.array()
    }

    companion object {
        const val MAGIC = 0xFF
        const val HEADER_LEN = 20
        const val DEFAULT_TYPE = 0x01

        // Sanity bound only -- never observed anywhere near this in capture
        // (largest was an 8192-byte media chunk). Prevents a corrupt or
        // malicious header from triggering a huge allocation.
        const val MAX_PAYLOAD_LEN = 16 * 1024 * 1024

        /** Decodes the 20-byte header at [offset] in [bytes]. Does not consume the payload. */
        fun decode(bytes: ByteArray, offset: Int = 0): DvripHeader {
            require(offset >= 0) { "negative offset" }
            require(bytes.size - offset >= HEADER_LEN) {
                "need $HEADER_LEN bytes for header, have ${bytes.size - offset}"
            }
            val buf = ByteBuffer.wrap(bytes, offset, HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.get().toInt() and 0xFF
            if (magic != MAGIC) {
                throw DvripFramingException("bad magic 0x%02x at offset %d".format(magic, offset))
            }
            val type = buf.get().toInt() and 0xFF
            buf.short // reserved
            val session = buf.int.toUInt()
            val sequence = buf.int.toUInt()
            buf.short // reserved
            val messageId = buf.short.toInt() and 0xFFFF
            val payloadLength = buf.int
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LEN) {
                throw DvripFramingException("payload length $payloadLength out of bounds at offset $offset")
            }
            return DvripHeader(type, session, sequence, messageId, payloadLength)
        }
    }
}

class DvripFramingException(message: String) : Exception(message)
