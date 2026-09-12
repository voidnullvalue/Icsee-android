package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspRetryPolicyTest {

    private val attempts = listOf(
        RtspAttemptRoute(RtspCredentialSource.CONFIGURED, forceTcp = true, mainStream = true),
        RtspAttemptRoute(RtspCredentialSource.FACTORY, forceTcp = true, mainStream = true),
        RtspAttemptRoute(RtspCredentialSource.CONFIGURED, forceTcp = true, mainStream = false),
        RtspAttemptRoute(RtspCredentialSource.FACTORY, forceTcp = true, mainStream = false),
        RtspAttemptRoute(RtspCredentialSource.CONFIGURED, forceTcp = false, mainStream = true),
        RtspAttemptRoute(RtspCredentialSource.FACTORY, forceTcp = false, mainStream = true),
        RtspAttemptRoute(RtspCredentialSource.CONFIGURED, forceTcp = false, mainStream = false),
        RtspAttemptRoute(RtspCredentialSource.FACTORY, forceTcp = false, mainStream = false),
    )

    @Test
    fun `non auth failure keeps configured credentials`() {
        assertEquals(
            2,
            RtspRetryPolicy.nextAttemptIndex(
                attempts,
                currentIndex = 0,
                failureKind = RtspFailureKind.OTHER,
            ),
        )
    }

    @Test
    fun `non auth failure keeps factory credentials when factory was selected`() {
        assertEquals(
            3,
            RtspRetryPolicy.nextAttemptIndex(
                attempts,
                currentIndex = 1,
                failureKind = RtspFailureKind.OTHER,
            ),
        )
    }

    @Test
    fun `decoder failure prefers sub stream with same credentials and transport`() {
        assertEquals(
            2,
            RtspRetryPolicy.nextAttemptIndex(
                attempts,
                currentIndex = 0,
                failureKind = RtspFailureKind.DECODER,
            ),
        )
    }

    @Test
    fun `authentication failure alone may switch credentials`() {
        assertEquals(
            1,
            RtspRetryPolicy.nextAttemptIndex(
                attempts,
                currentIndex = 0,
                failureKind = RtspFailureKind.AUTHENTICATION,
            ),
        )
    }

    @Test
    fun `authentication failure stops after alternate credentials also fail`() {
        assertNull(
            RtspRetryPolicy.nextAttemptIndex(
                attempts,
                currentIndex = 1,
                failureKind = RtspFailureKind.AUTHENTICATION,
            ),
        )
    }
}
