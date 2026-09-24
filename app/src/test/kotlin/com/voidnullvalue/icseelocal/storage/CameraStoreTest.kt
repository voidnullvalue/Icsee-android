package com.voidnullvalue.icseelocal.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStoreTest {
    @Test
    fun `duplicate camera ids keep the newest stored entry`() {
        val old = storedCamera(id = "same", displayName = "Old")
        val other = storedCamera(id = "other", displayName = "Other")
        val newest = storedCamera(id = "same", displayName = "Newest")

        val repaired = listOf(old, other, newest).deduplicateByIdKeepingNewest()

        assertEquals(listOf("other", "same"), repaired.map { it.id })
        assertEquals("Newest", repaired.last().displayName)
    }

    private fun storedCamera(id: String, displayName: String) = StoredCamera(
        id = id,
        displayName = displayName,
        host = "192.0.2.1",
        dvripPort = 34567,
        channel = 0,
        streamType = "MAIN",
        rtspFallbackEnabled = true,
        rtspPort = 554,
    )
}
