package com.voidnullvalue.icseelocal.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiongmaiCryptoTest {

    @Test
    fun `obfuscateExUserMapPassword matches the vendor's own example`() {
        // From PASSWORD_CHANGE_RE.md / AbstractC4571f.u: u("test1234") == "0001GdVzdDEyMzQ="
        assertEquals("0001GdVzdDEyMzQ=", XiongmaiCrypto.obfuscateExUserMapPassword("test1234"))
    }

    @Test
    fun `obfuscateExUserMapPassword of blank password is blank`() {
        assertEquals("", XiongmaiCrypto.obfuscateExUserMapPassword(""))
    }

    @Test
    fun `deriveRandomUserKey concatenates the documented serial substrings`() {
        // Live-verified serial from project-icsee-random-user-decryption memory.
        assertEquals("3007be44d1307be8", XiongmaiCrypto.deriveRandomUserKey("a44d13007be81c4d"))
    }

    @Test
    fun `deriveRandomUserKey rejects a too-short serial`() {
        assertNull(XiongmaiCrypto.deriveRandomUserKey("short"))
    }

    @Test
    fun `decryptRandomUserInfo recovers the live-verified xkfu credentials`() {
        // Captured live against camera serial a44d13007be81c4d (2026-07-07):
        // GetRandomUser -> Info decrypts to "p1:xkfu p2:5xef5a t:5549\0..." -- see
        // PROTOCOL_NOTES.md "Recovering the real provisioned account".
        val result = XiongmaiCrypto.decryptRandomUserInfo(
            "J+8pYoGvW+3uz8VqkDbqECL5HSLMi7D3nMBlzFTDOhk=",
            "a44d13007be81c4d",
        )
        assertEquals(Pair("xkfu", "5xef5a"), result)
    }

    @Test
    fun `decryptRandomUserInfo returns null for garbage input`() {
        assertNull(XiongmaiCrypto.decryptRandomUserInfo("not-base64!!", "a44d13007be81c4d"))
        assertNull(XiongmaiCrypto.decryptRandomUserInfo("J+8pYoGvW+3uz8VqkDbqECL5HSLMi7D3nMBlzFTDOhk=", "short"))
    }
}
