package com.voidnullvalue.icseelocal.dvrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class TransportStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val lastMessageIdSent: Int? = null,
    val lastMessageIdReceived: Int? = null,
    val lastSequenceSent: UInt? = null,
    val lastError: String? = null,
)

/**
 * One TCP connection to a camera's DVRIP control port. Single owner per
 * socket: [connect] launches this transport's own receive loop on a scope
 * it owns and cancels on [close]; callers never race to start/stop reading
 * from the socket themselves, and there is exactly one instance per
 * connection attempt (create a fresh transport for each reconnect).
 */
class DvripTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 5000,
    /** Read poll granularity, not a connection-health timeout -- see the receive loop. */
    private val readPollTimeoutMillis: Int = 2000,
    /**
     * Sequence counter for outgoing command frames. DVRIP tracks one monotonic
     * command sequence per session, shared across TCP connections that reuse
     * the session. Media payloads whose native protocol fixes sequence to zero
     * use [send]'s [sequenceOverride] instead.
     */
    private val sequence: AtomicLong = AtomicLong(0),
) {
    private var socket: Socket? = null
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.IO)
    private var receiveJob: Job? = null

    private val _stats = MutableStateFlow(TransportStats())
    val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<DvripFrame>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val incomingFrames: SharedFlow<DvripFrame> = _incomingFrames.asSharedFlow()

    val isConnected: Boolean get() = socket != null && !closed.get()

    /** Connects and starts this transport's owned receive loop; returns once the socket is open. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        check(socket == null) { "DvripTransport instances are single-use; create a new one per connection" }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        s.soTimeout = readPollTimeoutMillis
        s.tcpNoDelay = true
        socket = s
        receiveJob = ownerScope.launch { receiveLoop(s) }
    }

    /** Next value of the (possibly session-shared) monotonic sequence counter. */
    fun nextSequence(): UInt = sequence.getAndIncrement().toUInt()

    /**
     * Sends one DVRIP frame.
     *
     * [sequenceOverride] is used by native media protocols whose data frames
     * carry a literal sequence (AVTalk 1419 uses zero). [headerByte12] and
     * [headerByte13] expose the protocol-dependent bytes at offsets 12/13;
     * they default to zero so all existing callers retain their exact wire
     * format. FunSDK encrypted login uses byte12=0x63, and AVTalk 1419 uses
     * byte12 as a per-source-frame fragment-group tag.
     */
    suspend fun send(
        session: UInt,
        messageId: Int,
        payload: ByteArray,
        type: Int = DvripHeader.DEFAULT_TYPE,
        sequenceOverride: UInt? = null,
        headerByte12: Int = 0,
        headerByte13: Int = 0,
    ): DvripFrame =
        withContext(Dispatchers.IO) {
            val frame = DvripFrame.of(
                session = session,
                sequence = sequenceOverride ?: nextSequence(),
                messageId = messageId,
                payload = payload,
                type = type,
                headerByte12 = headerByte12,
                headerByte13 = headerByte13,
            )
            writeMutex.withLock {
                val s = socket ?: throw IOException("not connected")
                if (closed.get()) throw IOException("transport closed")
                try {
                    s.getOutputStream().let { out ->
                        out.write(frame.encode())
                        out.flush()
                    }
                } catch (e: IOException) {
                    _stats.update { it.copy(lastError = "write failed: ${e.message}") }
                    throw e
                }
            }
            _stats.update {
                it.copy(
                    bytesSent = it.bytesSent + DvripHeader.HEADER_LEN + frame.payload.size,
                    framesSent = it.framesSent + 1,
                    lastMessageIdSent = frame.header.messageId,
                    lastSequenceSent = frame.header.sequence,
                )
            }
            frame
        }

    private suspend fun receiveLoop(s: Socket) {
        val assembler = DvripFrameAssembler()
        val buf = ByteArray(65536)
        val input = s.getInputStream()
        while (kotlin.coroutines.coroutineContext.isActive && !closed.get()) {
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (closed.get()) break
                _stats.update { it.copy(lastError = "read failed: ${e.message}") }
                break
            }
            if (n < 0) {
                if (!closed.get()) _stats.update { it.copy(lastError = "connection closed by peer") }
                break
            }
            if (n == 0) continue
            val frames = try {
                assembler.offer(buf.copyOf(n))
            } catch (e: DvripFramingException) {
                _stats.update { it.copy(lastError = "framing error: ${e.message}") }
                break
            }
            for (frame in frames) {
                _stats.update {
                    it.copy(
                        bytesReceived = it.bytesReceived + DvripHeader.HEADER_LEN + frame.payload.size,
                        framesReceived = it.framesReceived + 1,
                        lastMessageIdReceived = frame.header.messageId,
                    )
                }
                _incomingFrames.emit(frame)
            }
        }
    }

    /** Idempotent: safe to call multiple times, from any thread, including from within a cancelled coroutine. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket?.close() }
            ownerJob.cancel()
        }
    }
}
