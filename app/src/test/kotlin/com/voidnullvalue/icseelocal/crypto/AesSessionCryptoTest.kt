package com.voidnullvalue.icseelocal.crypto

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AesSessionCryptoTest {

    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun `shouldEncrypt matches the live-confirmed NotEncryptMsgID set`() {
        val crypto = AesSessionCrypto(key, AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        assertFalse(crypto.shouldEncrypt(DvripMessageIds.LOGIN_REQUEST)) // 1000 is in the confirmed unencrypted set
        assertFalse(crypto.shouldEncrypt(DvripMessageIds.MEDIA_STREAM)) // 1412
        assertTrue(crypto.shouldEncrypt(DvripMessageIds.PTZ_CONTROL_REQUEST)) // 1400 confirmed NOT in the unencrypted set
        assertTrue(crypto.shouldEncrypt(DvripMessageIds.KEEPALIVE_REQUEST)) // 1006
    }

    @Test
    fun `ECB round trips a block-aligned plaintext`() {
        val crypto = AesSessionCrypto(key, AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        val plaintext = ByteArray(32) { (it * 7).toByte() }
        val ciphertext = crypto.encrypt(1400, plaintext)
        assertArrayEquals(plaintext, crypto.decrypt(1400, ciphertext))
    }

    @Test
    fun `zero-IV CBC round trips a block-aligned plaintext`() {
        val crypto = AesSessionCrypto(key, AesSessionCrypto.candidateCbcZeroIvNoPadding())
        val plaintext = ByteArray(48) { (it * 3).toByte() }
        val ciphertext = crypto.encrypt(1400, plaintext)
        assertArrayEquals(plaintext, crypto.decrypt(1400, ciphertext))
    }

    @Test
    fun `random-IV-prepended CBC round trips and produces different ciphertext each call`() {
        val crypto = AesSessionCrypto(key, AesParameters("AES/CBC/NoPadding", ivSizeBytes = 16))
        val plaintext = ByteArray(16) { 0x42 }
        val a = crypto.encrypt(1400, plaintext)
        val b = crypto.encrypt(1400, plaintext)
        assertFalse(a.contentEquals(b)) // random IV each time
        assertArrayEquals(plaintext, crypto.decrypt(1400, a))
        assertArrayEquals(plaintext, crypto.decrypt(1400, b))
    }

    @Test
    fun `rejects invalid key sizes`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AesSessionCrypto(ByteArray(10), AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        }
    }
}
