package com.sufficit.ai.gateway.openclaw

import com.sufficit.ai.gateway.agentinterface.AgentAuditDecision
import com.sufficit.ai.gateway.agentinterface.AgentInternalEventType
import com.sufficit.ai.gateway.agentinterface.AgentMessageContent
import com.sufficit.ai.gateway.agentinterface.AgentProtocolCodec
import com.sufficit.ai.gateway.agentinterface.AgentReplyEnvelope
import com.sufficit.ai.gateway.agentinterface.RemoteAgentEventClassifier
import org.json.JSONObject

/** Eventos internos do gateway que jamais representam uma fala do assistente. */
enum class OpenClawInternalEvent(
    val canonicalType: AgentInternalEventType,
    val systemMessage: String
) {
    CONTEXT_COMPACTION(
        AgentInternalEventType.CONTEXT_COMPACTION,
        "Contexto interno do agente compactado."
    ),
    MEMORY_INTERNAL(
        AgentInternalEventType.MEMORY_INTERNAL,
        "Memoria interna do agente atualizada."
    ),
    MAINTENANCE(
        AgentInternalEventType.MAINTENANCE,
        "Manutencao interna do agente concluida."
    ),
    SYSTEM_INTERNAL(
        AgentInternalEventType.SYSTEM_INTERNAL,
        "Evento interno do agente processado."
    );

    companion object {
        fun fromCanonical(value: AgentInternalEventType?): OpenClawInternalEvent? =
            entries.firstOrNull { it.canonicalType == value }
    }
}

/**
 * Defesa no cliente para eventos de manutencao que um gateway legado possa
 * devolver como texto puro. O servidor tambem classifica o evento, mas esta
 * camada impede que qualquer variacao volte a ser lida pelo TTS.
 */
object OpenClawInternalEventClassifier {
    fun detect(vararg values: String?): OpenClawInternalEvent? {
        return OpenClawInternalEvent.fromCanonical(
            RemoteAgentEventClassifier.detectInternalEvent(textValues = values)
        )
    }
}

data class OpenClawReplyEnvelope(
    val rawText: String,
    val replyText: String,
    val spokenReplyText: String,
    val needsAttention: Boolean,
    val shouldSpeak: Boolean,
    val speakBlockReason: String?,
    val isSystemInfo: Boolean,
    val internalEvent: OpenClawInternalEvent? = null,
    val tags: List<String>,
    val confidence: Double?,
    val overlap: Boolean,
    val settingsPatch: JSONObject?,
    /** Texto do turno que o gateway efetivamente avaliou. */
    val transcript: String? = null,
    /** Decisão do pré-agente Android (forward/discard/hold/review/ignore). */
    val preAgentAction: String? = null,
    /** Código auditável da decisão (ex.: ambient_not_directed_to_agent). */
    val preAgentReason: String? = null,
    val shouldForwardToFinalAgent: Boolean? = null,
    /**
     * Falha do agente no servidor (campo "error" do envelope). Detalhe cru
     * para log/status — NUNCA vira bolha de chat nem é lido pelo TTS.
     */
    val errorText: String? = null,
    /**
     * Conteúdo visual-apenas (campo "details"): endereços, links, código,
     * explicações longas. Mostrado no chat como painel expansível; NUNCA é
     * falado pelo TTS.
     */
    val detailsText: String? = null,
    /**
     * Comandos de ferramenta que o agente escolheu executar no aparelho (campo
     * "actions"). Cada item e um JSON {"tool":"<nome>", ...args}. O telefone
     * executa localmente pela sua conexao de saida — nao precisa de rede de
     * entrada (substitui a skill que exigia mesma rede). Ferramentas: screenshot,
     * photo, wake, effect, say, listen, standby, interrupt, config, clearChat.
     */
    val actions: List<JSONObject> = emptyList(),
    /** Envelope neutro usado pelos novos adapters e pela apresentacao movel. */
    val canonicalReply: AgentReplyEnvelope? = null
)

