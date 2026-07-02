package com.voidnullvalue.icseelocal.discovery

import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDiscoveryClientTest {

    @Test
    fun `probe frame matches the exact bytes captured on the wire`() {
        // Real captured probe: ff 00 0000 00000000 00000000 0000 fa05 00000000
        // (see PROTOCOL_NOTES.md "Discovery probe").
        val expectedHex = "ff00000000000000000000000000fa0500000000"
        val frame = DvripFrame.of(0u, 0u, DvripMessageIds.DISCOVERY_PROBE, ByteArray(0), type = 0)
        assertEquals(expectedHex, frame.encode().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `parses a beacon datagram containing a DVRIP-wrapped JSON payload`() {
        val json = """{"NetWork.NetCommon":{"HostIP":"0x6401A8C0","HostName":"camera_07c3",""" +
            """"HttpPort":80,"MAC":"5c:4e:ee:72:07:c3","Pid":"A9A022913235C00M",""" +
            """"SN":"a44d13007be81c4d","SSLPort":8443,"TCPPort":34567,"UDPPort":34568,""" +
            """"Version":"V5.11.R02.000809V1.10010.346837.0000010"},"Ret":100,"SessionID":"0x00000000"}"""
        val datagram = DvripFrame.of(0u, 0u, 1530, com.voidnullvalue.icseelocal.dvrip.DvripPayloads.encodeJson(json)).encode()
        val beacon = CameraDiscoveryClient.parseBeaconDatagram(datagram)
        requireNotNull(beacon)
        assertEquals("192.168.1.100", beacon.hostIp)
        assertEquals("5c:4e:ee:72:07:c3", beacon.mac)
    }

    @Test
    fun `returns null for a datagram too short to hold a header`() {
        assertNull(CameraDiscoveryClient.parseBeaconDatagram(ByteArray(4)))
    }

    @Test
    fun `returns null for a datagram with a truncated payload`() {
        val full = DvripFrame.of(0u, 0u, 1530, com.voidnullvalue.icseelocal.dvrip.DvripPayloads.encodeJson("""{"a":1}""")).encode()
        assertNull(CameraDiscoveryClient.parseBeaconDatagram(full.copyOfRange(0, full.size - 5)))
    }

    @Test
    fun `returns null for a non-DVRIP datagram`() {
        assertNull(CameraDiscoveryClient.parseBeaconDatagram(ByteArray(40) { 0x41 }))
    }
}
