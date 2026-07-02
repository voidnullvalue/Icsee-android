package com.voidnullvalue.icseelocal.ptz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PtzRequestBuilderTest {

    @Test
    fun `builds the exact envelope shape from the task brief`() {
        val json = PtzRequestBuilder.build(PtzCommand.DIRECTION_RIGHT, sessionId = 0x1Bu, step = 2, preset = 0)
        assertEquals(
            """{"Name":"OPPTZControl","SessionID":"0x0000001B","OPPTZControl":{"Command":"DirectionRight",""" +
                """"Parameter":{"AUX":{"Number":0,"Status":"On"},"Channel":0,"MenuOpts":"Enter","Pattern":"Start",""" +
                """"Preset":0,"Step":2,"Tour":0}}}""",
            json,
        )
    }

    @Test
    fun `zoom command is spelled ZoomTile not ZoomTele`() {
        assertEquals("ZoomTile", PtzCommand.ZOOM_TILE.wireValue)
        assertTrue(PtzRequestBuilder.build(PtzCommand.ZOOM_TILE, 0x1Bu).contains("\"ZoomTile\""))
    }

    @Test
    fun `tour flag is 1 only for tour commands`() {
        assertTrue(PtzRequestBuilder.build(PtzCommand.START_TOUR, 0x1Bu).contains("\"Tour\":1"))
        assertTrue(PtzRequestBuilder.build(PtzCommand.STOP_TOUR, 0x1Bu).contains("\"Tour\":1"))
        assertTrue(PtzRequestBuilder.build(PtzCommand.DIRECTION_UP, 0x1Bu).contains("\"Tour\":0"))
    }

    @Test
    fun `rejects step outside 0 to 10`() {
        assertThrows(IllegalArgumentException::class.java) { PtzRequestBuilder.build(PtzCommand.DIRECTION_UP, 0x1Bu, step = 11) }
        assertThrows(IllegalArgumentException::class.java) { PtzRequestBuilder.build(PtzCommand.DIRECTION_UP, 0x1Bu, step = -1) }
    }

    @Test
    fun `rejects preset outside -1 to 100`() {
        assertThrows(IllegalArgumentException::class.java) { PtzRequestBuilder.build(PtzCommand.GOTO_PRESET, 0x1Bu, preset = 101) }
        assertThrows(IllegalArgumentException::class.java) { PtzRequestBuilder.build(PtzCommand.GOTO_PRESET, 0x1Bu, preset = -2) }
    }

    @Test
    fun `compatibility stop matches the documented DirectionUp Preset -1 Step 5 shape`() {
        val json = PtzRequestBuilder.buildCompatibilityStop(sessionId = 0x1Bu)
        assertTrue(json.contains("\"Command\":\"DirectionUp\""))
        assertTrue(json.contains("\"Preset\":-1"))
        assertTrue(json.contains("\"Step\":5"))
        assertTrue(json.contains("\"Channel\":0"))
    }
}
