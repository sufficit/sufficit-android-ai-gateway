package com.sufficit.ai.gateway.agentinterface

data class MobilePresentation(
    val text: String,
    val speech: String,
    val details: String?,
    val attachments: List<AgentAttachment>,
    val shouldRenderAsReply: Boolean,
    val speakBlockReason: String? = null
)

object MobilePresentationPolicy {
    fun present(reply: AgentReplyEnvelope): MobilePresentation {
        val isInternal = reply.internalEvent != null
        val hasError = !reply.errorText.isNullOrBlank()
        val normalizedText = reply.content.text.trim()
        val normalizedSpeech = reply.content.speech.trim()
        val blockReason = when {
            isInternal -> "internal_event"
            hasError -> "remote_error"
            !reply.shouldSpeak -> reply.speakBlockReason ?: "agent_requested_silence"
            normalizedSpeech.isBlank() -> "empty_speech"
            else -> null
        }
        return MobilePresentation(
            text = if (isInternal) "" else normalizedText,
            speech = if (blockReason == null) normalizedSpeech else "",
            details = if (isInternal) null else reply.content.details?.trim()?.takeIf(String::isNotBlank),
            attachments = if (isInternal) emptyList() else reply.content.attachments,
            shouldRenderAsReply = !isInternal && !hasError &&
                (normalizedText.isNotBlank() || reply.content.attachments.isNotEmpty()),
            speakBlockReason = blockReason
        )
    }
}

