package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaStreamReassemblerTest {

    @Test
    fun `reassembles a single-chunk audio unit exactly like the real 328-byte captures`() {
        // Real evidence: 8-byte marker "00 00 01 fa 0e 02 40 01" + 320 bytes A-law.
        val header = byteArrayOf(0, 0, 1, 0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01)
        val chunk = header + ByteArray(320) { 0xD5.toByte() }
        val unit = MediaStreamReassembler().offer(chunk)
        requireNotNull(unit)
        assertEquals(MediaUnitType.AUDIO, unit.type)
        assertEquals(328, unit.bytes.size)
        assertEquals(1, unit.chunkCount)
    }

    @Test
    fun `reassembles a video unit spanning two full 8192 chunks plus a short final chunk`() {
        val reassembler = MediaStreamReassembler()
        val firstChunk = byteArrayOf(0, 0, 1, 0xFC.toByte()) + ByteArray(MEDIA_CHUNK_MAX_SIZE - 4)
        assertNull(reassembler.offer(firstChunk))
        val secondChunk = ByteArray(MEDIA_CHUNK_MAX_SIZE)
        assertNull(reassembler.offer(secondChunk))
        val finalChunk = ByteArray(100)
        val unit = reassembler.offer(finalChunk)
        requireNotNull(unit)
        assertEquals(MediaUnitType.VIDEO_FIRST, unit.type)
        assertEquals(MEDIA_CHUNK_MAX_SIZE * 2 + 100, unit.bytes.size)
        assertEquals(3, unit.chunkCount)
    }

    @Test
    fun `rejects a continuation chunk arriving as the first chunk of a new unit`() {
        val bogus = ByteArray(10) { 0x41 } // doesn't start with 00 00 01
        assertThrows(IllegalArgumentException::class.java) { MediaStreamReassembler().offer(bogus) }
    }
}
