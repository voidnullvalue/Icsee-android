package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.SessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripTransport

data class AuthenticatedSession(
    val sessionId: UInt,
    val aliveIntervalSeconds: Int,
    val transport: DvripTransport,
    val crypto: SessionCrypto,
)

/** Thrown when negotiation reaches a point this app cannot yet complete honestly -- see PROTOCOL_STATUS.md. */
class LoginNegotiationBlockedException(message: String) : Exception(message)

interface LoginNegotiator {
    suspend fun negotiate(transport: DvripTransport, credentials: CameraCredentials): AuthenticatedSession
}
