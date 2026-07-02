package com.voidnullvalue.icseelocal.ble

import java.nio.charset.StandardCharsets

/**
 * Wire format for this camera's BLE-based factory pairing / WiFi-provisioning
 * handshake -- reverse-engineered by reading the real vendor app's decompiled
 * sources directly (`com.utils.BleDistributionUtil`,
 * `com.lib.sdk.bean.bluetooth.XMBleHead`,
 * `com.manager.bluetooth.XMBleManager#parseBleWiFiConfigResult`), not
 * secondhand. See [[project-icsee-ble-pairing]] memory / PROTOCOL_NOTES.md
 * for the GATT UUIDs this pairs with (service 1910, write 2b11, notify
 * 2b10).
 *
 * Every frame, both directions, shares one envelope:
 * `8B8B(magic,2) version(1) cmdId(1) funId(2,BE) dataType(1) contentLen(2,BE)
 * content(contentLen) checksum(1, sum of every preceding byte mod 256)`.
 *
 * DNS-server list support is deliberately omitted: the vendor's own DNS
 * list-combining helper (`BleDistributionUtil.a(List)`) is flagged by the
 * decompiler itself as "decompiled incorrectly", so its exact byte layout
 * isn't trustworthy to replicate. Omitting DNS (empty list, same as the
 * vendor app's null-list case) produces a request byte-identical to what
 * `combineWiFiSSIDToHexStr(..., null, "")` emits, and the camera falls back
 * to DHCP-provided DNS, which is the common case anyway.
 */
object BleWifiProvisionCodec {
    private const val MAGIC: Byte = 0x8B.toByte()
    private const val HEADER_SIZE = 9
    private const val VERSION: Int = 0x02
    private const val CMD_SEND: Int = 0x01
    private const val WIFI_CONFIG_FUN_ID: Int = 0x0002
    private const val DATA_TYPE: Int = 0x00

    /** Builds the exact bytes to chunk-write to the write characteristic (2b11). */
    fun buildWifiConfigFrame(ssid: String, password: String, encryptType: Int): ByteArray {
        val content = buildContent(ssid, password, encryptType)
        val header = ByteArray(HEADER_SIZE).also { h ->
            h[0] = MAGIC
            h[1] = MAGIC
            h[2] = VERSION.toByte()
            h[3] = CMD_SEND.toByte()
            h[4] = ((WIFI_CONFIG_FUN_ID shr 8) and 0xFF).toByte()
            h[5] = (WIFI_CONFIG_FUN_ID and 0xFF).toByte()
            h[6] = DATA_TYPE.toByte()
            h[7] = ((content.size shr 8) and 0xFF).toByte()
            h[8] = (content.size and 0xFF).toByte()
        }
        val withoutChecksum = header + content
        return withoutChecksum + checksum(withoutChecksum)
    }

    private fun buildContent(ssid: String, password: String, encryptType: Int): ByteArray {
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        require(ssidBytes.size <= 0xFF) { "SSID too long to length-prefix in one byte" }
        require(passwordBytes.size <= 0xFF) { "Password too long to length-prefix in one byte" }
        return byteArrayOf(ssidBytes.size.toByte()) + ssidBytes +
            byteArrayOf(passwordBytes.size.toByte()) + passwordBytes +
            byteArrayOf(encryptType.toByte()) +
            byteArrayOf(0) + // DNS list length -- always empty, see class doc
            byteArrayOf(0) // extra field length -- vendor always sends "" here too
    }

    fun checksum(bytes: ByteArray): Byte {
        var sum = 0
        for (b in bytes) sum += (b.toInt() and 0xFF)
        return (sum and 0xFF).toByte()
    }

    /**
     * Mirrors `BleDistributionUtil.getWifiEncrypt(String)` exactly, which reads an
     * Android `ScanResult.capabilities`-shaped string like `[WPA2-PSK-CCMP][ESS]`.
     * This app's pairing UI only collects a typed SSID/password (no WiFi scan step),
     * so [inferEncryptType] is what's actually used; this is kept for parity/fidelity
     * and in case a real capabilities string becomes available later.
     */
    fun wifiEncryptType(capabilities: String): Int = when {
        capabilities.contains("WAPI") && capabilities.contains("PSK") -> 8
        capabilities.contains("WPA3") && capabilities.contains("WPA2") && capabilities.contains("PSK") -> 7
        capabilities.contains("WPA3") && capabilities.contains("PSK") -> 6
        capabilities.contains("WPA2") && capabilities.contains("ENTERPRISE") -> 5
        capabilities.contains("WPA2") && capabilities.replace("WPA2", "").let { it.contains("WPA") && it.contains("PSK") } -> 4
        capabilities.contains("WPA2") && capabilities.contains("PSK") -> 3
        capabilities.contains("WPA_PSK") -> 2
        capabilities.contains("WEP") -> 1
        else -> 0
    }

