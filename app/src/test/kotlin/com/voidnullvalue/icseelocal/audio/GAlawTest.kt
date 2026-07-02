package com.voidnullvalue.icseelocal.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class GAlawTest {

    @Test
    fun `encodes PCM silence to 0xD5, matching the byte observed filling real captured audio frames`() {
        assertEquals(0xD5.toByte(), GAlaw.encode(0))
    }

    @Test
    fun `known vectors from the standard G711 reference algorithm`() {
        assertEquals(0x55.toByte(), GAlaw.encode(-8))
        assertEquals(0xD5.toByte(), GAlaw.encode(3)) // rounds down into the same zero segment
        assertEquals(0xAA.toByte(), GAlaw.encode(32767)) // max positive sample
        assertEquals(0x2A.toByte(), GAlaw.encode(-32768)) // max negative sample
    }

    @Test
    fun `decode of silence byte is within one quantization step of zero`() {
        val decoded = GAlaw.decode(0xD5.toByte())
        assert(kotlin.math.abs(decoded.toInt()) <= 8) { "expected near-zero, got $decoded" }
    }

    @Test
    fun `encode then decode round trips within A-law quantization error`() {
        for (sample in listOf<Short>(1000, -1000, 20000, -20000, 100, -100)) {
            val decoded = GAlaw.decode(GAlaw.encode(sample))
            val error = kotlin.math.abs(decoded - sample)
            assert(error < sample.toInt().let { kotlin.math.abs(it) / 8 + 64 }) {
                "sample=$sample decoded=$decoded error=$error too large"
            }
        }
    }
}
