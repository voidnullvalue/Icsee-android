package com.voidnullvalue.icseelocal.video

/**
 * Identifies which credential set an RTSP attempt is using without exposing the
 * actual username/password to retry-policy code or diagnostics.
 */
internal enum class RtspCredentialSource {
    CONFIGURED,
    FACTORY,
}

internal enum class RtspFailureKind {
    AUTHENTICATION,
    DECODER,
    OTHER,
}

internal data class RtspAttemptRoute(
    val credentialSource: RtspCredentialSource,
    val forceTcp: Boolean,
    val mainStream: Boolean,
)

/**
 * Chooses the next RTSP attempt without allowing an unrelated playback failure
 * to silently change credentials.
 *
 * Authentication failures may try the alternate credential set, but only for
 * the same stream and transport. Decoder and other failures remain on the
 * credentials that reached that stage of playback while trying the existing
 * stream/transport fallbacks.
 */
internal object RtspRetryPolicy {
    fun nextAttemptIndex(
        attempts: List<RtspAttemptRoute>,
        currentIndex: Int,
        failureKind: RtspFailureKind,
    ): Int? {
        val current = attempts.getOrNull(currentIndex) ?: return null
        val remaining = (currentIndex + 1) until attempts.size

        return when (failureKind) {
            RtspFailureKind.AUTHENTICATION ->
                remaining.firstOrNull { index ->
                    val candidate = attempts[index]
                    candidate.forceTcp == current.forceTcp &&
                        candidate.mainStream == current.mainStream &&
                        candidate.credentialSource != current.credentialSource
                }

            RtspFailureKind.DECODER ->
                remaining.firstOrNull { index ->
                    val candidate = attempts[index]
                    candidate.credentialSource == current.credentialSource &&
                        candidate.forceTcp == current.forceTcp &&
                        !candidate.mainStream
                } ?: remaining.firstOrNull { index ->
                    attempts[index].credentialSource == current.credentialSource
                }

            RtspFailureKind.OTHER ->
                remaining.firstOrNull { index ->
                    attempts[index].credentialSource == current.credentialSource
                }
        }
    }
}
