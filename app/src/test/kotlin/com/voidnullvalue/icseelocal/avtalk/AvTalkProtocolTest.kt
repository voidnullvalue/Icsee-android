package com.voidnullvalue.icseelocal.avtalk

import com.voidnullvalue.icseelocal.crypto.FunSdkSessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime

class AvTalkProtocolTest {
    @Test
    fun `bootstrap AES matches recovered FunSDK vector`() {
        val plaintext = "{\"Name\":\"EncodeCapability\",\"SessionID\":\"0x0000000003\"}".toByteArray()
        val expected = hexToBytes(
            "7460ce27841c435b6960f46c6bd35d09737340d50f87f4a1cd289aef634fb70f" +
                "300677e2184d867b051b186f891f1bbf0355a77bf13fc9e763a980edf4fe3e38",
        )
        assertArrayEquals(expected, FunSdkSessionCrypto.bootstrapEncrypt(plaintext))
        assertArrayEquals(plaintext, FunSdkSessionCrypto.bootstrapDecrypt(expected))
    }

    @Test
    fun `AVTalk control JSON exactly matches recovered stock builder`() {
        assertEquals(
            "{\"Name\":\"AVTalk\",\"AVTalk\":{\"Action\":\"Claim\",\"Channel\":0,\"ProType\":0," +
                "\"Video\":{\"Enc\":\"H265\",\"W\":240,\"H\":320,\"FPS\":10}," +
                "\"Audio\":{\"Enc\":\"g711a\",\"SB\":16,\"SR\":8000}},\"SessionID\":\"0x000000003C\"}",
            AvTalkPayloads.claim(0x3cu),
        )
        assertEquals(
            "{\"Name\":\"EncodeCapability\",\"SessionID\":\"0x0000000003\"}",
            AvTalkPayloads.encodeCapability(3u),
        )
        val wire = AvTalkPayloads.claimWirePayload(0x3cu)
        assertEquals(0, wire.last().toInt())
        assertEquals(AvTalkPayloads.claim(0x3cu), wire.copyOf(wire.size - 1).toString(Charsets.UTF_8))
    }

    @Test
    fun `keyframe header matches CSTDStream NewFrame`() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x26)
        val frame = AvTalkFrameEncoder.h265KeyFrame(
            payload,
            timestamp = LocalDateTime.of(2026, 8, 21, 12, 34, 56),
        )
        assertEquals("000001fc010a1e28b8c82a6a05000000", toHex(frame.copyOfRange(0, 16)))
        assertArrayEquals(payload, frame.copyOfRange(16, frame.size))
    }

    @Test
    fun `interframe and A-law headers use native little-endian lengths`() {
        val p = AvTalkFrameEncoder.h265InterFrame(ByteArray(0x12345))
        assertEquals("000001fd45230100", toHex(p.copyOfRange(0, 8)))

        val audio = AvTalkFrameEncoder.alawAudio(ByteArray(320))
        assertEquals("000001fa0e024001", toHex(audio.copyOfRange(0, 8)))
    }

    @Test
    fun `media group sequence matches CXMDevPTL NewSeq arithmetic`() {
        val seq = FunSdkSequence()
        assertEquals(1016, seq.next()) // first return from initial state 1008
        assertEquals(1024, seq.next())
        assertEquals(1032, seq.next())

        val wrapping = FunSdkSequence(initial = 10000)
        assertEquals(1008, wrapping.next()) // reset to 1008
    }

    @Test
    fun `profile rejects codec declarations this implementation cannot emit`() {
        assertThrows(IllegalArgumentException::class.java) { AvTalkProfile(videoEncoding = "H264") }
        assertThrows(IllegalArgumentException::class.java) { AvTalkProfile(audioEncoding = "AAC") }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
