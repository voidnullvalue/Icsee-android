package com.voidnullvalue.icseelocal.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed camera discovery beacon. Field shape matches the task brief's
 * example JSON (this app has not itself captured a real beacon -- see
 * PROTOCOL_NOTES.md "Discovery probe": zero UDP traffic to/from the camera
 * appears in /root/pcap.pcap, so only the client's outbound probe frame is
 * evidence-backed; the response shape here is implemented per the brief and
 * needs live confirmation).
 */
data class DiscoveryBeacon(
    val hostIp: String,
    val hostName: String,
    val httpPort: Int,
    val mac: String,
    val pid: String,
    val serialNumber: String,
    val sslPort: Int,
    val tcpPort: Int,
    val udpPort: Int,
    val version: String,
) {
    val identityKey: String get() = mac.ifBlank { serialNumber }.ifBlank { hostIp }
}

@Serializable
internal data class NetCommonDto(
    @SerialName("HostIP") val hostIp: String = "",
    @SerialName("HostName") val hostName: String = "",
    @SerialName("HttpPort") val httpPort: Int = 0,
    @SerialName("MAC") val mac: String = "",
    @SerialName("Pid") val pid: String = "",
    @SerialName("SN") val serialNumber: String = "",
    @SerialName("SSLPort") val sslPort: Int = 0,
    @SerialName("TCPPort") val tcpPort: Int = 0,
    @SerialName("UDPPort") val udpPort: Int = 0,
    @SerialName("Version") val version: String = "",
)

@Serializable
internal data class DiscoveryBeaconDto(
    @SerialName("NetWork.NetCommon") val netCommon: NetCommonDto? = null,
    @SerialName("Ret") val ret: Int = 0,
    @SerialName("SessionID") val sessionId: String = "",
)

object DiscoveryBeaconParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Converts a little-endian hex IPv4 string like "0x6401A8C0" to a
     * dotted-quad string. Confirmed by the task brief's own worked example:
     * 0x6401A8C0 -> 192.168.1.100 is exactly "reverse the byte pairs".
     */
    fun parseHostIp(hex: String): String? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.length != 8 || !cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val bytes = IntArray(4) { i -> cleaned.substring(i * 2, i * 2 + 2).toInt(16) }
        return bytes.reversed().joinToString(".")
    }

    fun parse(jsonText: String): DiscoveryBeacon? {
        val dto = runCatching { json.decodeFromString(DiscoveryBeaconDto.serializer(), jsonText) }.getOrNull()
            ?: return null
        val common = dto.netCommon ?: return null
        val ip = parseHostIp(common.hostIp) ?: return null
        return DiscoveryBeacon(
            hostIp = ip,
            hostName = common.hostName,
            httpPort = common.httpPort,
            mac = common.mac,
            pid = common.pid,
            serialNumber = common.serialNumber,
            sslPort = common.sslPort,
            tcpPort = common.tcpPort,
            udpPort = common.udpPort,
            version = common.version,
        )
    }
}
