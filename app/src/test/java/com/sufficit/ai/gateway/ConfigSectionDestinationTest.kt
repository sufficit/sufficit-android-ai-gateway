package com.sufficit.ai.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigSectionDestinationTest {
    @Test
    fun `back navigation follows category hierarchy`() {
        assertEquals(ConfigSectionDestination.HOME, ConfigSectionDestination.CONNECTIONS.parent())
        assertEquals(ConfigSectionDestination.CONNECTIONS, ConfigSectionDestination.ACCESS.parent())
        assertEquals(ConfigSectionDestination.CONNECTIONS, ConfigSectionDestination.MCP.parent())
        assertEquals(ConfigSectionDestination.DEVICE, ConfigSectionDestination.HISTORY.parent())
        assertEquals(ConfigSectionDestination.VOICE, ConfigSectionDestination.TRANSCRIPTION.parent())
        assertEquals(ConfigSectionDestination.START, ConfigSectionDestination.WAKE_WORD.parent())
    }
}
