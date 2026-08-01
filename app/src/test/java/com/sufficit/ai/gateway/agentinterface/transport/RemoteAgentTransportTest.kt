package com.sufficit.ai.gateway.agentinterface.transport

import com.sufficit.ai.gateway.agentinterface.AgentChannelContext
import com.sufficit.ai.gateway.agentinterface.AgentInputMode
import com.sufficit.ai.gateway.agentinterface.AgentTurnEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentTransportTest {
    @Test
    fun mockCompletesAWholeTurnWithoutWebSocket() {
        val events = RecordingListener()
        val transport = MockAgentTransport(events)
        val config = config()
        val turn = turn()

        val receipt = transport.sendTurn(config, turn)

        assertEquals(turn.turnId, receipt)
        assertTrue(events.connected)
        assertEquals("Recebi: ligue a luz", events.lastReply?.replyText)
        assertEquals(turn, transport.sentTurns.single())
        assertTrue(transport.sendClientActionResult("call-1", "effect", "ok"))
        assertEquals("call-1", transport.sentActionResults.single().callId)
    }

    @Test
    fun openClawMapperKeepsWakeAndMobilePresentationInMetadata() {
        val metadata = OpenClawTransportMapper.mergeTurnMetadata(
            JSONObject()
                .put("wakeWordSessionActive", true)
                .put("matchedWakeTerm", "legacy")
                .put("isDirectAddress", true),
            turn()
        )

        assertEquals(1, metadata.getInt("agentProtocolVersion"))
        assertEquals("turn-transport-001", metadata.getString("turnId"))
        assertTrue(metadata.getBoolean("awakened"))
        assertEquals("xuxu", metadata.getString("wakeWord"))
        assertEquals("android_mobile_chat", metadata.getString("surface"))
        assertTrue(metadata.getJSONObject("presentation").getBoolean("preferConcise"))
        assertFalse(metadata.has("wakeWordSessionActive"))
        assertFalse(metadata.has("matchedWakeTerm"))
        assertFalse(metadata.has("isDirectAddress"))
    }

    private fun turn() = AgentTurnEnvelope(
        turnId = "turn-transport-001",
        text = "ligue a luz",
        interaction = AgentChannelContext(
            inputMode = AgentInputMode.VOICE,
            awakened = true,
            wakeWord = "xuxu"
        )
    )

    private fun config() = RemoteAgentConnectionConfig(
        endpoint = "wss://example.invalid",
        accessToken = "fixture-access",
        deviceToken = "fixture-device",
        sessionKey = "fixture-session"
    )

    private class RecordingListener : RemoteAgentTransport.Listener {
        var connected = false
        var lastReply: RemoteAgentReply? = null

        override fun onConnected() {
            connected = true
        }

        override fun onDisconnected(reason: String) {
            connected = false
        }

        override fun onReply(reply: RemoteAgentReply) {
            lastReply = reply
        }

        override fun onError(message: String, throwable: Throwable?) = Unit
    }
}
