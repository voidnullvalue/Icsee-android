package com.voidnullvalue.icseelocal.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Message 1001 (login response). Field shape confirmed by a live
 * successful login against the target camera on 2026-07-01 (see
 * PROTOCOL_NOTES.md "Login -- LIVE AUTHENTICATION CONFIRMED"):
 *
 * ```
 * { "AdminToken": "<base64>", "AliveInterval": 30, "ChannelNum": 1,
 *   "DeviceType ": "IPC", "ExtraChannel": 0, "Ret": 100,
 *   "SessionID": "0x0000001d" }
 * ```
 *
 * Note the literal trailing space in `"DeviceType "` -- that's exactly
 * what the camera sends, not a transcription error. `AdminToken` is new
 * (not present in any of the task brief's example JSON or the pcap
 * evidence); its purpose is unconfirmed and it is not currently used by
 * this app for anything.
 */
data class LoginResponse(
    val ret: Int,
    val sessionId: UInt,
    val aliveIntervalSeconds: Int,
    val channelNum: Int,
    val adminToken: String?,
) {
    val success: Boolean get() = ret == 100
}

@Serializable
internal data class LoginResponseDto(
    @SerialName("Ret") val ret: Int = 0,
    @SerialName("SessionID") val sessionId: String = "0x00000000",
    @SerialName("AliveInterval") val aliveInterval: Int = 0,
    @SerialName("ChannelNum") val channelNum: Int = 0,
    @SerialName("AdminToken") val adminToken: String? = null,
)

object LoginResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(jsonText: String): LoginResponse? {
        val dto = runCatching { json.decodeFromString(LoginResponseDto.serializer(), jsonText) }.getOrNull()
            ?: return null
        val sessionId = runCatching {
            dto.sessionId.removePrefix("0x").removePrefix("0X").toUInt(16)
        }.getOrNull() ?: return null
        return LoginResponse(
            ret = dto.ret,
            sessionId = sessionId,
            aliveIntervalSeconds = dto.aliveInterval,
            channelNum = dto.channelNum,
            adminToken = dto.adminToken,
        )
    }
}
