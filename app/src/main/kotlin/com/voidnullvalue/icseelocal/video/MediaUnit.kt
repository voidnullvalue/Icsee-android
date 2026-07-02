package com.voidnullvalue.icseelocal.video

/**
 * One reassembled logical media unit from the message-1412 combined
 * video+audio stream (see PROTOCOL_NOTES.md "Video/audio media channel").
 * Every unit begins with a private 3-byte marker `00 00 01` followed by a
 * type byte -- not a standard Annex-B NAL start code.
 */
enum class MediaUnitType(val markerByte: Int) {
    /** 0xFA -- always exactly 328 bytes total, never split across DVRIP frames. Confirmed raw G.711 A-law. */
    AUDIO(0xFA),
    /** 0xFC -- seen on the first video unit in the capture (presumed keyframe/IDR). */
    VIDEO_FIRST(0xFC),
    /** 0xFD -- seen on every subsequent video unit (presumed inter-frame). */
    VIDEO_SUBSEQUENT(0xFD),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromMarkerByte(byte: Int): MediaUnitType = entries.firstOrNull { it.markerByte == byte } ?: UNKNOWN
    }
}

data class MediaUnit(
    val type: MediaUnitType,
    val bytes: ByteArray,
    val chunkCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaUnit) return false
        return type == other.type && bytes.contentEquals(other.bytes) && chunkCount == other.chunkCount
    }
    override fun hashCode(): Int = 31 * (31 * type.hashCode() + bytes.contentHashCode()) + chunkCount
}
