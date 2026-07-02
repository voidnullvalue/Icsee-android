package com.voidnullvalue.icseelocal.audio

/**
 * Standard ITU-T G.711 A-law codec (the classic public-domain reference
 * algorithm, e.g. as used in Sun's audio libraries and FFmpeg) -- a
 * published standard, not something reverse-engineered from the camera.
 * Cross-checked against real evidence: encoding PCM silence (0) produces
 * 0xD5, which is exactly the constant byte value observed filling the
 * silent portions of the camera's own G.711 A-law audio frames (see
 * PROTOCOL_NOTES.md "Talk channel").
 */
object GAlaw {
    private const val QUANT_MASK = 0x0F
    private const val SEG_SHIFT = 4
    private const val SEG_MASK = 0x70
    private val SEG_AEND = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    fun encode(pcm16: Short): Byte {
        var sample = pcm16.toInt() shr 3
        val mask: Int
        if (sample >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            sample = -sample - 1
        }
        val seg = segmentFor(sample)
        if (seg >= 8) return (0x7F xor mask).toByte()
        var aval = seg shl SEG_SHIFT
        aval = aval or if (seg < 2) (sample shr 1) and QUANT_MASK else (sample shr seg) and QUANT_MASK
        return (aval xor mask).toByte()
    }

    fun decode(alaw: Byte): Short {
        val a = (alaw.toInt() and 0xFF) xor 0x55
        var t = (a and QUANT_MASK) shl 4
        val seg = (a and SEG_MASK) shr SEG_SHIFT
        t = when (seg) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (seg - 1)
        }
        return (if (a and 0x80 != 0) t else -t).toShort()
    }

    fun encodeBuffer(pcm16: ShortArray): ByteArray = ByteArray(pcm16.size) { encode(pcm16[it]) }

    private fun segmentFor(value: Int): Int {
        for (i in SEG_AEND.indices) if (value <= SEG_AEND[i]) return i
        return SEG_AEND.size
    }
}
