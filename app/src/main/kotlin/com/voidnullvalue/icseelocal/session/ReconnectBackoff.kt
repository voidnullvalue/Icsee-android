package com.voidnullvalue.icseelocal.session

/**
 * Bounded exponential backoff so a persistently-unreachable camera can't cause a
 * reconnect storm. Deliberately *slow*: each reconnect is a real DVRIP login, and
 * this firmware locks (Ret:205) on rapid login bursts, so the first retry waits
 * 20s and they grow from there (20s, 40s, 80s, ...). Recovering from a transient
 * blip a little slower is a fair price for never machine-gunning the camera's
 * login endpoint -- and live video (RTSP) keeps playing across the gap regardless,
 * since it doesn't depend on this control session.
 */
class ReconnectBackoff(
    private val baseDelayMillis: Long = 20_000,
    private val maxDelayMillis: Long = 300_000,
    private val factor: Double = 2.0,
) {
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 0)
        if (attempt == 0) return 0
        val raw = baseDelayMillis * Math.pow(factor, (attempt - 1).toDouble())
        return raw.toLong().coerceIn(baseDelayMillis, maxDelayMillis)
    }
}
