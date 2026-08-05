package com.sufficit.ai.gateway.agentinterface

import org.json.JSONArray
import org.json.JSONObject

object AgentProtocolCodec {
    fun encodeTurn(turn: AgentTurnEnvelope): JSONObject = JSONObject()
        .put("schemaVersion", turn.schemaVersion)
        .put("type", "interaction.turn")
        .put("turnId", turn.turnId)
        .put("text", turn.text)
        .put("interaction", encodeInteraction(turn.interaction))
        .put("presentation", encodePresentation(turn.presentation))
        .put("availableTools", JSONArray().apply {
            turn.availableTools.forEach { put(JSONObject(it.toString())) }
        })
        .put("metadata", JSONObject(turn.metadata.toString()))

    fun decodeReply(value: JSONObject): AgentReplyEnvelope {
        val schemaVersion = value.optInt("schemaVersion", AgentProtocolVersion.CURRENT)
        require(schemaVersion in 1..AgentProtocolVersion.CURRENT) {
            "Versao de protocolo nao suportada: $schemaVersion."
        }
        val message = value.optJSONObject("message") ?: value
        val text = message.firstText("text", "replyText", "message")
        val speech = message.firstText("speech", "spokenReplyText", "voiceReplyText")
            .ifBlank { text }
        val details = message.firstText("details", "visualNote").ifBlank { null }
        val explicitInternalType = value.firstText("internalType", "eventType")
        val internalEvent = RemoteAgentEventClassifier.detectInternalEvent(
            explicitInternalType,
            text,
            speech
        )
        val tags = value.optJSONArray("tags").stringValues()
        val needsAttention = value.booleanOrNull("needsAttention")
            ?: tags.any { it == "uncertain_target" || it == "needs_attention" }
        val requestedSpeech = value.booleanOrNull("shouldSpeak")
            ?: tags.none { it == "silent" || it == "do_not_speak" }
        val errorText = value.firstText("error", "errorText").ifBlank { null }
        val activity = value.optJSONObject("activity")
            ?.optString("state")
            ?.let(AgentActivityState::fromWireValue)
        val action = value.firstText("action").ifBlank { null }
        val reason = value.firstText("reason").ifBlank { null }
        return AgentReplyEnvelope(
            turnId = value.optString("turnId").trim().ifBlank { null },
            content = AgentMessageContent(
                text = text,
                speech = if (internalEvent == null) speech else "",
                details = if (internalEvent == null) details else null,
                attachments = decodeAttachments(message.optJSONArray("attachments"))
            ),
            actions = decodeActions(value.optJSONArray("actions") ?: value.optJSONArray("tools")),
            activityState = activity,
            needsAttention = needsAttention,
            shouldSpeak = requestedSpeech && internalEvent == null && errorText == null,
            speakBlockReason = value.optString("speakBlockReason").trim().ifBlank { null },
            tags = buildList {
                addAll(tags)
                if (internalEvent != null && "internal_event" !in tags) add("internal_event")
            },
            confidence = value.numberOrNull("confidence"),
            overlap = value.booleanOrNull("overlap")
                ?: tags.any { it == "overlap_suspected" || it == "overlap_confirmed" },
            audit = AgentAuditDecision(
                transcript = value.optString("transcript").trim().ifBlank { null },
                action = action,
                reason = reason,
                shouldForwardToFinalAgent = value.booleanOrNull("shouldForwardToFinalAgent")
            ),
            errorText = errorText,
            internalEvent = internalEvent,
            isSystemInfo = (value.booleanOrNull("isSystemInfo") ?: false) || internalEvent != null,
            settingsPatch = value.optJSONObject("settingsPatch")
                ?: value.optJSONObject("settings")
                ?: value.optJSONObject("androidSettings"),
            schemaVersion = schemaVersion
        )
    }

    private fun encodeInteraction(value: AgentChannelContext): JSONObject = JSONObject()
        .put("inputMode", value.inputMode.wireValue)
        .put("surface", value.surface)
        .put("voiceReplyAvailable", value.voiceReplyAvailable)
        .put("awakened", value.awakened)
        .put("wakeWord", value.wakeWord?.takeIf { value.awakened } ?: JSONObject.NULL)
        .put("multipleVoicesLikely", value.multipleVoicesLikely)
        .put("detectedSpeakerCount", value.detectedSpeakerCount ?: JSONObject.NULL)
        .put("nonVerbalAudioEvents", JSONArray(value.nonVerbalAudioEvents))
        .put("transcriptionAnalysisSources", JSONArray(value.transcriptionAnalysisSources))
        .put("transcriptionAvailableSignals", JSONArray(value.transcriptionAvailableSignals))
        .put("transcriptionReliabilityScore", value.transcriptionReliabilityScore ?: JSONObject.NULL)
        .put("transcriptionNoiseScore", value.transcriptionNoiseScore ?: JSONObject.NULL)
        .put("transcriptionLanguageCode", value.transcriptionLanguageCode ?: JSONObject.NULL)
        .put("transcriptionLanguageProbability", value.transcriptionLanguageProbability ?: JSONObject.NULL)

    private fun encodePresentation(value: AgentPresentationHints): JSONObject = JSONObject()
        .put("preferConcise", value.preferConcise)
        .put("preferSpeakable", value.preferSpeakable)
        .put("preferredTextChars", value.preferredTextChars)
        .put("preferredSpeechSeconds", value.preferredSpeechSeconds)
        .put("supportsDetails", value.supportsDetails)
        .put("supportsAttachments", value.supportsAttachments)
        .put("supportsClientActions", value.supportsClientActions)

    private fun decodeAttachments(array: JSONArray?): List<AgentAttachment> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val kind = item.optString("kind").trim()
            val name = item.optString("name").trim()
            val uri = item.optString("uri").trim()
            if (kind.isBlank() || name.isBlank() || uri.isBlank()) continue
            add(
                AgentAttachment(
                    kind = kind,
                    name = name,
                    uri = uri,
                    mimeType = item.optString("mimeType").trim().ifBlank { null },
                    sizeBytes = item.optLong("sizeBytes").takeIf { it > 0L }
                )
            )
        }
    }

    private fun decodeActions(array: JSONArray?): List<AgentClientActionRequest> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> {
                    val tool = item.firstText("tool", "name", "type")
                    if (tool.isBlank()) continue
                    val arguments = item.optJSONObject("arguments") ?: JSONObject(item.toString()).apply {
                        remove("tool")
                        remove("name")
                        remove("type")
                        remove("callId")
                        remove("call_id")
                    }
                    add(
                        AgentClientActionRequest(
                            tool = tool,
                            callId = item.firstText("callId", "call_id").ifBlank { null },
                            arguments = arguments,
                            raw = JSONObject(item.toString())
                        )
                    )
                }
                is String -> if (item.isNotBlank()) add(AgentClientActionRequest(tool = item.trim()))
            }
        }
    }

    private fun JSONObject.firstText(vararg fields: String): String {
        fields.forEach { field ->
            optString(field).trim().takeIf(String::isNotBlank)?.let { return it }
        }
        return ""
    }

    private fun JSONArray?.stringValues(): List<String> = buildList {
        if (this@stringValues == null) return@buildList
        for (index in 0 until length()) {
            optString(index).trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.booleanOrNull(field: String): Boolean? {
        if (!has(field)) return null
        return when (val value = opt(field)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    private fun JSONObject.numberOrNull(field: String): Double? {
        if (!has(field)) return null
        return when (val value = opt(field)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
