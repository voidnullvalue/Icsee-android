package com.voidnullvalue.icseelocal.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class SofiaHashTest {

    @Test
    fun `matches the algorithm independently confirmed live, native SDK, and web UI JS`() {
        // Synthetic test vectors (not real device credentials), computed
        // independently with the same algorithm in Python and cross-checked
        // against libFunSDK.so's XMMD5Encrypt disassembly and the camera's
        // own web UI JS (MD5_8) -- the algorithm itself was live-confirmed
        // by a real successful login using a real (not-committed) password;
        // see PROTOCOL_NOTES.md "Login".
        assertEquals("fvkkGRg2", SofiaHash.hash("hunter2"))
        assertEquals("AYtHPeb1", SofiaHash.hash("correcthorse"))
        assertEquals("XtHEqckh", SofiaHash.hash("testpass123"))
    }

    @Test
    fun `always produces exactly 8 characters from the confirmed alphabet`() {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toSet()
        for (pw in listOf("", "a", "admin", "password123", "testuser")) {
            val hash = SofiaHash.hash(pw)
            assertEquals(8, hash.length)
            assertEquals(true, hash.all { it in alphabet })
        }
    }
}
