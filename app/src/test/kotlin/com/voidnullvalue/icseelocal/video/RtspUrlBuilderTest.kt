package com.voidnullvalue.icseelocal.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RtspUrlBuilderTest {

    @Test
    fun `builds the confirmed working URL shape for the main stream`() {
        val url = RtspUrlBuilder.build(
            host = "192.168.1.100",
            username = "testuser",
            password = "testpass",
        )
        assertEquals("rtsp://192.168.1.100:554/user=testuser&password=testpass&channel=1&stream=0.sdp", url)
    }

    @Test
    fun `sub stream uses stream index 1`() {
        val url = RtspUrlBuilder.build(
            host = "192.168.1.100",
            username = "testuser",
            password = "testpass",
            mainStream = false,
        )
        assertEquals("rtsp://192.168.1.100:554/user=testuser&password=testpass&channel=1&stream=1.sdp", url)
    }

    @Test
    fun `fallback credentials match the live-confirmed factory default`() {
        val url = RtspUrlBuilder.build(
            host = "192.168.1.100",
            username = RtspUrlBuilder.FALLBACK_USERNAME,
            password = RtspUrlBuilder.FALLBACK_PASSWORD,
        )
        assertEquals("rtsp://192.168.1.100:554/user=admin&password=&channel=1&stream=0.sdp", url)
    }

    @Test
    fun `encodes special characters in the password so amp cannot truncate the path`() {
        val url = RtspUrlBuilder.build(
            host = "192.168.1.100",
            username = "user",
            password = "a&b=c",
        )
        assertEquals("rtsp://192.168.1.100:554/user=user&password=a%26b%3Dc&channel=1&stream=0.sdp", url)
    }
}