internal object OpenClawReplyEnvelopeParser {
    fun parse(
        rawText: String,
        uncertainPrefix: String
    ): OpenClawReplyEnvelope {
        val normalizedRaw = rawText.trim()
        val parsedJson = parseJsonObject(normalizedRaw)
        if (parsedJson != null) {
            val source = parsedJson.optString("source").trim()
            val canonical = AgentProtocolCodec.decodeReply(parsedJson)
            val internalEvent = OpenClawInternalEvent.fromCanonical(canonical.internalEvent)
            val isSystemInfo = (parsedJson.booleanOrNull("isSystemInfo") ?: false) || internalEvent != null
            val actions = canonical.actions.map { request ->
                if (request.raw.length() > 0) {
                    JSONObject(request.raw.toString())
                } else {
                    JSONObject().put("tool", request.tool).apply {
                        request.callId?.let { put("callId", it) }
                    }
                }
            }
            val rawEnvelopeText = when {
                source.equals("android-pre-agent", ignoreCase = true) -> normalizedRaw
                canonical.content.text.isNotBlank() -> normalizedRaw
                else -> normalizedRaw
            }
            return OpenClawReplyEnvelope(
                rawText = rawEnvelopeText,
                replyText = internalEvent?.systemMessage ?: canonical.content.text,
                spokenReplyText = if (internalEvent == null) canonical.content.speech else "",
                needsAttention = canonical.needsAttention,
                shouldSpeak = canonical.shouldSpeak && !isSystemInfo,
                speakBlockReason = canonical.speakBlockReason,
                isSystemInfo = isSystemInfo,
                internalEvent = internalEvent,
                tags = canonical.tags,
                confidence = canonical.confidence,
                overlap = canonical.overlap,
                settingsPatch = canonical.settingsPatch,
                transcript = canonical.audit.transcript,
                preAgentAction = canonical.audit.action,
                preAgentReason = canonical.audit.reason,
                shouldForwardToFinalAgent = canonical.audit.shouldForwardToFinalAgent,
                errorText = canonical.errorText,
                detailsText = canonical.content.details,
                actions = actions,
                canonicalReply = canonical
            )
        }

        val needsAttention = normalizedRaw.startsWith(uncertainPrefix)
        val cleanedReply = normalizedRaw.removePrefix(uncertainPrefix).trim()
        val internalEvent = OpenClawInternalEventClassifier.detect(cleanedReply)
        val canonical = AgentReplyEnvelope(
            content = AgentMessageContent(
                text = cleanedReply.ifBlank { normalizedRaw },
                speech = if (internalEvent == null) cleanedReply.ifBlank { normalizedRaw } else ""
            ),
            needsAttention = needsAttention,
            shouldSpeak = !needsAttention && internalEvent == null,
            tags = buildList {
                if (needsAttention) add("uncertain_target")
                if (internalEvent != null) add("internal_event")
            },
            audit = AgentAuditDecision(),
            internalEvent = internalEvent?.canonicalType
        )
        return OpenClawReplyEnvelope(
            rawText = normalizedRaw,
            replyText = internalEvent?.systemMessage ?: cleanedReply.ifBlank { normalizedRaw },
            spokenReplyText = if (internalEvent == null) cleanedReply.ifBlank { normalizedRaw } else "",
            needsAttention = needsAttention,
            shouldSpeak = !needsAttention && internalEvent == null,
            speakBlockReason = null,
            isSystemInfo = internalEvent != null,
            internalEvent = internalEvent,
            tags = buildList {
                if (needsAttention) add("uncertain_target")
                if (internalEvent != null) add("internal_context_compaction")
            },
            confidence = null,
            overlap = false,
            settingsPatch = null,
            canonicalReply = canonical
        )
    }

    private fun parseJsonObject(value: String): JSONObject? {
        if (!value.startsWith("{") || !value.endsWith("}")) {
            return null
        }
        return runCatching { JSONObject(value) }.getOrNull()
    }

    private fun JSONObject.booleanOrNull(field: String): Boolean? {
        if (!has(field)) {
            return null
        }
        return when (val value = opt(field)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> null
        }
    }

}
