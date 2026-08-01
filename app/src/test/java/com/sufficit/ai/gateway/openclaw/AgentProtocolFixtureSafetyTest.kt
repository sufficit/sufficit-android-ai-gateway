package com.sufficit.ai.gateway.openclaw

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolFixtureSafetyTest {
    @Test
    fun fixturesAreSanitizedAndCoverTheProtocolBaseline() {
        val root = requireNotNull(javaClass.getResource("/agent-protocol"))
        val files = File(root.toURI()).listFiles().orEmpty().filter(File::isFile)

        assertTrue(files.size >= 6)
        val combined = files.joinToString("\n") { it.readText() }.lowercase()
        listOf("bearer ", "access_token", "refresh_token", "deviceToken", "gatewayToken")
            .forEach { forbidden -> assertFalse(combined.contains(forbidden.lowercase())) }
        assertTrue(combined.contains("\"awakened\": true"))
        assertTrue(combined.contains("\"wakeword\": \"xuxu\""))
        assertTrue(combined.contains("\"callid\": \"call-fixture-001\""))
    }
}
