package com.sufficit.ai.gateway.agentinterface

import org.json.JSONObject

object AgentProtocolVersion {
    const val CURRENT = 1
}

enum class AgentInputMode(val wireValue: String) {
    VOICE("voice"),
    TEXT("text"),
    GESTURE("gesture"),
    MULTIMODAL("multimodal"),
    UNKNOWN("unknown");

    companion object {
        fun fromWireValue(value: String): AgentInputMode =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() } ?: UNKNOWN
    }
}

data class AgentChannelContext(
    val inputMode: AgentInputMode,
    val awakened: Boolean,
    val wakeWord: String? = null,
    val surface: String = "android_mobile_chat",
    val voiceReplyAvailable: Boolean = true,
    val multipleVoicesLikely: Boolean = false,
    val detectedSpeakerCount: Int? = null,
    val nonVerbalAudioEvents: List<String> = emptyList(),
    val transcriptionAnalysisSources: List<String> = emptyList(),
    val transcriptionAvailableSignals: List<String> = emptyList(),
    val transcriptionReliabilityScore: Double? = null,
    val transcriptionNoiseScore: Double? = null,
    val transcriptionLanguageCode: String? = null,
    val transcriptionLanguageProbability: Double? = null
) {
    init {
        require(!awakened || !wakeWord.isNullOrBlank()) {
            "wakeWord deve identificar a chamada quando awakened=true."
        }
        require(detectedSpeakerCount == null || detectedSpeakerCount > 0) {
            "detectedSpeakerCount deve ser positivo quando informado."
        }
    }
}

data class AgentPresentationHints(
    val preferConcise: Boolean = true,
    val preferSpeakable: Boolean = true,
    val preferredTextChars: Int = 480,
    val preferredSpeechSeconds: Int = 25,
    val supportsDetails: Boolean = true,
    val supportsAttachments: Boolean = true,
    val supportsClientActions: Boolean = true
)

data class AgentTurnEnvelope(
    val turnId: String,
    val text: String,
    val interaction: AgentChannelContext,
    val presentation: AgentPresentationHints = AgentPresentationHints(),
    val availableTools: List<JSONObject> = emptyList(),
    val metadata: JSONObject = JSONObject(),
    val schemaVersion: Int = AgentProtocolVersion.CURRENT
) {
    init {
        require(turnId.isNotBlank()) { "turnId vazio." }
        require(text.isNotBlank()) { "Texto do turno vazio." }
        require(schemaVersion > 0) { "schemaVersion invalido." }
    }
}

data class AgentAttachment(
    val kind: String,
    val name: String,
    val uri: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
) {
    init {
        require(kind.isNotBlank()) { "Tipo do anexo vazio." }
        require(name.isNotBlank()) { "Nome do anexo vazio." }
        require(uri.isNotBlank()) { "URI do anexo vazia." }
    }
}

data class AgentMessageContent(
    val text: String,
    val speech: String = text,
    val details: String? = null,
    val attachments: List<AgentAttachment> = emptyList()
)

data class AgentClientActionRequest(
    val tool: String,
    val callId: String? = null,
    val arguments: JSONObject = JSONObject(),
    val raw: JSONObject = JSONObject()
) {
    init {
        require(tool.isNotBlank()) { "Nome da ferramenta vazio." }
    }
}

enum class AgentActivityState(val wireValue: String) {
    QUEUED("queued"),
    PROCESSING("processing"),
    EXECUTING_ACTION("executing_action"),
    VERIFYING("verifying"),
    COMPLETED("completed"),
    FAILED("failed");

    companion object {
        fun fromWireValue(value: String): AgentActivityState? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}

data class AgentAuditDecision(
    val transcript: String? = null,
    val action: String? = null,
    val reason: String? = null,
    val shouldForwardToFinalAgent: Boolean? = null
)

enum class AgentInternalEventType(
    val wireValue: String,
    val systemMessage: String
) {
    CONTEXT_COMPACTION("context_compaction", "Contexto interno do agente compactado."),
    MEMORY_INTERNAL("memory_internal", "Memoria interna do agente atualizada."),
    MAINTENANCE("maintenance", "Manutencao interna do agente concluida."),
    SYSTEM_INTERNAL("system_internal", "Evento interno do agente processado.");

    companion object {
        fun fromWireValue(value: String): AgentInternalEventType? {
            val normalized = value.trim().lowercase().replace('.', '_')
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

data class AgentReplyEnvelope(
    val content: AgentMessageContent,
    val turnId: String? = null,
    val actions: List<AgentClientActionRequest> = emptyList(),
    val activityState: AgentActivityState? = null,
    val needsAttention: Boolean = false,
    val shouldSpeak: Boolean = true,
    val speakBlockReason: String? = null,
    val tags: List<String> = emptyList(),
    val confidence: Double? = null,
    val overlap: Boolean = false,
    val audit: AgentAuditDecision = AgentAuditDecision(),
    val errorText: String? = null,
    val internalEvent: AgentInternalEventType? = null,
    val isSystemInfo: Boolean = false,
    val settingsPatch: JSONObject? = null,
    val schemaVersion: Int = AgentProtocolVersion.CURRENT
)
