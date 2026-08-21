package com.voidnullvalue.icseelocal.avtalk

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class I420TransformerTest {
    @Test
    fun `rotates packed I420 90 degrees clockwise without changing samples`() {
        // 4x2 Y:
        // 0 1 2 3
        // 4 5 6 7
        // U: 10 11, V: 20 21
        val input = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            10, 11,
            20, 21,
        )
        val output = I420Transformer.transform(input, 4, 2, 90, 2, 4)
        assertArrayEquals(
            byteArrayOf(
                4, 0,
                5, 1,
                6, 2,
                7, 3,
                10,
                11,
                20,
                21,
            ),
            output,
        )
    }

    @Test
    fun `identity transform preserves packed I420`() {
        val input = ByteArray(4 * 4 * 3 / 2) { it.toByte() }
        assertArrayEquals(input, I420Transformer.transform(input, 4, 4, 0, 4, 4))
    }
}
