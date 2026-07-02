package com.voidnullvalue.icseelocal.diagnostics

import com.voidnullvalue.icseelocal.model.ConnectionState

/**
 * Sanitized diagnostic snapshot. Deliberately excludes anything the task
 * brief forbids from display/export: no plaintext passwords, AES keys, RSA
 * blocks, mic contents, or full video frames.
 */
data class DiagnosticsSnapshot(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val cameraAddress: String? = null,
    val tcpStreamId: String? = null,
    val authenticatedSessionId: String? = null,
    val encryptionActive: Boolean = false,
    val keepaliveIntervalSeconds: Int? = null,
    val lastKeepaliveAtMillis: Long? = null,
    val lastMessageId: Int? = null,
    val lastSequence: UInt? = null,
    val lastResponseRet: Int? = null,
    val controlBytesSent: Long = 0,
    val controlBytesReceived: Long = 0,
    val videoBytesReceived: Long = 0,
    val audioBytesSent: Long = 0,
    val reconnectCount: Int = 0,
    val decoderState: String = "idle",
    val detectedCodec: String = "unknown",
    val lastError: String? = null,
    val commandHistory: List<CommandHistoryEntry> = emptyList(),
)

data class CommandHistoryEntry(
    val timestampMillis: Long,
    val direction: Direction,
    val messageId: Int,
    val summary: String,
) {
    enum class Direction { SENT, RECEIVED }
}

/** Bounded ring buffer so diagnostics history can't grow without limit over a long session. */
class BoundedHistory(private val maxEntries: Int = 200) {
    private val entries = ArrayDeque<CommandHistoryEntry>()

    @Synchronized
    fun add(entry: CommandHistoryEntry): List<CommandHistoryEntry> {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
        return entries.toList()
    }

    @Synchronized
    fun snapshot(): List<CommandHistoryEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
