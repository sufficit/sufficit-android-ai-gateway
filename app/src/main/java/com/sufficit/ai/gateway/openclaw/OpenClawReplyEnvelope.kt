package com.sufficit.ai.gateway.openclaw

import org.json.JSONObject

data class OpenClawReplyEnvelope(
    val rawText: String,
    val replyText: String,
    val spokenReplyText: String,
    val needsAttention: Boolean,
    val shouldSpeak: Boolean,
    val speakBlockReason: String?,
    val isSystemInfo: Boolean,
    val tags: List<String>,
    val confidence: Double?,
    val overlap: Boolean,
    val settingsPatch: JSONObject?,
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
    val actions: List<JSONObject> = emptyList()
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
            val tags = parsedJson.optJSONArray("tags")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val value = array.optString(index).trim()
                            if (value.isNotBlank()) {
                                add(value)
                            }
                        }
                    }
                }
                .orEmpty()
            val replyText = parsedJson.optString("replyText").trim()
                .ifBlank { parsedJson.optString("text").trim() }
                .ifBlank { parsedJson.optString("message").trim() }
            val spokenReplyText = parsedJson.optString("spokenReplyText").trim()
                .ifBlank { parsedJson.optString("voiceReplyText").trim() }
                .ifBlank { replyText }
            val needsAttention =
                parsedJson.booleanOrNull("needsAttention")
                    ?: parsedJson.booleanOrNull("attention")
                    ?: parsedJson.booleanOrNull("attentionRequired")
                    ?: tags.any {
                        it.equals("uncertain_target", ignoreCase = true) ||
                            it.equals("needs_attention", ignoreCase = true)
                    }
            val shouldSpeak =
                parsedJson.booleanOrNull("shouldSpeak")
                    ?: parsedJson.booleanOrNull("speak")
                    ?: (!needsAttention && tags.none {
                        it.equals("silent", ignoreCase = true) ||
                            it.equals("do_not_speak", ignoreCase = true)
                    })
            val speakBlockReason = parsedJson.optString("speakBlockReason").trim().ifBlank { null }
            val isSystemInfo = parsedJson.booleanOrNull("isSystemInfo") ?: false
            val overlap =
                parsedJson.booleanOrNull("overlap")
                    ?: tags.any {
                        it.equals("overlap_suspected", ignoreCase = true) ||
                            it.equals("overlap_confirmed", ignoreCase = true)
                    }
            val confidence = parsedJson.numberOrNull("confidence")
            val settingsPatch =
                parsedJson.optJSONObject("settingsPatch")
                    ?: parsedJson.optJSONObject("settings")
                    ?: parsedJson.optJSONObject("androidSettings")
            val errorText = parsedJson.optString("error").trim()
                .ifBlank { parsedJson.optString("errorText").trim() }
                .ifBlank { null }
            val detailsText = parsedJson.optString("details").trim()
                .ifBlank { parsedJson.optString("visualNote").trim() }
                .ifBlank { null }
            val actions = (parsedJson.optJSONArray("actions") ?: parsedJson.optJSONArray("tools"))
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            // Aceita objeto {tool,...} ou string "wake" (atalho sem args).
                            when (val item = array.opt(index)) {
                                is JSONObject -> add(item)
                                is String -> if (item.isNotBlank()) add(JSONObject().put("tool", item.trim()))
                            }
                        }
                    }
                }
                .orEmpty()
            val rawEnvelopeText = when {
                source.equals("android-pre-agent", ignoreCase = true) -> normalizedRaw
                replyText.isNotBlank() -> normalizedRaw
                else -> normalizedRaw
            }
            return OpenClawReplyEnvelope(
                rawText = rawEnvelopeText,
                replyText = replyText,
                spokenReplyText = spokenReplyText,
                needsAttention = needsAttention,
                shouldSpeak = shouldSpeak,
                speakBlockReason = speakBlockReason,
                isSystemInfo = isSystemInfo,
                tags = tags,
                confidence = confidence,
                overlap = overlap,
                settingsPatch = settingsPatch,
                errorText = errorText,
                detailsText = detailsText,
                actions = actions
            )
        }

        val needsAttention = normalizedRaw.startsWith(uncertainPrefix)
        val cleanedReply = normalizedRaw.removePrefix(uncertainPrefix).trim()
        return OpenClawReplyEnvelope(
            rawText = normalizedRaw,
            replyText = cleanedReply.ifBlank { normalizedRaw },
            spokenReplyText = cleanedReply.ifBlank { normalizedRaw },
            needsAttention = needsAttention,
            shouldSpeak = !needsAttention,
            speakBlockReason = null,
            isSystemInfo = false,
            tags = if (needsAttention) listOf("uncertain_target") else emptyList(),
            confidence = null,
            overlap = false,
            settingsPatch = null
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

    private fun JSONObject.numberOrNull(field: String): Double? {
        if (!has(field)) {
            return null
        }
        return when (val value = opt(field)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
