package com.voidnullvalue.icseelocal.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * Android Keystore-backed AES-256-GCM encryption for camera credentials.
 * The key is generated inside the secure keystore and never leaves it --
 * only ciphertext (safe to persist in ordinary app storage) crosses this
 * boundary.
 */
class KeystoreCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) {
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedBlob(iv = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    fun decrypt(blob: EncryptedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "icsee_local_credentials_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
