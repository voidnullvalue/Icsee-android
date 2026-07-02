package com.voidnullvalue.icseelocal.crypto

/**
 * Generic per-message envelope applied to DVRIP payloads after login. Not
 * responsible for the login message itself -- see PROTOCOL_STATUS.md and
 * DvripMessageIds.TRANSPORT_UNENCRYPTED_IDS for why message 1000 is special.
 */
interface SessionCrypto {
    fun shouldEncrypt(messageId: Int): Boolean
    fun encrypt(messageId: Int, plaintext: ByteArray): ByteArray
    fun decrypt(messageId: Int, ciphertext: ByteArray): ByteArray
}

/** Used before a session has negotiated any crypto -- shouldEncrypt is always false. */
object NullSessionCrypto : SessionCrypto {
    override fun shouldEncrypt(messageId: Int): Boolean = false
    override fun encrypt(messageId: Int, plaintext: ByteArray): ByteArray =
        throw IllegalStateException("no session crypto negotiated yet")
    override fun decrypt(messageId: Int, ciphertext: ByteArray): ByteArray =
        throw IllegalStateException("no session crypto negotiated yet")
}
