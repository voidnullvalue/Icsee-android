package com.voidnullvalue.icseelocal.session

/** Bounded exponential backoff so a persistently-unreachable camera can't cause a reconnect storm. */
class ReconnectBackoff(
    private val baseDelayMillis: Long = 1000,
    private val maxDelayMillis: Long = 30_000,
    private val factor: Double = 2.0,
) {
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 0)
        if (attempt == 0) return 0
        val raw = baseDelayMillis * Math.pow(factor, (attempt - 1).toDouble())
        return raw.toLong().coerceIn(baseDelayMillis, maxDelayMillis)
    }
}
