package com.voidnullvalue.icseelocal.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    @Test
    fun `allows the normal happy path`() {
        assertTrue(ConnectionStateMachine.canTransition(ConnectionState.Disconnected, ConnectionState.Connecting))
        assertTrue(ConnectionStateMachine.canTransition(ConnectionState.Connecting, ConnectionState.NegotiatingCrypto))
        assertTrue(ConnectionStateMachine.canTransition(ConnectionState.NegotiatingCrypto, ConnectionState.Authenticating))
        assertTrue(
            ConnectionStateMachine.canTransition(
                ConnectionState.Authenticating,
                ConnectionState.Authenticated(1u, 30),
            ),
        )
        assertTrue(
            ConnectionStateMachine.canTransition(
                ConnectionState.Authenticated(1u, 30),
                ConnectionState.Streaming(1u, 30),
            ),
        )
    }

    @Test
    fun `rejects skipping straight from disconnected to authenticated`() {
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.Disconnected, ConnectionState.Authenticated(1u, 30)))
    }

    @Test
    fun `rejects skipping straight from disconnected to streaming`() {
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.Disconnected, ConnectionState.Streaming(1u, 30)))
    }

    @Test
    fun `any active state can fail`() {
        val activeStates = listOf(
            ConnectionState.Connecting,
            ConnectionState.NegotiatingCrypto,
            ConnectionState.Authenticating,
            ConnectionState.Authenticated(1u, 30),
            ConnectionState.Streaming(1u, 30),
        )
        for (state in activeStates) {
            assertTrue("$state -> Failed should be allowed", ConnectionStateMachine.canTransition(state, ConnectionState.Failed("x")))
        }
    }

    @Test
    fun `failed can restart via connecting`() {
        assertTrue(ConnectionStateMachine.canTransition(ConnectionState.Failed("x"), ConnectionState.Connecting))
    }
}
