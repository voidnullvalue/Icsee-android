package com.voidnullvalue.icseelocal.dvrip

/**
 * Incrementally reassembles DVRIP frames out of a byte stream that may
 * arrive split across arbitrary TCP read boundaries -- a single [offer]
 * call's input has no fixed relationship to frame boundaries: it may
 * contain zero, one, or many complete frames, plus a partial frame at the
 * end which is buffered until more data arrives.
 *
 * Not thread-safe: callers must serialize access per socket (single reader
 * per connection).
 */
class DvripFrameAssembler {
    private var buffer: ByteArray = ByteArray(0)

    /** Number of bytes currently buffered waiting for more data (diagnostics only). */
    val pendingByteCount: Int get() = buffer.size

    /**
     * Appends [data] to the internal buffer and returns every complete
     * frame that can now be parsed out of it, in order. Throws
     * [DvripFramingException] if a header's magic byte is invalid or its
     * payload length is out of bounds -- callers should treat that as a
     * fatal desync for this connection and close/reconnect rather than try
     * to resynchronize a live control/media stream.
     */
    fun offer(data: ByteArray): List<DvripFrame> {
        buffer = if (buffer.isEmpty()) data else buffer + data
        val frames = mutableListOf<DvripFrame>()
        var offset = 0
        while (buffer.size - offset >= DvripHeader.HEADER_LEN) {
            val header = DvripHeader.decode(buffer, offset)
            val frameEnd = offset + DvripHeader.HEADER_LEN + header.payloadLength
            if (frameEnd > buffer.size) break // payload not fully arrived yet
            val payload = buffer.copyOfRange(offset + DvripHeader.HEADER_LEN, frameEnd)
            frames.add(DvripFrame(header, payload))
            offset = frameEnd
        }
        buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}
