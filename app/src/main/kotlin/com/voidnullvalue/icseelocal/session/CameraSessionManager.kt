package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.diagnostics.BoundedHistory
import com.voidnullvalue.icseelocal.diagnostics.CommandHistoryEntry
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.model.ConnectionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the connection lifecycle for one camera: state machine transitions,
 * keepalive, and bounded-backoff reconnect. Single owner per camera
 * connection -- create one instance per active camera, not shared.
 *
 * Login is live-verified end-to-end against the target camera (see
 * [DvripLoginNegotiator] / PROTOCOL_NOTES.md "Login -- LIVE AUTHENTICATION
 * CONFIRMED"): Disconnected -> Connecting -> NegotiatingCrypto ->
 * Authenticating -> Authenticated, with real keepalive/PTZ/OPTalk commands
 * subsequently accepted by the camera in that state.
 */
class CameraSessionManager(
    private val host: String,
    private val port: Int,
    private val loginNegotiator: LoginNegotiator = DvripLoginNegotiator(),
    private val reconnectBackoff: ReconnectBackoff = ReconnectBackoff(),
    private val maxAutoReconnectAttempts: Int = 5,
) {
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(ownerJob + Dispatchers.IO)

    /**
     * One monotonic sequence counter for the whole session, shared with every
     * connection that reuses this session (video/talk). DVRIP correlates a
     * media/talk claim to the session by a sequence that continues the
     * session's own progression; a secondary connection using its own counter
     * from 0 gets Ret:100 but no stream. See [DvripTransport]'s `sequence`.
     */
    val sessionSequence = java.util.concurrent.atomic.AtomicLong(0)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val history = BoundedHistory()
    val commandHistory get() = history.snapshot()

    private var transport: DvripTransport? = null
    private var keepalive: KeepaliveTask? = null
    private var connectJob: Job? = null
    private var reconnectAttempt = 0

    /** Read-only access to the control connection's transport for diagnostics (byte counters, last message id, etc). */
    val controlTransport: DvripTransport? get() = transport

    /** The authenticated control connection's command channel -- null until [ConnectionState.Authenticated]. PTZ (and any other control-channel command) reuses this rather than opening a new connection. */
    var commandChannel: DvripCommandChannel? = null
        private set

    private fun transition(to: ConnectionState) {
        val from = _state.value
        check(ConnectionStateMachine.canTransition(from, to)) {
            "illegal state transition ${from.label} -> ${to.label}"
        }
        _state.value = to
    }

    /** Manual connect / manual reconnect entry point (also used by the UI's reconnect button). */
    fun connect(credentials: CameraCredentials) {
        connectJob?.cancel()
        reconnectAttempt = 0
        connectJob = scope.launch { attemptConnect(credentials, isManual = true) }
    }

    fun disconnect() {
        connectJob?.cancel()
        keepalive?.stop()
        transport?.close()
        transport = null
        commandChannel = null
        if (ConnectionStateMachine.canTransition(_state.value, ConnectionState.Disconnected)) {
            _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun attemptConnect(credentials: CameraCredentials, isManual: Boolean) {
        transition(ConnectionState.Connecting)
        val t = DvripTransport(host, port, sequence = sessionSequence)
        try {
            t.connect()
            transport = t
            transition(ConnectionState.NegotiatingCrypto)
            // negotiate() performs both crypto negotiation and credential submission in one
            // call today (see DvripLoginNegotiator); there is no intermediate progress signal
            // to distinguish the two phases yet, so Authenticating covers the whole call.
            transition(ConnectionState.Authenticating)
            val session = loginNegotiator.negotiate(t, credentials)
            transition(ConnectionState.Authenticated(session.sessionId, session.aliveIntervalSeconds))
            reconnectAttempt = 0

            val channel = DvripCommandChannel(t, session.sessionId, session.crypto)
            commandChannel = channel
            keepalive = KeepaliveTask(
                channel = channel,
                aliveIntervalSeconds = session.aliveIntervalSeconds,
                sessionIdHex = "0x%08X".format(session.sessionId.toLong()),
                onFailure = { scheduleReconnect(credentials, reason = "keepalive failed: ${it.message}") },
            ).also { it.start(scope) }
        } catch (e: Exception) {
            t.close()
            transport = null
            history.add(
                CommandHistoryEntry(
                    System.currentTimeMillis(),
                    CommandHistoryEntry.Direction.RECEIVED,
                    0,
                    "connect failed: ${e.message}",
                ),
            )
            if (isManual) {
                transition(ConnectionState.Failed(e.message ?: e.toString()))
            } else {
                scheduleReconnect(credentials, reason = e.message ?: e.toString())
            }
        }
    }

    private fun scheduleReconnect(credentials: CameraCredentials, reason: String) {
        keepalive?.stop()
        transport?.close()
        transport = null
        commandChannel = null
        reconnectAttempt++
        if (reconnectAttempt > maxAutoReconnectAttempts) {
            transition(ConnectionState.Failed("gave up after $maxAutoReconnectAttempts reconnect attempts: $reason"))
            return
        }
        val delayMillis = reconnectBackoff.delayForAttempt(reconnectAttempt)
        transition(ConnectionState.Reconnecting(reconnectAttempt, System.currentTimeMillis() + delayMillis, reason))
        connectJob = scope.launch {
            delay(delayMillis)
            attemptConnect(credentials, isManual = false)
        }
    }

    /** Releases everything; this manager instance must not be reused after calling shutdown(). */
    fun shutdown() {
        disconnect()
        ownerJob.cancel()
    }
}
