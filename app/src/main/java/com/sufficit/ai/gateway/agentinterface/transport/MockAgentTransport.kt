package com.sufficit.ai.gateway.agentinterface.transport

import com.sufficit.ai.gateway.agentinterface.AgentMessageContent
import com.sufficit.ai.gateway.agentinterface.AgentReplyEnvelope
import com.sufficit.ai.gateway.agentinterface.AgentTurnEnvelope

class MockAgentTransport(
    private val listener: RemoteAgentTransport.Listener,
    private val responder: (AgentTurnEnvelope) -> AgentReplyEnvelope = { turn ->
        AgentReplyEnvelope(
            turnId = turn.turnId,
            content = AgentMessageContent(
                text = "Recebi: ${turn.text}",
                speech = "Recebi: ${turn.text}"
            )
        )
    }
) : RemoteAgentTransport {
    private var connected = false
    val sentTurns: MutableList<AgentTurnEnvelope> = mutableListOf()
    val sentActionResults: MutableList<MockActionResult> = mutableListOf()

    override fun connect(config: RemoteAgentConnectionConfig) {
        connected = true
        listener.onConnected()
    }

    override fun disconnect() {
        if (!connected) return
        connected = false
        listener.onDisconnected("mock disconnected")
    }

    override fun sendTurn(
        config: RemoteAgentConnectionConfig,
        turn: AgentTurnEnvelope
    ): String {
        if (!connected) connect(config)
        sentTurns += turn
        listener.onReply(
            RemoteAgentReply(
                envelope = responder(turn),
                rawText = "mock:${turn.turnId}",
                finalState = "final"
            )
        )
        return turn.turnId
    }

    override fun sendClientActionResult(
        callId: String,
        tool: String,
        result: String,
        error: String
    ): Boolean {
        if (!connected) return false
        sentActionResults += MockActionResult(callId, tool, result, error)
        return true
    }
}

data class MockActionResult(
    val callId: String,
    val tool: String,
    val result: String,
    val error: String
)

