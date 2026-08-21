package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.SessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import java.util.Base64

/**
 * Sends/receives JSON commands over an authenticated session, applying the
 * negotiated session crypto envelope when required.
 *
 * [sequenceProvider] is null for the existing DVRIP stack, preserving its
 * transport-owned sequence behavior. The recovered FunSDK encrypted/AVTalk
 * path supplies CXMDevPTL::NewSeq() here so control commands and keepalives
 * consume the same native +8 sequence used by the secondary AVTalk socket.
 */
class DvripCommandChannel(
    private val transport: DvripTransport,
    private val sessionId: UInt,
    private val crypto: SessionCrypto,
    private val sequenceProvider: (() -> UInt)? = null,
) {
    suspend fun sendJson(messageId: Int, json: String): DvripFrame {
        val wirePayload = if (crypto.shouldEncrypt(messageId)) {
            val ciphertext = crypto.encrypt(messageId, json.toByteArray(Charsets.UTF_8))
            DvripPayloads.encodeBase64Text(Base64.getEncoder().encodeToString(ciphertext))
        } else {
            DvripPayloads.encodeJson(json)
        }
        return transport.send(
            session = sessionId,
            messageId = messageId,
            payload = wirePayload,
            sequenceOverride = sequenceProvider?.invoke(),
        )
    }

    /** Returns null if the frame payload does not match its expected wire envelope. */
    fun decodeResponse(frame: DvripFrame): String? {
        return if (crypto.shouldEncrypt(frame.header.messageId)) {
            val base64Text = DvripPayloads.decodeBase64TextOrNull(frame.payload) ?: return null
            val ciphertext = runCatching { Base64.getDecoder().decode(base64Text) }.getOrNull() ?: return null
            val plaintext = runCatching { crypto.decrypt(frame.header.messageId, ciphertext) }.getOrNull() ?: return null
            plaintext.toString(Charsets.UTF_8)
        } else {
            DvripPayloads.decodeJsonOrNull(frame.payload)
        }
    }
}
