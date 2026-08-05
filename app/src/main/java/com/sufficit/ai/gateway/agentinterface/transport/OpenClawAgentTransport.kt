package com.sufficit.ai.gateway.agentinterface.transport

import com.sufficit.ai.gateway.agentinterface.AgentAuditDecision
import com.sufficit.ai.gateway.agentinterface.AgentMessageContent
import com.sufficit.ai.gateway.agentinterface.AgentReplyEnvelope
import com.sufficit.ai.gateway.agentinterface.AgentTurnEnvelope
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayConfig
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayPersistentConnection
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply
import org.json.JSONArray
import org.json.JSONObject

class OpenClawAgentTransport(
    private val listener: RemoteAgentTransport.Listener
) : RemoteAgentTransport {
    private val connection = OpenClawGatewayPersistentConnection(
        object : OpenClawGatewayPersistentConnection.Listener {
            override fun onConnected() = listener.onConnected()

            override fun onDisconnected(reason: String) = listener.onDisconnected(reason)

            override fun onReply(reply: OpenClawGatewayReply) {
                listener.onReply(
                    RemoteAgentReply(
                        envelope = reply.canonicalReply ?: reply.toCanonicalFallback(),
                        rawText = reply.rawReplyText,
                        finalState = reply.finalState
                    )
                )
            }

            override fun onError(message: String, throwable: Throwable?) =
                listener.onError(message, throwable)
        }
    )

    override fun connect(config: RemoteAgentConnectionConfig) {
        connection.connect(OpenClawTransportMapper.toOpenClawConfig(config))
    }

    override fun disconnect() = connection.disconnect()

    override fun sendTurn(config: RemoteAgentConnectionConfig, turn: AgentTurnEnvelope): String {
        val metadata = OpenClawTransportMapper.mergeTurnMetadata(config.metadata, turn)
        return connection.sendTranscript(
            config = OpenClawTransportMapper.toOpenClawConfig(config.copy(metadata = metadata)),
            transcript = turn.text,
            segmentId = turn.turnId
        )
    }

    override fun sendClientActionResult(
        callId: String,
        tool: String,
        result: String,
        error: String
    ): Boolean = connection.sendClientToolResult(callId, tool, result, error)

    private fun OpenClawGatewayReply.toCanonicalFallback(): AgentReplyEnvelope =
        AgentReplyEnvelope(
            content = AgentMessageContent(
                text = replyText,
                speech = spokenReplyText,
                details = detailsText
            ),
            actions = actions.mapNotNull { action ->
                val tool = action.optString("tool").trim()
                if (tool.isBlank()) null else com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest(
                    tool = tool,
                    callId = action.optString("callId").trim().ifBlank { null },
                    raw = JSONObject(action.toString())
                )
            },
            needsAttention = needsAttention,
            shouldSpeak = shouldSpeak,
            speakBlockReason = speakBlockReason,
            tags = tags,
            confidence = confidence,
            overlap = overlap,
            audit = AgentAuditDecision(
                transcript = transcript,
                action = preAgentAction,
                reason = preAgentReason,
                shouldForwardToFinalAgent = shouldForwardToFinalAgent
            ),
            errorText = errorText,
            internalEvent = internalEvent?.canonicalType,
            isSystemInfo = isSystemInfo,
            settingsPatch = settingsPatch
        )
}

internal object OpenClawTransportMapper {
    fun toOpenClawConfig(config: RemoteAgentConnectionConfig): OpenClawGatewayConfig =
        OpenClawGatewayConfig(
            gatewayUrl = config.endpoint,
            gatewayToken = config.accessToken,
            deviceToken = config.deviceToken,
            sessionKey = config.sessionKey,
            userId = config.userId,
            installationId = config.installationId,
            backend = config.backend,
            model = config.model,
            metadata = JSONObject(config.metadata.toString())
        )

    fun mergeTurnMetadata(
        base: JSONObject,
        turn: AgentTurnEnvelope
    ): JSONObject = JSONObject(base.toString()).apply {
        LEGACY_WAKE_METADATA_KEYS.forEach(::remove)
        put("agentProtocolVersion", turn.schemaVersion)
        put("turnId", turn.turnId)
        put("surface", turn.interaction.surface)
        put("inputMode", turn.interaction.inputMode.wireValue)
        put("voiceReplyAvailable", turn.interaction.voiceReplyAvailable)
        put("awakened", turn.interaction.awakened)
        put(
            "wakeWord",
            turn.interaction.wakeWord?.takeIf { turn.interaction.awakened } ?: JSONObject.NULL
        )
        put("multipleVoicesLikely", turn.interaction.multipleVoicesLikely)
        put("transcriptionAnalysis", JSONObject().apply {
            put("reliabilityScore", turn.interaction.transcriptionReliabilityScore ?: JSONObject.NULL)
            put("reliabilitySource", "gateway_audio_heuristic")
            put("noiseScore", turn.interaction.transcriptionNoiseScore ?: JSONObject.NULL)
            put("detectedSpeakerCount", turn.interaction.detectedSpeakerCount ?: JSONObject.NULL)
            put("multipleVoicesLikely", turn.interaction.multipleVoicesLikely)
            put("nonVerbalEvents", JSONArray(turn.interaction.nonVerbalAudioEvents))
            put("analysisSources", JSONArray(turn.interaction.transcriptionAnalysisSources))
            put("availableSignals", JSONArray(turn.interaction.transcriptionAvailableSignals))
            put("languageCode", turn.interaction.transcriptionLanguageCode ?: JSONObject.NULL)
            put(
                "languageProbability",
                turn.interaction.transcriptionLanguageProbability ?: JSONObject.NULL
            )
        })
        put(
            "presentation",
            JSONObject()
                .put("preferConcise", turn.presentation.preferConcise)
                .put("preferSpeakable", turn.presentation.preferSpeakable)
                .put("preferredTextChars", turn.presentation.preferredTextChars)
                .put("preferredSpeechSeconds", turn.presentation.preferredSpeechSeconds)
                .put("supportsDetails", turn.presentation.supportsDetails)
                .put("supportsAttachments", turn.presentation.supportsAttachments)
                .put("supportsClientActions", turn.presentation.supportsClientActions)
        )
        put("availableTools", JSONArray().apply {
            turn.availableTools.forEach { put(JSONObject(it.toString())) }
        })
    }

    private val LEGACY_WAKE_METADATA_KEYS = setOf(
        "wakeWordSessionActive",
        "wakeWordSession",
        "directAddress",
        "isDirectAddress",
        "matchedWakeTerm",
        "secondsSinceDirectAddress",
        "contextResetRequested",
        "shouldAskForWakeConfirmation"
    )
}
