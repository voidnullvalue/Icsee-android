package com.voidnullvalue.icseelocal.model

/**
 * Session state machine, per the task brief: Disconnected -> Connecting ->
 * NegotiatingCrypto -> Authenticating -> Authenticated -> Streaming, with
 * Reconnecting/Failed reachable from any active state.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object NegotiatingCrypto : ConnectionState()
    data object Authenticating : ConnectionState()
    data class Authenticated(val sessionId: UInt, val aliveIntervalSeconds: Int) : ConnectionState()
    data class Streaming(val sessionId: UInt, val aliveIntervalSeconds: Int) : ConnectionState()
    data class Reconnecting(val attempt: Int, val nextRetryAtMillis: Long, val reason: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()

    val label: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting"
            is NegotiatingCrypto -> "Negotiating crypto"
            is Authenticating -> "Authenticating"
            is Authenticated -> "Authenticated"
            is Streaming -> "Streaming"
            is Reconnecting -> "Reconnecting"
            is Failed -> "Failed"
        }
}

/**
 * Legal transitions for the session state machine. Kept as an explicit table
 * (rather than "any -> any") so a transport bug that tries to jump straight
 * from Disconnected to Streaming, for example, fails loudly in tests instead
 * of silently corrupting diagnostics.
 */
object ConnectionStateMachine {
    private fun stateKind(s: ConnectionState): String = when (s) {
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.NegotiatingCrypto -> "NegotiatingCrypto"
        is ConnectionState.Authenticating -> "Authenticating"
        is ConnectionState.Authenticated -> "Authenticated"
        is ConnectionState.Streaming -> "Streaming"
        is ConnectionState.Reconnecting -> "Reconnecting"
        is ConnectionState.Failed -> "Failed"
    }

    private val allowed: Map<String, Set<String>> = mapOf(
        "Disconnected" to setOf("Connecting"),
        "Connecting" to setOf("NegotiatingCrypto", "Authenticating", "Failed", "Reconnecting", "Disconnected"),
        "NegotiatingCrypto" to setOf("Authenticating", "Failed", "Reconnecting", "Disconnected"),
        "Authenticating" to setOf("Authenticated", "Failed", "Reconnecting", "Disconnected"),
        "Authenticated" to setOf("Streaming", "Reconnecting", "Disconnected", "Failed"),
        "Streaming" to setOf("Reconnecting", "Disconnected", "Failed", "Authenticated"),
        "Reconnecting" to setOf("Connecting", "Failed", "Disconnected"),
        "Failed" to setOf("Connecting", "Disconnected"),
    )

    fun canTransition(from: ConnectionState, to: ConnectionState): Boolean =
        stateKind(to) in (allowed[stateKind(from)] ?: emptySet())
}
