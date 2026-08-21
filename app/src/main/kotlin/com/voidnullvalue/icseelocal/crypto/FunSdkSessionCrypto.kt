package com.voidnullvalue.icseelocal.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exact FunSDK post-login AES envelope recovered from libFunSDK.so.
 *
 * FunSDK treats command JSON as a C string: the terminating NUL is included
 * in the encrypted plaintext length, then the buffer is zero-filled to the
 * next 16-byte boundary and encrypted with AES-128-CBC using an all-zero IV.
 * The DVRIP layer base64-encodes the returned ciphertext separately.
 */
class FunSdkSessionCrypto(
    key: ByteArray,
    private val notEncryptMessageIds: Set<Int>,
) : SessionCrypto {
    private val key = key.copyOf()

    init {
        require(this.key.size == AES_BLOCK_SIZE) { "FunSDK CommunicateKey must be exactly 128 bits" }
    }

    override fun shouldEncrypt(messageId: Int): Boolean = messageId !in notEncryptMessageIds

    override fun encrypt(messageId: Int, plaintext: ByteArray): ByteArray =
        encryptFunSdkCString(key, plaintext)

    override fun decrypt(messageId: Int, ciphertext: ByteArray): ByteArray =
        decryptFunSdkCString(key, ciphertext)

    companion object {
        const val BOOTSTRAP_KEY_ASCII = "dashoiahfarqdasr"
        private const val AES_BLOCK_SIZE = 16
        private val ZERO_IV = ByteArray(AES_BLOCK_SIZE)

        fun bootstrapEncrypt(plaintext: ByteArray): ByteArray =
            encryptFunSdkCString(BOOTSTRAP_KEY_ASCII.toByteArray(Charsets.US_ASCII), plaintext)

        fun bootstrapDecrypt(ciphertext: ByteArray): ByteArray =
            decryptFunSdkCString(BOOTSTRAP_KEY_ASCII.toByteArray(Charsets.US_ASCII), ciphertext)

        private fun encryptFunSdkCString(key: ByteArray, plaintext: ByteArray): ByteArray {
            val cStringLength = plaintext.size + 1
            val paddedLength = ((cStringLength + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE) * AES_BLOCK_SIZE
            val padded = ByteArray(paddedLength)
            plaintext.copyInto(padded)
            // padded[plaintext.size] is the C-string NUL; remaining bytes are already zero.
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ZERO_IV))
            return cipher.doFinal(padded)
        }

        private fun decryptFunSdkCString(key: ByteArray, ciphertext: ByteArray): ByteArray {
            require(ciphertext.isNotEmpty() && ciphertext.size % AES_BLOCK_SIZE == 0) {
                "FunSDK AES ciphertext must be a non-empty multiple of 16 bytes"
            }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ZERO_IV))
            val plaintext = cipher.doFinal(ciphertext)
            var end = plaintext.size
            while (end > 0 && plaintext[end - 1] == 0.toByte()) end--
            return plaintext.copyOf(end)
        }
    }
}
