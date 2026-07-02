package com.voidnullvalue.icseelocal.dvrip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DvripFrameAssemblerTest {

    private fun frameBytes(session: UInt, seq: UInt, msgId: Int, payload: ByteArray): ByteArray =
        DvripFrame.of(session, seq, msgId, payload).encode()

    @Test
    fun `parses a single complete frame delivered in one read`() {
        val bytes = frameBytes(1u, 1u, DvripMessageIds.KEEPALIVE_REQUEST, byteArrayOf(1, 2, 3))
        val frames = DvripFrameAssembler().offer(bytes)
        assertEquals(1, frames.size)
        assertEquals(DvripMessageIds.KEEPALIVE_REQUEST, frames[0].header.messageId)
        assertTrue(frames[0].payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `assembles a frame split across many partial reads`() {
        val bytes = frameBytes(1u, 1u, DvripMessageIds.PTZ_CONTROL_REQUEST, ByteArray(50) { it.toByte() })
        val assembler = DvripFrameAssembler()
        val collected = mutableListOf<DvripFrame>()
        // Feed one byte at a time -- the pathological case for partial reads.
        for (b in bytes) {
            collected += assembler.offer(byteArrayOf(b))
        }
        assertEquals(1, collected.size)
        assertEquals(DvripMessageIds.PTZ_CONTROL_REQUEST, collected[0].header.messageId)
        assertEquals(50, collected[0].payload.size)
    }

    @Test
    fun `assembles a frame split exactly at the header boundary`() {
        val bytes = frameBytes(1u, 1u, 1400, byteArrayOf(9, 9, 9))
        val assembler = DvripFrameAssembler()
        val first = assembler.offer(bytes.copyOfRange(0, DvripHeader.HEADER_LEN))
        assertTrue(first.isEmpty())
        assertEquals(DvripHeader.HEADER_LEN, assembler.pendingByteCount)
        val second = assembler.offer(bytes.copyOfRange(DvripHeader.HEADER_LEN, bytes.size))
        assertEquals(1, second.size)
        assertEquals(0, assembler.pendingByteCount)
    }

    @Test
    fun `parses multiple frames delivered in a single read`() {
        val a = frameBytes(1u, 1u, 1006, byteArrayOf(1))
        val b = frameBytes(1u, 2u, 1400, byteArrayOf(2, 2))
        val c = frameBytes(1u, 3u, 1401, byteArrayOf(3, 3, 3))
        val frames = DvripFrameAssembler().offer(a + b + c)
        assertEquals(3, frames.size)
        assertEquals(listOf(1006, 1400, 1401), frames.map { it.header.messageId })
        assertEquals(listOf(1u, 2u, 3u), frames.map { it.header.sequence })
    }

    @Test
    fun `holds back a trailing partial frame until more data arrives`() {
        val a = frameBytes(1u, 1u, 1006, byteArrayOf(1, 2))
        val b = frameBytes(1u, 2u, 1400, byteArrayOf(3, 4, 5))
        val assembler = DvripFrameAssembler()
        val firstBatch = assembler.offer(a + b.copyOfRange(0, 10))
        assertEquals(1, firstBatch.size)
        assertEquals(1006, firstBatch[0].header.messageId)
        assertTrue(assembler.pendingByteCount > 0)
        val secondBatch = assembler.offer(b.copyOfRange(10, b.size))
        assertEquals(1, secondBatch.size)
        assertEquals(1400, secondBatch[0].header.messageId)
    }

    @Test
    fun `throws on invalid magic byte in stream`() {
        val bytes = frameBytes(1u, 1u, 1006, byteArrayOf(1)).also { it[0] = 0x00 }
        assertThrows(DvripFramingException::class.java) { DvripFrameAssembler().offer(bytes) }
    }

    @Test
    fun `zero length payload frame parses correctly`() {
        // Real evidence: discovery probe frame has msg id 1530, zero-length payload.
        val bytes = frameBytes(0u, 0u, DvripMessageIds.DISCOVERY_PROBE, ByteArray(0))
        val frames = DvripFrameAssembler().offer(bytes)
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].payload.size)
    }

    @Test
    fun `real captured media chunk size of 8192 bytes assembles correctly`() {
        val bytes = frameBytes(0x1Bu, 5u, DvripMessageIds.MEDIA_STREAM, ByteArray(8192) { (it % 251).toByte() })
        val frames = DvripFrameAssembler().offer(bytes)
        assertEquals(1, frames.size)
        assertEquals(8192, frames[0].payload.size)
    }
}
