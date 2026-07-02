package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecProbeTest {

    @Test
    fun `returns UNKNOWN for real-shaped captured bytes with no Annex-B start code`() {
        // Shape matches the real captured video unit header: 00 00 01 fc ... with no
        // 00 00 00 01 / 00 00 01 start code anywhere in the body (see PROTOCOL_NOTES.md).
        val unit = MediaUnit(MediaUnitType.VIDEO_FIRST, byteArrayOf(0, 0, 1, 0xFC.toByte()) + ByteArray(200) { (it * 37).toByte() }, 1)
        assertEquals(DetectedCodec.UNKNOWN, CodecProbe.detect(unit))
    }

    @Test
    fun `detects H264 given a synthetic Annex-B SPS start code`() {
        // 00 00 00 01 + NAL header 0x67 (forbidden=0, nal_ref_idc=3, type=7=SPS)
        val bytes = byteArrayOf(0, 0, 1, 0xFC.toByte()) + byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42, 0x00)
        val unit = MediaUnit(MediaUnitType.VIDEO_FIRST, bytes, 1)
        assertEquals(DetectedCodec.H264, CodecProbe.detect(unit))
    }

    @Test
    fun `does not attempt codec detection on audio units`() {
        val unit = MediaUnit(MediaUnitType.AUDIO, byteArrayOf(0, 0, 1, 0xFA.toByte()) + ByteArray(320), 1)
        assertEquals(DetectedCodec.UNKNOWN, CodecProbe.detect(unit))
    }
}
