package com.voidnullvalue.icseelocal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TalkAudioFrameTest {

    @Test
    fun `wrap produces the exact 8-byte header observed on the wire`() {
        // Real captured header, byte-for-byte: 00 00 01 fa 0e 02 40 01 (see PROTOCOL_NOTES.md).
        val expectedHeaderHex = "000001fa0e024001"
        val wrapped = TalkAudioFrame.wrap(ByteArray(320) { 0xD5.toByte() })
        val headerHex = wrapped.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHeaderHex, headerHex)
        assertEquals(328, wrapped.size)
    }

    @Test
    fun `unwrap recovers the original A-law payload`() {
        val payload = ByteArray(320) { (it % 251).toByte() }
        val wrapped = TalkAudioFrame.wrap(payload)
        assertArrayEquals(payload, TalkAudioFrame.unwrap(wrapped))
    }

    @Test
    fun `rejects a payload that is not exactly 320 bytes`() {
        assertThrows(IllegalArgumentException::class.java) { TalkAudioFrame.wrap(ByteArray(319)) }
        assertThrows(IllegalArgumentException::class.java) { TalkAudioFrame.wrap(ByteArray(321)) }
    }

    @Test
    fun `unwrap returns null for malformed frames`() {
        assertNull(TalkAudioFrame.unwrap(ByteArray(100)))
        assertNull(TalkAudioFrame.unwrap(ByteArray(328))) // wrong marker (all zero, not 00 00 01)
    }
}
