package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.NullSessionCrypto
import com.voidnullvalue.icseelocal.crypto.SofiaHash
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Drives the DVRIP login handshake using the confirmed-working plaintext
 * path -- **live-verified end-to-end against the target camera on
 * 2026-07-01** (real login, real session id, real keepalive/PTZ/OPTalk
 * commands all subsequently accepted with `Ret: 100`; see
 * PROTOCOL_NOTES.md "Login -- LIVE AUTHENTICATION CONFIRMED").
 *
 * This deliberately does *not* perform the message 1010/1011 RSA/AES
 * negotiation: live testing showed the camera accepts a plain JSON
 * message 1000 (`LoginType: "DVRIP-Web"`, password run through
 * [SofiaHash]) with no negotiation step at all, and the resulting session
 * runs entirely unencrypted (`NullSessionCrypto`) -- keepalive, PTZ, and
 * OPTalk claim were all confirmed live in that mode. The RSA/AES
 * machinery in `crypto/` (`DvripRsaPublicKey`, `PreLoginNegotiation`,
 * `AesSessionCrypto`) is real, tested code for the 1010/1011 exchange
 * itself (also live-confirmed, see PROTOCOL_NOTES.md), kept available for
 * a firmware/device that actually requires it, but is not on this login
 * path since it turned out to be unnecessary here.
 */
class DvripLoginNegotiator(
    private val responseTimeoutMillis: Long = 5000,
) : LoginNegotiator {

    override suspend fun negotiate(transport: DvripTransport, credentials: CameraCredentials): AuthenticatedSession =
        withTimeout(responseTimeoutMillis) {
            coroutineScope {
                // Subscribe before sending -- see the identical race-avoidance note
                // in the pre-login negotiate path this replaces.
                val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incomingFrames
                        .filter { it.header.messageId == DvripMessageIds.LOGIN_RESPONSE }
                        .first()
                }
                val requestJson = buildLoginRequestJson(credentials)
                transport.send(
                    session = 0u,
                    messageId = DvripMessageIds.LOGIN_REQUEST,
                    payload = DvripPayloads.encodeJson(requestJson),
                )
                val response: DvripFrame = responseDeferred.await()
                val json = DvripPayloads.decodeJsonOrNull(response.payload)
                    ?: throw LoginNegotiationBlockedException("message 1001 response was not plaintext JSON as expected: ${response.payload.size} bytes")
                val login = LoginResponseParser.parse(json)
                    ?: throw LoginNegotiationBlockedException("could not parse message 1001 response: $json")
                if (!login.success) {
                    throw LoginNegotiationBlockedException("login rejected: Ret=${login.ret}")
                }
                AuthenticatedSession(
                    sessionId = login.sessionId,
                    aliveIntervalSeconds = login.aliveIntervalSeconds,
                    transport = transport,
                    crypto = NullSessionCrypto,
                )
            }
        }

    private fun buildLoginRequestJson(credentials: CameraCredentials): String {
        val obj = buildJsonObject {
            put("EncryptType", "MD5")
            put("LoginType", "DVRIP-Web")
            // A blank password (the factory-default "admin"/no-password account
            // BLE pairing hands back, see PROTOCOL_NOTES.md "Writing the
            // credential frame") must be sent as a literal empty string here, not
            // SofiaHash.hash("") -- that hashes to a non-empty 8-char string
            // ("tlJwpbo6"), which the camera's no-password account rejects with
            // Ret:205 since it isn't the hash of *no* password.
            put("PassWord", if (credentials.password.isEmpty()) "" else SofiaHash.hash(credentials.password))
            put("UserName", credentials.username)
        }
        return Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }
}
