package com.voidnullvalue.icseelocal.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/**
 * Decodes the "PublicKey" field format confirmed by a live probe against
 * the target camera (192.168.1.100, see PROTOCOL_NOTES.md "Live
 * verification, 2026-07-01"): a single string, `<hex modulus>,<hex
 * exponent>`, e.g. modulus 256 hex chars (1024 bits) and exponent
 * `010001` (65537, the standard RSA public exponent). Uses only the
 * standard `java.security` RSA implementation -- no custom RSA primitives.
 */
object DvripRsaPublicKey {
    fun parse(publicKeyField: String): RSAPublicKey {
        val parts = publicKeyField.split(",")
        require(parts.size == 2) { "expected '<modulus_hex>,<exponent_hex>', got ${parts.size} comma-separated parts" }
        val modulus = BigInteger(parts[0], 16)
        val exponent = BigInteger(parts[1], 16)
        val spec = RSAPublicKeySpec(modulus, exponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }
}
