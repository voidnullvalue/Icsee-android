package com.voidnullvalue.icseelocal.dvrip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DvripPayloadsTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `encodeJson appends 0x0A 0x00 terminator`() {
        val encoded = DvripPayloads.encodeJson("""{"a":1}""")
        assertEquals(0x0A, encoded[encoded.size - 2].toInt())
        assertEquals(0x00, encoded[encoded.size - 1].toInt())
    }

    @Test
    fun `decodes real captured login response JSON payload`() {
        // Real payload bytes from /root/pcap.pcap, message 1001 (see PROTOCOL_NOTES.md).
        val hex = "7b2022416c697665496e74657276616c22203a2033302c20224368616e6e656c4e756d22203a20" +
            "312c2022446576696365547970652022203a2022495043222c202245787472614368616e6e656c22" +
            "203a20302c202252657422203a203130302c202253657373696f6e494422203a20223078303030303030316222207d0a00"
        val decoded = DvripPayloads.decodeJsonOrNull(hexToBytes(hex))
        assertTrue(decoded != null && decoded.contains("\"Ret\" : 100"))
        assertTrue(decoded!!.contains("\"AliveInterval\" : 30"))
    }

    @Test
    fun `round trips encodeJson through decodeJsonOrNull`() {
        val json = """{"Name":"OPPTZControl"}"""
        val decoded = DvripPayloads.decodeJsonOrNull(DvripPayloads.encodeJson(json))
        assertEquals(json, decoded)
    }

    @Test
    fun `returns null for non-json binary payload`() {
        val binary = ByteArray(16) { it.toByte() }
        assertNull(DvripPayloads.decodeJsonOrNull(binary))
    }

    @Test
    fun `encodeBase64Text appends single 0x00 terminator, not 0x0A00`() {
        val encoded = DvripPayloads.encodeBase64Text("QUJD")
        assertEquals(5, encoded.size)
        assertEquals(0x00, encoded[4].toInt())
    }

    @Test
    fun `decodes real captured keepalive base64 payload text`() {
        // Real payload bytes from /root/pcap.pcap, message 1006 (encrypted; only the
        // base64 *text* framing is verified here, not the AES plaintext underneath).
        val hex = "6b65616c704c7973674b7a30446e6f394349536e6534332b30634749614161776a6f30773075" +
            "3732494b34796a31467a39386834515063747a612f586a6f625300"
        val decoded = DvripPayloads.decodeBase64TextOrNull(hexToBytes(hex))
        assertEquals("kealpLysgKz0Dno9CISne43+0cGIaAawjo0w0u72IK4yj1Fz98h4QPctza/XjobS", decoded)
    }

    @Test
    fun `returns null for empty payload`() {
        assertNull(DvripPayloads.decodeJsonOrNull(ByteArray(0)))
        assertNull(DvripPayloads.decodeBase64TextOrNull(ByteArray(0)))
    }
}