    /** No capabilities string available from a typed SSID/password -- WPA2-PSK (3) covers the overwhelming common case. */
    fun inferEncryptType(password: String): Int = if (password.isEmpty()) 0 else 3

    data class Frame(val version: Int, val cmdId: Int, val funId: Int, val dataType: Int, val content: ByteArray)

    /** Returns the total frame length once enough bytes (>= the 9-byte header) have arrived, else null. */
    fun expectedFrameLength(buffered: ByteArray): Int? {
        if (buffered.size < HEADER_SIZE) return null
        if (buffered[0] != MAGIC || buffered[1] != MAGIC) return null
        val contentLen = ((buffered[7].toInt() and 0xFF) shl 8) or (buffered[8].toInt() and 0xFF)
        return HEADER_SIZE + contentLen + 1
    }

    /** Parses (and checksum-validates) a complete frame. Returns null if malformed or the checksum doesn't match. */
    fun parseFrame(bytes: ByteArray): Frame? {
        val totalLen = expectedFrameLength(bytes) ?: return null
        if (bytes.size < totalLen) return null
        val expected = checksum(bytes.copyOfRange(0, totalLen - 1))
        if (bytes[totalLen - 1] != expected) return null
        val contentLen = totalLen - HEADER_SIZE - 1
        return Frame(
            version = bytes[2].toInt() and 0xFF,
            cmdId = bytes[3].toInt() and 0xFF,
            funId = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF),
            dataType = bytes[6].toInt() and 0xFF,
            content = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + contentLen),
        )
    }

    sealed class WifiConfigAck {
        data class Success(
            val assignedUsername: String,
            val assignedPassword: String,
            /** Vendor field name suggests a MAC, but a separate 6-byte MAC follows -- true meaning unconfirmed. */
            val deviceIdentifier: String,
            val ip: String,
            val mac: String,
            val devToken: String?,
        ) : WifiConfigAck()
        data class Failure(val errorCode: Int) : WifiConfigAck()
    }

    /** Mirrors `XMBleManager.parseBleWiFiConfigResult`'s offset walk exactly, translated from hex-string to byte-array indexing. */
    fun parseWifiConfigAck(content: ByteArray): WifiConfigAck? {
        if (content.isEmpty()) return null
        var offset = 0
        val resultCode = content[offset].toInt() and 0xFF
        offset += 1
        if (resultCode != 0) return WifiConfigAck.Failure(resultCode)

        fun readLengthPrefixed(): String {
            val len = content[offset].toInt() and 0xFF
            offset += 1
            val value = String(content, offset, len, StandardCharsets.UTF_8)
            offset += len
            return value
        }

        if (content.size < offset + 1) return null
        val username = readLengthPrefixed()
        if (content.size < offset + 1) return null
        val password = readLengthPrefixed()
        if (content.size < offset + 1) return null
        val deviceIdentifier = readLengthPrefixed()

        if (content.size < offset + 4) return null
        val ipBytes = content.copyOfRange(offset, offset + 4)
        offset += 4
        val ip = "${ipBytes[3].toInt() and 0xFF}.${ipBytes[2].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}.${ipBytes[0].toInt() and 0xFF}"

        if (content.size < offset + 6) return null
        val macBytes = content.copyOfRange(offset, offset + 6)
        offset += 6
        val mac = macBytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

        val devToken = if (content.size > offset) {
            val tokenLen = content[offset].toInt() and 0xFF
            offset += 1
            if (tokenLen > 0 && content.size >= offset + tokenLen) {
                String(content, offset, tokenLen, StandardCharsets.UTF_8)
            } else {
                null
            }
        } else {
            null
        }

        return WifiConfigAck.Success(
            assignedUsername = username.ifBlank { "admin" },
            assignedPassword = password,
            deviceIdentifier = deviceIdentifier,
            ip = ip,
            mac = mac,
            devToken = devToken,
        )
    }
}
