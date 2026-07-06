package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.SessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripTransport

data class AuthenticatedSession(
    val sessionId: UInt,
    val aliveIntervalSeconds: Int,
    val transport: DvripTransport,
    val crypto: SessionCrypto,
    /**
     * The `AdminToken` the camera returns in the login response (msg 1001). Its
     * purpose is not yet confirmed (see PROTOCOL_NOTES.md / PROTOCOL_STATUS.md
     * "AdminToken"); it is captured here rather than discarded because if it turns
     * out to permit token-based *session resumption* on a fresh TCP connection, the
     * unavoidable socket-death reconnects could stop being full password logins --
     * the single biggest remaining lever on this camera's login-rate budget. Not
     * used for anything yet; wiring it through is deliberate groundwork, and
     * confirming resumption needs a live camera.
     */
    val adminToken: String? = null,
)

/**
 * Thrown when negotiation reaches a point this app cannot yet complete honestly
 * -- see PROTOCOL_STATUS.md. [ret] carries the camera's `Ret` code when the
 * failure was a device-reported login rejection (null for transport/parse
 * failures with no code). It lets callers distinguish a credential rejection --
 * which will never succeed by retrying, and where retrying repeatedly can make
 * the device temporarily lock the account (Ret:205) -- from a transient network
 * error that is worth reconnecting on.
 */
class LoginNegotiationBlockedException(message: String, val ret: Int? = null) : Exception(message) {
    /** True when the camera answered the login with a rejection code rather than accepting it. */
    val isAuthRejection: Boolean get() = ret != null

    /** Ret:205 -- the account is temporarily locked, typically after repeated failed logins. */
    val isAccountLocked: Boolean get() = ret == RET_ACCOUNT_LOCKED

    companion object {
        const val RET_ACCOUNT_LOCKED = 205
    }
}

interface LoginNegotiator {
    suspend fun negotiate(transport: DvripTransport, credentials: CameraCredentials): AuthenticatedSession
}
