package com.voidnullvalue.icseelocal.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedHistoryTest {
    @Test
    fun `never grows past the configured bound`() {
        val history = BoundedHistory(maxEntries = 5)
        repeat(20) { i ->
            history.add(CommandHistoryEntry(i.toLong(), CommandHistoryEntry.Direction.SENT, 1400, "cmd$i"))
        }
        val snapshot = history.snapshot()
        assertEquals(5, snapshot.size)
        // oldest entries evicted first
        assertEquals("cmd15", snapshot.first().summary)
        assertEquals("cmd19", snapshot.last().summary)
    }

    @Test
    fun `clear empties the history`() {
        val history = BoundedHistory()
        history.add(CommandHistoryEntry(0, CommandHistoryEntry.Direction.SENT, 1400, "x"))
        history.clear()
        assertEquals(0, history.snapshot().size)
    }
}
