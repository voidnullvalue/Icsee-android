package com.voidnullvalue.icseelocal.video

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds this camera family's RTSP URL convention -- confirmed live
 * 2026-07-01 against the target camera (`DESCRIBE` -> `200 OK` with a real
 * SDP: H.265 video, PCMA audio; full `SETUP`/`PLAY` confirmed delivering
 * real RTP bytes over TCP-interleaved transport). See PROTOCOL_NOTES.md
 * "RTSP video -- LIVE CONFIRMED".
 *
 * Credentials are embedded directly in the URL path (not the standard
 * `rtsp://user:pass@host` userinfo form, and not a `WWW-Authenticate`
 * Digest exchange) -- that's this vendor's convention, confirmed by
 * observing the server return `200 OK` directly for a matching
 * credential with no challenge round-trip.
 *
 * [FALLBACK_USERNAME]/[FALLBACK_PASSWORD] is a real, reproducible
 * factory-default RTSP account confirmed live on the target camera --
 * distinct from (and not guaranteed to be the same as) the DVRIP account
 * a user configures in this app. Not assumed to work on every camera in
 * this family; used as a fallback if the user's own configured
 * credentials are rejected.
 */
object RtspUrlBuilder {
    const val FALLBACK_USERNAME = "admin"
    const val FALLBACK_PASSWORD = ""

    fun build(
        host: String,
        port: Int = 554,
        username: String,
        password: String,
        channel: Int = 1,
        mainStream: Boolean = true,
    ): String {
        val streamIndex = if (mainStream) 0 else 1
        // Encode so passwords containing '&', '?', '#', spaces, etc. cannot
        // truncate the vendor path (`user=…&password=…&channel=…`).
        val user = encodeComponent(username)
        val pass = encodeComponent(password)
        return "rtsp://$host:$port/user=$user&password=$pass&channel=$channel&stream=$streamIndex.sdp"
    }

    /** Percent-encode a path component; spaces as `%20` (not `+`). */
    internal fun encodeComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
