package com.voidnullvalue.icseelocal.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected hex vectors below were computed independently with a direct Python
 * port of the vendor's `BleDistributionUtil`/`XMBleHead` logic (see
 * PROTOCOL_NOTES.md "BLE pairing"), not hand-derived, so this locks in
 * byte-for-byte fidelity to the real app rather than just internal
 * self-consistency.
 */
class BleWifiProvisionCodecTest {
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `builds WPA2 frame matching independently-computed vendor-format hex`() {
        val frame = BleWifiProvisionCodec.buildWifiConfigFrame("Test", "hunter2", encryptType = 3)
        assertEquals("8B8B0201000200001004546573740768756E74657232030000A1", frame.toHex())
    }

    @Test
    fun `builds open-network frame with empty password matching vendor-format hex`() {
        val frame = BleWifiProvisionCodec.buildWifiConfigFrame("MyHomeWifi", "", encryptType = 0)
        assertEquals("8B8B0201000200000F0A4D79486F6D65576966690000000012", frame.toHex())
    }

    @Test
    fun `inferEncryptType defaults to open for blank password, WPA2-PSK otherwise`() {
        assertEquals(0, BleWifiProvisionCodec.inferEncryptType(""))
        assertEquals(3, BleWifiProvisionCodec.inferEncryptType("hunter2"))
    }

    @Test
    fun `wifiEncryptType mirrors vendor capability-string mapping`() {
        assertEquals(3, BleWifiProvisionCodec.wifiEncryptType("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(1, BleWifiProvisionCodec.wifiEncryptType("[WEP][ESS]"))
        assertEquals(0, BleWifiProvisionCodec.wifiEncryptType("[ESS]"))
        assertEquals(6, BleWifiProvisionCodec.wifiEncryptType("[WPA3-SAE-PSK][ESS]"))
    }

    @Test
    fun `build then parseFrame round-trips header and content`() {
        val frame = BleWifiProvisionCodec.buildWifiConfigFrame("Test", "hunter2", encryptType = 3)
        val parsed = BleWifiProvisionCodec.parseFrame(frame)
        assertNotNull(parsed)
        parsed!!
        assertEquals(0x02, parsed.version)
        assertEquals(0x01, parsed.cmdId)
        assertEquals(0x0002, parsed.funId)
        assertEquals(0x00, parsed.dataType)
        assertEquals("04546573740768756E74657232030000", parsed.content.toHex())
    }

    @Test
    fun `parseFrame rejects a corrupted checksum`() {
        val frame = BleWifiProvisionCodec.buildWifiConfigFrame("Test", "hunter2", encryptType = 3)
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertNull(BleWifiProvisionCodec.parseFrame(frame))
    }

    @Test
    fun `expectedFrameLength reports total length once the 9-byte header is available`() {
        val frame = BleWifiProvisionCodec.buildWifiConfigFrame("Test", "hunter2", encryptType = 3)
        assertNull(BleWifiProvisionCodec.expectedFrameLength(frame.copyOfRange(0, 8)))
        assertEquals(frame.size, BleWifiProvisionCodec.expectedFrameLength(frame.copyOfRange(0, 9)))
        // Still reports full length even with only the header buffered so far (content not yet arrived).
        assertEquals(frame.size, BleWifiProvisionCodec.expectedFrameLength(frame))
    }

    @Test
    fun `parseWifiConfigAck decodes a synthetic success response matching independently-computed vendor-format hex`() {
        val content = hexToBytes("000561646D696E085A78394B326D5071034944310104A8C000112233445506746F6B313233")
        val ack = BleWifiProvisionCodec.parseWifiConfigAck(content)
        assertTrue(ack is BleWifiProvisionCodec.WifiConfigAck.Success)
        val success = ack as BleWifiProvisionCodec.WifiConfigAck.Success
        assertEquals("admin", success.assignedUsername)
        assertEquals("Zx9K2mPq", success.assignedPassword)
        assertEquals("ID1", success.deviceIdentifier)
        assertEquals("192.168.4.1", success.ip)
        assertEquals("00:11:22:33:44:55", success.mac)
        assertEquals("tok123", success.devToken)
    }

    @Test
    fun `parseWifiConfigAck decodes a full frame end to end via parseFrame`() {
        val fullFrame = hexToBytes(
            "8B8B02030002000025000561646D696E085A78394B326D5071034944310104A8C000112233445506746F6B31323325",
        )
        val parsed = BleWifiProvisionCodec.parseFrame(fullFrame)
        assertNotNull(parsed)
        val ack = BleWifiProvisionCodec.parseWifiConfigAck(parsed!!.content)
        assertTrue(ack is BleWifiProvisionCodec.WifiConfigAck.Success)
        assertEquals("192.168.4.1", (ack as BleWifiProvisionCodec.WifiConfigAck.Success).ip)
    }

    @Test
    fun `parseWifiConfigAck surfaces a non-zero result code as failure`() {
        val ack = BleWifiProvisionCodec.parseWifiConfigAck(byteArrayOf(0x53))
        assertTrue(ack is BleWifiProvisionCodec.WifiConfigAck.Failure)
        assertEquals(0x53, (ack as BleWifiProvisionCodec.WifiConfigAck.Failure).errorCode)
    }
}
