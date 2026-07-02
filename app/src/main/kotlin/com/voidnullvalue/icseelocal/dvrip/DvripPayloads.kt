package com.voidnullvalue.icseelocal.dvrip

/**
 * Payload-level (not header-level) helpers for the two plaintext wire shapes
 * confirmed in PROTOCOL_NOTES.md:
 *
 *  - Plaintext JSON commands: UTF-8 JSON text followed by `0x0A 0x00`
 *    (confirmed on messages 1001, 1413/1414, 1434/1435/1431).
 *  - Encrypted payloads: ASCII base64 text followed by a single `0x00`
 *    (confirmed on messages 1000, 1006, 1400/1401, etc). Encoding/decoding
 *    the base64 *text* is handled here; the AES step is `SessionCrypto`'s
 *    job, not this file's.
 */
object DvripPayloads {
    private val JSON_TERMINATOR = byteArrayOf(0x0A, 0x00)
    private const val BASE64_TERMINATOR: Byte = 0x00

    fun encodeJson(json: String): ByteArray = json.toByteArray(Charsets.UTF_8) + JSON_TERMINATOR

    /**
     * Strips the `0x0A 0x00` (or bare `0x00`) terminator and decodes as
     * UTF-8. Returns null if the payload doesn't look like terminated text
     * at all (e.g. raw binary media).
     */
    fun decodeJsonOrNull(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val trimmed = trimTrailingTerminator(payload)
        val text = runCatching { trimmed.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val stripped = text.trim()
        return if (stripped.startsWith("{") && stripped.endsWith("}")) text else null
    }

    fun encodeBase64Text(base64Text: String): ByteArray =
        base64Text.toByteArray(Charsets.US_ASCII) + byteArrayOf(BASE64_TERMINATOR)

    /** Returns the base64 ASCII text with its trailing NUL terminator removed, or null if not base64-looking ASCII. */
    fun decodeBase64TextOrNull(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val trimmed = trimTrailingTerminator(payload)
        val text = runCatching { trimmed.toString(Charsets.US_ASCII) }.getOrNull() ?: return null
        if (text.isEmpty()) return null
        val isBase64Alphabet = text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        return if (isBase64Alphabet) text else null
    }

    private fun trimTrailingTerminator(payload: ByteArray): ByteArray {
        var end = payload.size
        while (end > 0 && payload[end - 1] == 0.toByte()) end--
        while (end > 0 && payload[end - 1] == '\n'.code.toByte()) end--
        return payload.copyOfRange(0, end)
    }
}
