package com.voidnullvalue.icseelocal.crypto

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES implementation of [SessionCrypto] using only `javax.crypto` (no
 * custom AES primitives). The transform/IV strategy is **configurable and
 * not yet confirmed against the target camera** -- see PROTOCOL_NOTES.md
 * "Post-login encryption": ciphertext lengths observed on the wire are
 * exact multiples of 16 bytes, consistent with either CBC or ECB and with
 * either no padding or padding that happens to land on a block boundary in
 * every sample captured. This class does not guess a default; callers must
 * supply the key and an explicit [AesParameters], and [shouldEncrypt] is
 * driven by the *live-confirmed* `NotEncryptMsgID` set from message 1011
 * rather than a hardcoded list, so it stays correct even if firmware
 * changes it.
 *
 * [AesParameters.fixedIv] non-null means "use exactly this IV, don't
 * transmit it" (e.g. an all-zero IV); null with [AesParameters.ivSizeBytes]
 * > 0 means "generate a random IV per message and prepend it to the
 * ciphertext"; null with ivSizeBytes == 0 means no IV at all (ECB).
 */
data class AesParameters(
    val transformation: String,
    val ivSizeBytes: Int = 0,
    val fixedIv: ByteArray? = null,
)

class AesSessionCrypto(
    private val key: ByteArray,
    private val params: AesParameters,
    private val notEncryptMessageIds: Set<Int> = DvripMessageIds.TRANSPORT_UNENCRYPTED_IDS,
    private val random: SecureRandom = SecureRandom(),
) : SessionCrypto {

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 128/192/256 bits, got ${key.size * 8}" }
    }

    override fun shouldEncrypt(messageId: Int): Boolean = messageId !in notEncryptMessageIds

    override fun encrypt(messageId: Int, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(params.transformation)
        val keySpec = SecretKeySpec(key, "AES")
        return when {
            params.fixedIv != null -> {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(params.fixedIv))
                cipher.doFinal(plaintext)
            }
            params.ivSizeBytes > 0 -> {
                val iv = ByteArray(params.ivSizeBytes).also { random.nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
                iv + cipher.doFinal(plaintext)
            }
            else -> {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                cipher.doFinal(plaintext)
            }
        }
    }

    override fun decrypt(messageId: Int, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(params.transformation)
        val keySpec = SecretKeySpec(key, "AES")
        return when {
            params.fixedIv != null -> {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(params.fixedIv))
                cipher.doFinal(ciphertext)
            }
            params.ivSizeBytes > 0 -> {
                require(ciphertext.size > params.ivSizeBytes) { "ciphertext too short to contain IV" }
                val iv = ciphertext.copyOfRange(0, params.ivSizeBytes)
                val body = ciphertext.copyOfRange(params.ivSizeBytes, ciphertext.size)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
                cipher.doFinal(body)
            }
            else -> {
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                cipher.doFinal(ciphertext)
            }
        }
    }

    companion object {
        /** Zero-IV CBC, no padding -- consistent with observed ciphertext lengths, unverified against real traffic. */
        fun candidateCbcZeroIvNoPadding() = AesParameters("AES/CBC/NoPadding", fixedIv = ByteArray(16))

        /** ECB, no padding -- also consistent with observed lengths; ECB's determinism matches the repeated-identical-ciphertext observation for repeated identical PTZ acks. Unverified. */
        val CANDIDATE_ECB_NO_PADDING = AesParameters("AES/ECB/NoPadding")
    }
}
