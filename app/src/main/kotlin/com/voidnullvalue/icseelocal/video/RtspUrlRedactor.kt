package com.voidnullvalue.icseelocal.video

/**
 * Scrubs credentials embedded in vendor RTSP URLs (`user=…&password=…`) and
 * similar auth fragments so they never appear in logs, toasts, or error UI.
 */
object RtspUrlRedactor {
    private val passwordInPath = Regex("""([?&]password=)[^&\s]+""", RegexOption.IGNORE_CASE)
    private val userinfo = Regex("""(rtsp://)([^:/@\s]+):([^@/\s]+)@""", RegexOption.IGNORE_CASE)

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace(passwordInPath) { "${it.groupValues[1]}***" }
            .replace(userinfo) { "${it.groupValues[1]}${it.groupValues[2]}:***@" }
    }
}
