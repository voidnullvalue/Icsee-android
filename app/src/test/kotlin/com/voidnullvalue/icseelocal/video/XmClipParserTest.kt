package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmClipParserTest {

    @Test
    fun `parses interleaved Annex-B HEVC with XM wrappers`() {
        // Minimal fake: XM marker + VPS(32) + SPS(33) + PPS(34) + IDR(19) + TRAIL(1)
        val vps = nalHevc(32, byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val sps = nalHevc(33, minimalHevcSpsRbsp())
        val pps = nalHevc(34, byteArrayOf(0x10, 0x20))
        val idr = nalHevc(19, byteArrayOf(0x55, 0x66, 0x77))
        val trail = nalHevc(1, byteArrayOf(0x11))
        val xm = byteArrayOf(0xFC.toByte()) + ByteArray(12) { 0 }
        val raw = annexB(xm) + annexB(vps) + annexB(sps) + annexB(pps) + annexB(idr) + annexB(trail)

        val parsed = XmClipParser.parse(raw)
        assertTrue(parsed is XmClipParser.Parsed.Hevc)
        val hevc = (parsed as XmClipParser.Parsed.Hevc).track
        assertEquals(33, (hevc.sps[0].toInt() shr 1) and 0x3F)
        assertEquals(2, hevc.frames.size)
    }

    @Test
    fun `parses HEVC nested inside XM wrapper payload`() {
        val sps = nalHevc(33, minimalHevcSpsRbsp())
        val idr = nalHevc(19, byteArrayOf(0x01, 0x02))
        val inner = annexB(sps) + annexB(idr)
        // marker + 15-byte sub-header + nested Annex-B
        val wrapper = byteArrayOf(0xFC.toByte()) + ByteArray(15) { 0 } + inner
        val raw = annexB(wrapper)

        val parsed = XmClipParser.parse(raw)
        assertTrue(parsed is XmClipParser.Parsed.Hevc)
    }

    @Test
    fun `parses Annex-B H264`() {
        // Very small synthetic SPS: profile 66 (Baseline), level 30, 16x16 frame
        // NAL header 0x67, then rbsp — use a known tiny SPS if needed; here we only
        // need the parser to classify type 7 / type 5, not to decode dimensions.
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0xAB.toByte(), 0x40, 0x50, 0x17, 0xFC.toByte(), 0xB0.toByte(), 0x0A, 0x10)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x00, 0x01, 0x02, 0x03)
        val raw = annexB(sps) + annexB(pps) + annexB(idr)

        val parsed = XmClipParser.parse(raw)
        assertTrue(parsed is XmClipParser.Parsed.Avc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty clip throws clear error`() {
        XmClipParser.parse(ByteArray(0))
    }

    private fun nalHevc(type: Int, payload: ByteArray): ByteArray {
        val header0 = (type shl 1) and 0x7E
        return byteArrayOf(header0.toByte(), 0x01) + payload
    }

    /** Enough RBSP bits for HevcSps.dimensions to read width/height (8x8). */
    private fun minimalHevcSpsRbsp(): ByteArray {
        // Hand-rolled: after 2-byte NAL header, BitReader sees:
        // sps_video_parameter_set_id(4)=0, max_sub_layers(3)=0, temporal(1)=1,
        // profile_tier_level 96 bits, sps_id ue=0, chroma ue=1, width ue=8, height ue=8
        // This is only used to keep type=33 recognizable; dimensions parse may
        // return garbage for this stub and that's fine for parser classification tests.
        return ByteArray(24) { 0x40 }
    }

    private fun annexB(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal
}
