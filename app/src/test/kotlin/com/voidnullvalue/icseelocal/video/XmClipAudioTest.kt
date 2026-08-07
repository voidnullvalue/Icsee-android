package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmClipAudioTest {

    @Test
    fun `extracts confirmed FA A-law units with 3-byte start code`() {
        val alaw = ByteArray(320) { 0xD5.toByte() }
        val unit = byteArrayOf(0, 0, 1, 0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01) + alaw
        val padding = byteArrayOf(0, 0, 0, 1, 0xFC.toByte()) + ByteArray(20)
        val raw = padding + unit + padding + unit

        val extracted = XmClipAudio.extractAlaw(raw)
        assertEquals(640, extracted.size)
        assertTrue(extracted.all { it == 0xD5.toByte() })
    }

    @Test
    fun `extracts confirmed FA units with 4-byte start code`() {
        val alaw = ByteArray(320) { 0xA5.toByte() }
        val unit = byteArrayOf(0, 0, 0, 1, 0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01) + alaw
        val extracted = XmClipAudio.extractAlaw(unit)
        assertEquals(320, extracted.size)
        assertEquals(0xA5.toByte(), extracted[0])
    }

    @Test
    fun `returns empty when no FA audio present`() {
        val videoOnly = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03)
        assertTrue(XmClipAudio.extractAlaw(videoOnly).isEmpty())
    }
}
