package com.voidnullvalue.icseelocal.config

import java.util.Base64
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
            val ciphertext = Base64.getDecoder().decode(infoBase64)
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

    /**
     * The vendor's `u()` obfuscation (`AbstractC4571f.u`, see PASSWORD_CHANGE_RE.md) for
     * `System.ExUserMap`'s `Password` field. NOT encryption -- reversible with the same
     * swap, and the device derives its own `PasswordV2` from whatever is written here.
     * `u("") == ""`; otherwise `"0001" + base64(pw)` with the first two base64 chars
     * swapped. Verified against the vendor's own example: `u("test1234") == "0001GdVzdDEyMzQ="`.
     */
    fun obfuscateExUserMapPassword(password: String): String {
        if (password.isEmpty()) return ""
        val b64 = Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        val swapped = if (b64.length >= 2) "${b64[1]}${b64[0]}${b64.substring(2)}" else b64
        return "0001$swapped"
    }
}
