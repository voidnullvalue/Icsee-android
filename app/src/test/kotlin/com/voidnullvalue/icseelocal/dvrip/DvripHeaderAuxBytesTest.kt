package com.voidnullvalue.icseelocal.dvrip

import org.junit.Assert.assertEquals
import org.junit.Test

class DvripHeaderAuxBytesTest {
    @Test
    fun `protocol dependent header bytes round trip without changing zero-default callers`() {
        val tagged = DvripHeader(
            type = 1,
            session = 0x3cu,
            sequence = 0u,
            messageId = 1419,
            payloadLength = 123,
            headerByte12 = 0xF8,
            headerByte13 = 0,
        )
        val wire = tagged.encode()
        assertEquals(0xF8, wire[12].toInt() and 0xff)
        assertEquals(0, wire[13].toInt() and 0xff)
        assertEquals(tagged, DvripHeader.decode(wire))

        val ordinary = DvripHeader(1, 0x1bu, 7u, 1400, 12).encode()
        assertEquals(0, ordinary[12].toInt() and 0xff)
        assertEquals(0, ordinary[13].toInt() and 0xff)
    }
}
