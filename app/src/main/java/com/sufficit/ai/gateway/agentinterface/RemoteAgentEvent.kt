package com.sufficit.ai.gateway.agentinterface

sealed interface RemoteAgentEvent {
    data class Connection(
        val connected: Boolean,
        val reason: String? = null
    ) : RemoteAgentEvent

    data class Activity(
        val turnId: String?,
        val state: AgentActivityState,
        val statusText: String? = null
    ) : RemoteAgentEvent

    data class Reply(val envelope: AgentReplyEnvelope) : RemoteAgentEvent

    data class ActionRequested(
        val turnId: String?,
        val actions: List<AgentClientActionRequest>
    ) : RemoteAgentEvent

    data class Internal(
        val type: AgentInternalEventType,
        val technicalSummary: String? = null
    ) : RemoteAgentEvent

    data class Failure(
        val turnId: String?,
        val code: String,
        val message: String,
        val retryable: Boolean
    ) : RemoteAgentEvent
}

object RemoteAgentEventClassifier {
    fun detectInternalEvent(
        explicitType: String? = null,
        vararg textValues: String?
    ): AgentInternalEventType? {
        AgentInternalEventType.fromWireValue(explicitType.orEmpty())?.let { return it }
        return when {
            textValues.any(::isContextCompaction) -> AgentInternalEventType.CONTEXT_COMPACTION
            textValues.any(::isMemoryInternal) -> AgentInternalEventType.MEMORY_INTERNAL
            textValues.any(::isMaintenance) -> AgentInternalEventType.MAINTENANCE
            else -> null
        }
    }

    private fun isContextCompaction(value: String?): Boolean =
        everyMeaningfulLineStartsWith(value, CONTEXT_MAINTENANCE_PREFIXES)

    private fun isMemoryInternal(value: String?): Boolean =
        everyMeaningfulLineStartsWith(value, MEMORY_INTERNAL_PREFIXES)

    private fun isMaintenance(value: String?): Boolean =
        everyMeaningfulLineStartsWith(value, MAINTENANCE_PREFIXES)

    private fun everyMeaningfulLineStartsWith(value: String?, prefixes: List<String>): Boolean {
        val lines = value.orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return false
        return lines.all { line ->
            val normalized = line
                .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
                .trim()
                .lowercase()
            prefixes.any(normalized::startsWith)
        }
    }

    private val CONTEXT_MAINTENANCE_PREFIXES = listOf(
        "compacting context",
        "context compacted",
        "summarizing context",
        "context summarized",
        "compactando contexto",
        "compactando o contexto",
        "contexto compactado",
        "resumindo contexto",
        "resumindo o contexto",
        "contexto resumido"
    )

    private val MEMORY_INTERNAL_PREFIXES = listOf(
        "memory sync",
        "memory updated internally",
        "sincronizando memoria interna",
        "memoria interna atualizada"
    )

    private val MAINTENANCE_PREFIXES = listOf(
        "internal maintenance",
        "maintenance completed",
        "manutencao interna",
        "manutencao concluida"
    )
}

object InternalEventFilter {
    fun isUserVisible(event: RemoteAgentEvent): Boolean = event !is RemoteAgentEvent.Internal

    fun isSpeakable(event: RemoteAgentEvent): Boolean =
        event is RemoteAgentEvent.Reply &&
            event.envelope.internalEvent == null &&
            event.envelope.errorText.isNullOrBlank() &&
            MobilePresentationPolicy.present(event.envelope).speech.isNotBlank()
}

