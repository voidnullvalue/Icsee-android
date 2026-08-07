package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RtspUrlRedactorTest {

    @Test
    fun `redacts password query in vendor path`() {
        val raw = "rtsp://192.168.1.1:554/user=admin&password=s3cret&channel=1&stream=0.sdp"
        val redacted = RtspUrlRedactor.redact(raw)
        assertEquals(
            "rtsp://192.168.1.1:554/user=admin&password=***&channel=1&stream=0.sdp",
            redacted,
        )
        assertFalse(redacted.contains("s3cret"))
    }

    @Test
    fun `redacts userinfo form`() {
        val raw = "Source error rtsp://bob:hunter2@192.168.0.5/stream"
        assertEquals(
            "Source error rtsp://bob:***@192.168.0.5/stream",
            RtspUrlRedactor.redact(raw),
        )
    }

    @Test
    fun `leaves unrelated text alone`() {
        assertEquals("decoder init failed", RtspUrlRedactor.redact("decoder init failed"))
    }
}
