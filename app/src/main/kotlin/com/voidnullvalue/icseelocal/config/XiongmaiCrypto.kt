package com.voidnullvalue.icseelocal.config

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reverse-engineered from libFunSDK.so's Fun_DecDevRandomUserInfo (called by the
 * factory app's "GetRandomUser" flow, see [[project-icsee-ble-pairing]]).
 *
 * The device encrypts its randomly-provisioned account as AES-128-CBC with a
 * zero IV, using a key built entirely from substrings of the device's own
 * serial number: key = SN[5:11] + SN[1:7] + SN[8:12] (16 ASCII chars). The
 * plaintext is a fixed-width, null-padded string: "p1:<user> p2:<pass> t:<token>".
 */
object XiongmaiCrypto {
    private val ZERO_IV = ByteArray(16)

    fun deriveRandomUserKey(serialNumber: String): String? {
        if (serialNumber.length < 12) return null
        return serialNumber.substring(5, 11) + serialNumber.substring(1, 7) + serialNumber.substring(8, 12)
    }

    /**
     * Decrypts the base64 "Info"/"InfoUser" field returned by the GetRandomUser
     * DVRIP command, returning (username, password) if the expected
     * "p1:... p2:... t:..." format is found.
     */
    fun decryptRandomUserInfo(infoBase64: String, serialNumber: String): Pair<String, String>? {
        val keyStr = deriveRandomUserKey(serialNumber) ?: return null
        return try {
            val ciphertext = Base64.decode(infoBase64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyStr.toByteArray(), "AES"), IvParameterSpec(ZERO_IV))
            val plaintext = String(cipher.doFinal(ciphertext), Charsets.US_ASCII)

            val user = Regex("p1:(\\S*)").find(plaintext)?.groupValues?.get(1)
            val pass = Regex("p2:(\\S*)").find(plaintext)?.groupValues?.get(1)
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) Pair(user, pass) else null
        } catch (e: Exception) {
            null
        }
    }
}
