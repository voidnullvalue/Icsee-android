package com.voidnullvalue.icseelocal.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryBeaconParserTest {

    @Test
    fun `converts little endian HostIP hex to dotted quad per task brief example`() {
        assertEquals("192.168.1.100", DiscoveryBeaconParser.parseHostIp("0x6401A8C0"))
    }

    @Test
    fun `HostIP conversion is case insensitive and tolerates missing 0x prefix`() {
        assertEquals("192.168.1.100", DiscoveryBeaconParser.parseHostIp("6401a8c0"))
        assertEquals("192.168.1.100", DiscoveryBeaconParser.parseHostIp("0X6401A8C0"))
    }

    @Test
    fun `rejects malformed HostIP hex`() {
        assertNull(DiscoveryBeaconParser.parseHostIp("not-hex"))
        assertNull(DiscoveryBeaconParser.parseHostIp("0x123"))
        assertNull(DiscoveryBeaconParser.parseHostIp(""))
    }

    @Test
    fun `parses the beacon JSON shape given in the task brief`() {
        val jsonText = """
            {
              "NetWork.NetCommon": {
                "HostIP": "0x6401A8C0",
                "HostName": "camera_07c3",
                "HttpPort": 80,
                "MAC": "5c:4e:ee:72:07:c3",
                "MonMode": "TCP",
                "Pid": "A9A022913235C00M",
                "SN": "a44d13007be81c4d",
                "SSLPort": 8443,
                "TCPPort": 34567,
                "UDPPort": 34568,
                "Version": "V5.11.R02.000809V1.10010.346837.0000010"
              },
              "Ret": 100,
              "SessionID": "0x00000000"
            }
        """.trimIndent()
        val beacon = DiscoveryBeaconParser.parse(jsonText)
        requireNotNull(beacon)
        assertEquals("192.168.1.100", beacon.hostIp)
        assertEquals("camera_07c3", beacon.hostName)
        assertEquals(80, beacon.httpPort)
        assertEquals("5c:4e:ee:72:07:c3", beacon.mac)
        assertEquals("A9A022913235C00M", beacon.pid)
        assertEquals("a44d13007be81c4d", beacon.serialNumber)
        assertEquals(8443, beacon.sslPort)
        assertEquals(34567, beacon.tcpPort)
        assertEquals(34568, beacon.udpPort)
        assertEquals("V5.11.R02.000809V1.10010.346837.0000010", beacon.version)
        assertEquals("5c:4e:ee:72:07:c3", beacon.identityKey)
    }

    @Test
    fun `identity key falls back to serial then host when MAC is blank`() {
        val withoutMac = DiscoveryBeacon(
            hostIp = "192.168.1.100", hostName = "cam", httpPort = 80, mac = "",
            pid = "p", serialNumber = "abc123", sslPort = 8443, tcpPort = 34567, udpPort = 34568, version = "v",
        )
        assertEquals("abc123", withoutMac.identityKey)

        val bareIp = withoutMac.copy(serialNumber = "")
        assertEquals("192.168.1.100", bareIp.identityKey)
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(DiscoveryBeaconParser.parse("not json"))
        assertNull(DiscoveryBeaconParser.parse("""{"NoNetCommon": true}"""))
    }
}
