package com.voidnullvalue.icseelocal.dvrip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Byte-for-byte fixtures below are lifted verbatim from
 * /root/pcap.pcap (see PROTOCOL_NOTES.md) -- not invented. No credentials
 * or key material appear in any fixture; the login-response fixture is a
 * plaintext JSON server response and the keepalive fixture is only used to
 * exercise header decoding (its ciphertext payload is opaque and unused).
 */
class DvripHeaderTest {

    // Real login response: session=0x1B, seq=1016, msg=1001, len=128, plaintext JSON.
    private val loginResponseHeaderHex = "ff0100001b000000f80300000000e903800000 00".replace(" ", "")

    // Real keepalive request: session=0x1B, seq=0, msg=1006, len=65, encrypted payload.
    private val keepaliveHeaderHex = "ff0100001b000000000000000000ee0341000000".replace(" ", "")

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `decodes real login response header`() {
        val header = DvripHeader.decode(hexToBytes(loginResponseHeaderHex))
        assertEquals(0x01, header.type)
        assertEquals(0x1Bu, header.session)
        assertEquals(1016u, header.sequence)
        assertEquals(DvripMessageIds.LOGIN_RESPONSE, header.messageId)
        assertEquals(128, header.payloadLength)
    }

    @Test
    fun `decodes real keepalive request header`() {
        val header = DvripHeader.decode(hexToBytes(keepaliveHeaderHex))
        assertEquals(0x1Bu, header.session)
        assertEquals(0u, header.sequence)
        assertEquals(DvripMessageIds.KEEPALIVE_REQUEST, header.messageId)
        assertEquals(65, header.payloadLength)
    }

    @Test
    fun `session id formats as 0x plus eight uppercase hex digits`() {
        val header = DvripHeader.decode(hexToBytes(loginResponseHeaderHex))
        assertEquals("0x0000001B", header.sessionHex)
    }

    @Test
    fun `encode is the exact inverse of decode for a real frame`() {
        val original = hexToBytes(loginResponseHeaderHex)
        val header = DvripHeader.decode(original)
        assertEquals(original.toList(), header.encode().toList())
    }

    @Test
    fun `round trips arbitrary values through encode and decode`() {
        val header = DvripHeader(
            type = 1,
            session = 0xDEADBEEFu,
            sequence = 0xCAFEBABEu,
            messageId = 1400,
            payloadLength = 12345,
        )
        val decoded = DvripHeader.decode(header.encode())
        assertEquals(header, decoded)
    }

    @Test
    fun `rejects invalid magic byte`() {
        val bad = hexToBytes(loginResponseHeaderHex)
        bad[0] = 0xAB.toByte()
        assertThrows(DvripFramingException::class.java) { DvripHeader.decode(bad) }
    }

    @Test
    fun `rejects excessive payload length`() {
        val bad = hexToBytes(loginResponseHeaderHex)
        // Set payload length field (offset 16..20) to a value far past the sanity bound.
        bad[16] = 0xFF.toByte()
        bad[17] = 0xFF.toByte()
        bad[18] = 0xFF.toByte()
        bad[19] = 0x7F.toByte()
        assertThrows(DvripFramingException::class.java) { DvripHeader.decode(bad) }
    }

    @Test
    fun `rejects negative-looking payload length`() {
        val bad = hexToBytes(loginResponseHeaderHex)
        bad[16] = 0x00; bad[17] = 0x00; bad[18] = 0x00; bad[19] = 0x80.toByte() // Int.MIN_VALUE-ish
        assertThrows(DvripFramingException::class.java) { DvripHeader.decode(bad) }
    }

    @Test
    fun `rejects buffer shorter than header length`() {
        assertThrows(IllegalArgumentException::class.java) { DvripHeader.decode(ByteArray(10)) }
    }

    @Test
    fun `little endian session and sequence decode correctly`() {
        // session bytes 78 56 34 12 -> 0x12345678
        val header = DvripHeader(1, 0x12345678u, 0x01020304u, 1, 0)
        val encoded = header.encode()
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), encoded.slice(4..7).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04, 0x03, 0x02, 0x01), encoded.slice(8..11).map { it.toInt() and 0xFF })
    }
}
