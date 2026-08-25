package com.voidnullvalue.icseelocal.avtalk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AvTalkProtocolTest {
    @Test
    fun `payloads match working main py shapes`() {
        val sid = 0x3cu
        assertEquals(
            "{\"Name\":\"DecoderPram\",\"SessionID\":\"0x000000003c\"}",
            AvTalkPayloads.decoderPram(sid),
        )
        assertEquals(
            "{\"Name\":\"OPMonitor\",\"OPMonitor\":{\"Action\":\"Claim\",\"Action1\":\"Start\",\"Parameter\":{\"Channel\":0,\"CombinMode\":\"NONE\",\"StreamType\":\"Main\",\"TransMode\":\"TCP\"}},\"SessionID\":\"0x000000003c\"}",
            AvTalkPayloads.opMonitor(sid),
        )
        assertEquals(
            "{\"Name\":\"AVTalk\",\"AVTalk\":{\"Action\":\"Claim\",\"Channel\":0,\"ProType\":0,\"Video\":{\"Enc\":\"H265\",\"W\":240,\"H\":320,\"FPS\":10},\"Audio\":{\"Enc\":\"g711a\",\"SB\":16,\"SR\":8000}},\"SessionID\":\"0x000000003c\"}",
            AvTalkPayloads.claim(sid),
        )
        assertEquals(
            "{\"Name\":\"AVTalk\",\"SessionID\":\"0x0000003c\",\"AVTalk\":{\"Action\":\"Start\",\"Channel\":0,\"ProType\":0,\"UseExt\":-1,\"mediatype\":0,\"Video\":{\"Enc\":\"H265\",\"W\":240,\"H\":320,\"FPS\":10},\"Audio\":{\"Enc\":\"g711a\",\"SB\":16,\"SR\":8000}}}",
            AvTalkPayloads.start(sid),
        )
        assertEquals(
            "{\"Name\":\"KeepAlive\",\"SessionID\":\"0x000000003c\"}",
            AvTalkPayloads.keepAlive(sid),
        )
        assertEquals(0, AvTalkPayloads.claimWire(sid).last().toInt())
    }

    @Test
    fun `key frame header is exactly main py 4s BBBB II layout`() {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x26)
        val frame = AvTalkFrameEncoder.h265KeyFrame(annexB, unixSeconds = 0x01020304L)
        assertEquals("000001fc030a1e280403020105000000", toHex(frame.copyOfRange(0, 16)))
        assertArrayEquals(annexB, frame.copyOfRange(16, frame.size))
    }

    @Test
    fun `inter frame uses uint32 little endian length`() {
        val frame = AvTalkFrameEncoder.h265InterFrame(ByteArray(0x12345))
        assertEquals("000001fd45230100", toHex(frame.copyOfRange(0, 8)))
    }

    @Test
    fun `audio header is exact working 320 byte A law wrapper`() {
        val frame = AvTalkFrameEncoder.alawAudio(ByteArray(320) { 0xD5.toByte() })
        assertEquals("000001fa0e024001", toHex(frame.copyOfRange(0, 8)))
        assertEquals(328, frame.size)
    }

    @Test
    fun `profile rejects formats not represented by main py`() {
        assertThrows(IllegalArgumentException::class.java) { AvTalkProfile(videoEncoding = "H264") }
        assertThrows(IllegalArgumentException::class.java) { AvTalkProfile(audioSampleRate = 16000) }
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
