package com.voidnullvalue.icseelocal.config

import android.util.Base64

object XiongmaiCrypto {
    // Xiongmai default XOR key for PasswordV2 decryption (found in all devices)
    private val XOR_KEY = byteArrayOf(0x70.toByte(), 0x92.toByte(), 0x86.toByte(), 0x0e.toByte(), 0x89.toByte(), 0x63.toByte())

    /**
     * Decrypt Xiongmai PasswordV2 field using XOR with hardcoded key.
     * The PasswordV2 field contains the plaintext password XOR'd with a repeating key,
     * padded to 16 bytes with encryption metadata.
     */
    fun decryptPasswordV2(passwordV2Base64: String): String? = try {
        val ciphertext = Base64.decode(passwordV2Base64, Base64.DEFAULT)

        // XOR decrypt using repeating key
        val decrypted = ByteArray(ciphertext.size) { i ->
            (ciphertext[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }

        // Extract password: take alphanumeric characters until first non-alphanumeric
        val password = StringBuilder()
        for (byte in decrypted) {
            val char = byte.toInt().toChar()
            when {
                char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z' -> password.append(char)
                else -> break // Stop at first non-alphanumeric
            }
        }

        password.toString().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
