package com.sufficit.ai.gateway.agentinterface.transport

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import com.sufficit.ai.gateway.agentinterface.AgentReplyEnvelope
import com.sufficit.ai.gateway.agentinterface.AgentTurnEnvelope
import org.json.JSONObject

data class RemoteAgentConnectionConfig(
    val endpoint: String,
    val accessToken: String,
    val deviceToken: String,
    val sessionKey: String,
    val userId: String = "",
    val installationId: String = "",
    val backend: String? = null,
    val model: String? = null,
    val metadata: JSONObject = JSONObject()
)

data class RemoteAgentReply(
    val envelope: AgentReplyEnvelope,
    val rawText: String,
    val finalState: String
) {
    val replyText: String get() = envelope.content.text
    val spokenReplyText: String get() = envelope.content.speech
    val needsAttention: Boolean get() = envelope.needsAttention
    val shouldSpeak: Boolean get() = envelope.shouldSpeak
    val speakBlockReason: String? get() = envelope.speakBlockReason
    val isSystemInfo: Boolean get() = envelope.isSystemInfo
    val internalEvent get() = envelope.internalEvent
    val tags: List<String> get() = envelope.tags
    val confidence: Double? get() = envelope.confidence
    val overlap: Boolean get() = envelope.overlap
    val settingsPatch: JSONObject? get() = envelope.settingsPatch
    val transcript: String? get() = envelope.audit.transcript
    val preAgentAction: String? get() = envelope.audit.action
    val preAgentReason: String? get() = envelope.audit.reason
    val shouldForwardToFinalAgent: Boolean? get() = envelope.audit.shouldForwardToFinalAgent
    val errorText: String? get() = envelope.errorText
    val detailsText: String? get() = envelope.content.details
    val actions: List<AgentClientActionRequest> get() = envelope.actions
}

interface RemoteAgentTransport {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onReply(reply: RemoteAgentReply)
        fun onError(message: String, throwable: Throwable? = null)
    }

    fun connect(config: RemoteAgentConnectionConfig)

    fun disconnect()

    fun sendTurn(config: RemoteAgentConnectionConfig, turn: AgentTurnEnvelope): String

    fun sendClientActionResult(
        callId: String,
        tool: String,
        result: String,
        error: String = ""
    ): Boolean
}

