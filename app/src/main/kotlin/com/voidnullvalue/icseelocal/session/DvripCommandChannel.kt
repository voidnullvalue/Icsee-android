package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.SessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import java.util.Base64

/**
 * Sends/receives JSON commands over an authenticated session, applying
 * [SessionCrypto]'s generic envelope when the message id requires it.
 * Wire shape for the encrypted case (base64 ASCII text + single 0x00
 * terminator, as opposed to plaintext JSON's `0x0A 0x00`) is confirmed by
 * evidence -- see PROTOCOL_NOTES.md "Post-login encryption". The AES
 * parameters underneath [SessionCrypto] are not yet confirmed; this class
 * is correct regardless of that, since it only handles the base64/framing
 * layer and delegates the actual cipher operation.
 */
class DvripCommandChannel(
    private val transport: DvripTransport,
    private val sessionId: UInt,
    private val crypto: SessionCrypto,
) {
    suspend fun sendJson(messageId: Int, json: String): DvripFrame {
        val wirePayload = if (crypto.shouldEncrypt(messageId)) {
            val ciphertext = crypto.encrypt(messageId, json.toByteArray(Charsets.UTF_8))
            DvripPayloads.encodeBase64Text(Base64.getEncoder().encodeToString(ciphertext))
        } else {
            DvripPayloads.encodeJson(json)
        }
        return transport.send(sessionId, messageId, wirePayload)
    }

    /** Returns null if the frame's payload doesn't match the expected shape for its encryption status. */
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
