package com.voidnullvalue.icseelocal.crypto

import java.security.MessageDigest

/**
 * The password transform used by this camera's plaintext login path
 * (message 1000, `LoginType: "DVRIP-Web"`, no RSA/AES negotiation) --
 * verified end-to-end against the target camera on 2026-07-01, see
 * PROTOCOL_NOTES.md "Login -- LIVE AUTHENTICATION CONFIRMED". MD5 the
 * password, then for each of the 8 byte-pairs of the digest, sum the pair
 * mod 62 and map to `0-9A-Za-z`.
 *
 * Cross-confirmed from three independent sources before it was tested
 * live: the camera's own web UI JavaScript (`MD5_8` in `js/main.js`), the
 * native vendor SDK (`libFunSDK.so`'s `XMMD5Encrypt`), and the actively
 * maintained `dbuezas/icsee-ptz` Home Assistant integration's
 * `sofia_hash`. All three implement the identical algorithm.
 */
object SofiaHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun hash(password: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val a = digest[2 * i].toInt() and 0xFF
            val b = digest[2 * i + 1].toInt() and 0xFF
            sb.append(CHARS[(a + b) % 62])
        }
        return sb.toString()
    }
}
