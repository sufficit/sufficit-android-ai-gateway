package com.sufficit.ai.gateway.openclaw

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawGatewayPersistentConnectionTest {
    @Test
    fun reconnectBackoffStartsFastAndCapsAtThirtySeconds() {
        assertEquals(2L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(0))
        assertEquals(4L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(1))
        assertEquals(8L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(2))
        assertEquals(16L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(3))
        assertEquals(30L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(4))
        assertEquals(30L, OpenClawGatewayPersistentConnection.reconnectDelaySeconds(20))
    }
}
