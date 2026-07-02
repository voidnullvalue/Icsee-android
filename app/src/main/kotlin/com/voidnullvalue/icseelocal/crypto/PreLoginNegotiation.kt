package com.voidnullvalue.icseelocal.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.interfaces.RSAPublicKey

/**
 * Message 1011 (pre-login negotiate response). Field shape and values
 * confirmed by a live probe against the target camera on 2026-07-01 (see
 * PROTOCOL_NOTES.md) -- this is not the task brief's example, it is the
 * camera's real response:
 *
 * ```
 * { "Bits": 1024, "CommunicateBits": 128, "CommunicateEncryptAlgo": "AES",
 *   "EncryptAlgo": "RSA_V1.5", "NotEncryptMsgID": [1000, 1001, ...],
 *   "PublicKey": "<256 hex chars>,010001", "Ret": 100 }
 * ```
 *
 * The RSA public key rotates on every negotiation (confirmed: two live
 * probes back to back returned two different moduli).
 */
data class PreLoginNegotiation(
    val bits: Int,
    val communicateBits: Int,
    val communicateEncryptAlgo: String,
    val encryptAlgo: String,
    val notEncryptMessageIds: Set<Int>,
    /** Null when [ret] != 100 or the field was unparseable -- there is no meaningful key on failure. */
    val publicKey: RSAPublicKey?,
    val ret: Int,
) {
    val success: Boolean get() = ret == 100 && publicKey != null
}

@Serializable
internal data class PreLoginNegotiationDto(
    @SerialName("Bits") val bits: Int = 0,
    @SerialName("CommunicateBits") val communicateBits: Int = 0,
    @SerialName("CommunicateEncryptAlgo") val communicateEncryptAlgo: String = "",
    @SerialName("EncryptAlgo") val encryptAlgo: String = "",
    @SerialName("NotEncryptMsgID") val notEncryptMsgId: List<Int> = emptyList(),
    @SerialName("PublicKey") val publicKey: String = "",
    @SerialName("Ret") val ret: Int = 0,
)

object PreLoginNegotiationParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(jsonText: String): PreLoginNegotiation? {
        val dto = runCatching { json.decodeFromString(PreLoginNegotiationDto.serializer(), jsonText) }.getOrNull()
            ?: return null
        val key = if (dto.ret == 100 && dto.publicKey.isNotBlank()) {
            runCatching { DvripRsaPublicKey.parse(dto.publicKey) }.getOrNull()
        } else {
            null
        }
        return PreLoginNegotiation(
            bits = dto.bits,
            communicateBits = dto.communicateBits,
            communicateEncryptAlgo = dto.communicateEncryptAlgo,
            encryptAlgo = dto.encryptAlgo,
            notEncryptMessageIds = dto.notEncryptMsgId.toSet(),
            publicKey = key,
            ret = dto.ret,
        )
    }
}
