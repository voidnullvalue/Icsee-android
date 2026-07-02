package com.voidnullvalue.icseelocal.video

import java.io.ByteArrayOutputStream

/** Max observed payload size of a single message-1412 DVRIP frame carrying media data. */
const val MEDIA_CHUNK_MAX_SIZE = 8192

/**
 * Reassembles message-1412 DVRIP frame payloads into complete logical media
 * units (one audio chunk, or one video frame). See PROTOCOL_NOTES.md
 * "Video/audio media channel" for the evidence this is built from.
 *
 * Continuation rule (inferred, not exhaustively verified): a chunk whose
 * size equals [MEDIA_CHUNK_MAX_SIZE] means more data follows for the
 * current unit; a chunk shorter than that completes the unit. Every sample
 * in the capture was consistent with this rule, but a unit whose total size
 * happens to be an exact multiple of 8192 was never observed, so that edge
 * case is unverified.
 */
class MediaStreamReassembler {
    private var pending: ByteArrayOutputStream? = null
    private var pendingType: MediaUnitType? = null
    private var pendingChunkCount = 0

    /** Returns a completed [MediaUnit], or null if this chunk continues a unit still in progress. */
    fun offer(chunkPayload: ByteArray): MediaUnit? {
        if (pending == null) {
            require(chunkPayload.size >= 4 && chunkPayload[0] == 0.toByte() && chunkPayload[1] == 0.toByte() && chunkPayload[2] == 1.toByte()) {
                "expected a new media unit to start with the 00 00 01 marker, got ${chunkPayload.take(4)}"
            }
            pendingType = MediaUnitType.fromMarkerByte(chunkPayload[3].toInt() and 0xFF)
            pending = ByteArrayOutputStream(chunkPayload.size).apply { write(chunkPayload) }
            pendingChunkCount = 1
        } else {
            pending!!.write(chunkPayload)
            pendingChunkCount++
        }

        return if (chunkPayload.size < MEDIA_CHUNK_MAX_SIZE) {
            val unit = MediaUnit(pendingType!!, pending!!.toByteArray(), pendingChunkCount)
            pending = null
            pendingType = null
            pendingChunkCount = 0
            unit
        } else {
            null
        }
    }

    fun reset() {
        pending = null
        pendingType = null
        pendingChunkCount = 0
    }
}
