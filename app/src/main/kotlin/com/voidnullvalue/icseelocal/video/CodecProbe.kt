package com.voidnullvalue.icseelocal.video

enum class DetectedCodec { H264, H265, UNKNOWN }

/**
 * Scans a video [MediaUnit] for a standard Annex-B NAL start code followed
 * by a plausible NAL header. Per PROTOCOL_NOTES.md "Video/audio media
 * channel", **no such start code was found anywhere in the pcap evidence
 * examined** -- this returning UNKNOWN against real captured data is the
 * honest, expected result today, not a bug. It exists so a live capture (or
 * a corrected understanding of the private sub-header's length) can be
 * dropped in and identified without further plumbing changes.
 */
object CodecProbe {
    fun detect(unit: MediaUnit): DetectedCodec {
        if (unit.type != MediaUnitType.VIDEO_FIRST && unit.type != MediaUnitType.VIDEO_SUBSEQUENT) return DetectedCodec.UNKNOWN
        val data = unit.bytes
        var offset = 0
        while (offset < data.size - 5) {
            val is4Byte = data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
            val is3Byte = !is4Byte && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()
            if (is4Byte || is3Byte) {
                val headerOffset = offset + if (is4Byte) 4 else 3
                val headerByte = data[headerOffset].toInt() and 0xFF
                val forbiddenBitZero = headerByte and 0x80 == 0
                if (forbiddenBitZero) {
                    val h264NalType = headerByte and 0x1F
                    if (h264NalType in 1..23) return DetectedCodec.H264
                    val h265NalType = (headerByte shr 1) and 0x3F
                    if (h265NalType in 32..40) return DetectedCodec.H265 // VPS/SPS/PPS/etc range, less ambiguous than VCL range
                }
            }
            offset++
        }
        return DetectedCodec.UNKNOWN
    }
}
