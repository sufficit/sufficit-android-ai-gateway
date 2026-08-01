package com.sufficit.ai.gateway.history

import android.content.Context
import com.sufficit.ai.gateway.runtime.ChatAgentActivityState
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatAudioState
import com.sufficit.ai.gateway.runtime.ChatAttachment
import com.sufficit.ai.gateway.runtime.ChatDeliveryState
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.openclaw.OpenClawInternalEventClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Persiste o historico de conversa em filesDir/chat_history.json para que NAO
 * se perca a cada reinicio/atualizacao do app (um `install -r` preserva o
 * filesDir). Gravacao serializada num unico executor de I/O para nao bloquear
 * as threads de audio/UI que disparam o append.
 */
class ChatHistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val io = Executors.newSingleThreadExecutor()

    /** Carrega o historico salvo (vazio se nao houver/erro). Sincrono — chamado no onCreate. */
    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val text = o.optString("text")
                    val imagePath = o.optString("imagePath").takeIf { it.isNotBlank() }
                    val attachments = o.optJSONArray("attachments")?.let { values ->
                        buildList {
                            for (index in 0 until values.length()) {
                                val attachment = values.optJSONObject(index) ?: continue
                                val kind = attachment.optString("kind").trim()
                                val name = attachment.optString("name").trim()
                                val uri = attachment.optString("uri").trim()
                                if (kind.isBlank() || name.isBlank() || uri.isBlank()) continue
                                add(
                                    ChatAttachment(
                                        kind = kind,
                                        name = name,
                                        uri = uri,
                                        mimeType = attachment.optString("mimeType").trim()
                                            .takeIf { it.isNotBlank() },
                                        sizeBytes = attachment.optLong("sizeBytes").takeIf { it > 0L }
                                    )
                                )
                            }
                        }
                    }.orEmpty()
                    // Mantem a referencia mesmo se o arquivo sumiu: o card de
                    // midia mostra um placeholder "midia indisponivel" em vez de
                    // descartar silenciosamente para texto.
                    val audioPath = o.optString("audioPath").takeIf { it.isNotBlank() }
                    val audioExpiresAt = o.optLong("audioExpiresAtEpochMs").takeIf { it > 0L }
                    val audioStillAvailable = audioPath != null && audioExpiresAt != null &&
                        audioExpiresAt > System.currentTimeMillis() && File(audioPath).isFile
                    if (audioPath != null && !audioStillAvailable) File(audioPath).delete()
                    val storedAudioState = o.optString("audioState").takeIf { it.isNotBlank() }
                        ?.let { runCatching { ChatAudioState.valueOf(it) }.getOrNull() }
                    val restoredAudioState = if (storedAudioState == ChatAudioState.TRANSCRIBING) {
                        ChatAudioState.ERROR
                    } else {
                        storedAudioState
                    }
                    val storedDeliveryState = o.optString("deliveryState").takeIf { it.isNotBlank() }
                        ?.let { runCatching { ChatDeliveryState.valueOf(it) }.getOrNull() }
                    val storedAgentActivityState = o.optString("agentActivityState")
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { ChatAgentActivityState.valueOf(it) }.getOrNull() }
                    val restoredAgentActivityState = when (storedAgentActivityState) {
                        null, ChatAgentActivityState.FAILED -> storedAgentActivityState
                        else -> ChatAgentActivityState.FAILED
                    }
                    val deliveryTags = o.optJSONArray("deliveryTags")?.let { tags ->
                        buildList {
                            for (index in 0 until tags.length()) {
                                tags.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }.orEmpty()
                    val deliverySourceMessageIds = o.optJSONArray("deliverySourceMessageIds")?.let { ids ->
                        buildList {
                            for (index in 0 until ids.length()) {
                                ids.optLong(index).takeIf { it > 0L }?.let(::add)
                            }
                        }
                    }.orEmpty()
                    val deliverySourceTexts = o.optJSONArray("deliverySourceTexts")?.let { texts ->
                        buildList {
                            for (index in 0 until texts.length()) {
                                texts.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }.orEmpty()
                    add(
                        ChatMessage(
                            id = o.optLong("id"),
                            role = parseRole(o.optString("role")),
                            text = if (
                                storedAgentActivityState != null &&
                                storedAgentActivityState != ChatAgentActivityState.FAILED
                            ) {
                                "Não consegui concluir este pedido. " +
                                    "O aplicativo foi reiniciado durante o processamento."
                            } else {
                                text
                            },
                            atEpochMs = o.optLong("atEpochMs"),
                            details = o.optString("details").takeIf { it.isNotBlank() },
                            imagePath = imagePath,
                            attachments = attachments,
                            audioPath = audioPath?.takeIf { audioStillAvailable },
                            audioDurationMs = o.optLong("audioDurationMs").takeIf { it > 0L },
                            audioExpiresAtEpochMs = audioExpiresAt?.takeIf { audioStillAvailable },
                            audioState = restoredAudioState,
                            audioError = if (storedAudioState == ChatAudioState.TRANSCRIBING) {
                                "Transcrição interrompida pelo reinício do aplicativo"
                            } else {
                                o.optString("audioError").takeIf { it.isNotBlank() }
                            },
                            deliveryState = if (storedAudioState == ChatAudioState.TRANSCRIBING) {
                                ChatDeliveryState.FAILED
                            } else {
                                storedDeliveryState
                            },
                            deliveryReason = if (storedAudioState == ChatAudioState.TRANSCRIBING) {
                                "transcription_interrupted"
                            } else {
                                o.optString("deliveryReason").takeIf { it.isNotBlank() }
                            },
                            deliveryTags = deliveryTags,
                            deliveryUpdatedAtEpochMs = o.optLong("deliveryUpdatedAtEpochMs")
                                .takeIf { it > 0L },
                            deliverySourceMessageIds = deliverySourceMessageIds,
                            deliverySourceTexts = deliverySourceTexts,
                            agentActivityState = restoredAgentActivityState,
                            agentActivityUpdatedAtEpochMs = if (
                                restoredAgentActivityState != storedAgentActivityState
                            ) {
                                System.currentTimeMillis()
                            } else {
                                o.optLong("agentActivityUpdatedAtEpochMs").takeIf { it > 0L }
                            }
                        )
                    )
                }
            }.fold(mutableListOf<ChatMessage>()) { restored, message ->
                // Corrige historicos gravados pela versao que registrava a
                // mesma fala duas vezes: primeiro na bolha de audio e depois
                // como texto puro ao despachar ao OpenClaw. A consolidacao
                // podia inserir mensagens intermediarias entre as duas, entao
                // procura a bolha de audio recente que ja cobre o texto.
                val legacyDuplicate = message.role == ChatRole.USER &&
                    message.audioPath == null && restored.asReversed().any { previous ->
                        previous.role == ChatRole.USER &&
                            previous.audioPath != null &&
                            message.atEpochMs - previous.atEpochMs in 0..90_000L &&
                            previous.text.trim().let { existing ->
                                existing.isNotBlank() &&
                                    (message.text.startsWith(existing) || existing.startsWith(message.text))
                            }
                    }
                if (!legacyDuplicate) restored += message
                restored
            }.let(::migrateLegacyDeliveryAudits)
                .let(::migrateLegacyInternalEvents)
                .let(::closeTrailingOrphanedUserTurn)
        }.getOrDefault(emptyList())
    }

    /**
     * Versões anteriores podiam encerrar o processo entre a transcrição e a
     * criação do provisório. Repara somente o bloco final órfão: nunca altera
     * turnos antigos que já possuem resposta/auditoria depois deles.
     */
    private fun closeTrailingOrphanedUserTurn(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.lastOrNull()?.role != ChatRole.USER) return messages
        val trailingUsers = messages.takeLastWhile { it.role == ChatRole.USER }
        val actionableSources = trailingUsers.filter { message ->
            message.text.isNotBlank() && message.audioState != ChatAudioState.ERROR
        }
        if (actionableSources.isEmpty()) return messages

        val now = System.currentTimeMillis()
        return messages + ChatMessage(
            id = (messages.maxOfOrNull { it.id } ?: 0L) + 1L,
            role = ChatRole.ASSISTANT,
            text = "Não consegui concluir este pedido. " +
                "O processamento anterior terminou sem registrar resposta ou falha.",
            atEpochMs = now,
            deliverySourceMessageIds = actionableSources.map { it.id },
            deliverySourceTexts = actionableSources.map { it.text.trim() },
            agentActivityState = ChatAgentActivityState.FAILED,
            agentActivityUpdatedAtEpochMs = now
        )
    }

    /**
     * As versões anteriores registravam a justificativa como uma bolha solta
     * e depois copiavam a decisão para cada áudio. Converte o próprio marcador
     * do assistente em auditoria do lote e devolve às bolhas-fonte apenas o
     * estado de entrega, sem inventar telemetria que não existia.
     */
    private fun migrateLegacyDeliveryAudits(messages: List<ChatMessage>): List<ChatMessage> {
        val restored = messages.toMutableList()
        for (index in restored.indices) {
            if (restored[index].deliverySourceTexts.isNotEmpty()) continue
            val marker = legacyDeliveryMarker(restored[index].text) ?: continue
            var candidateIndex = index - 1
            val sourceIndexes = mutableListOf<Int>()
            while (candidateIndex >= 0) {
                val candidate = restored[candidateIndex]
                if (candidate.role != ChatRole.USER) break
                val elapsed = restored[index].atEpochMs - candidate.atEpochMs
                if (elapsed !in 0..90_000L) break
                sourceIndexes += candidateIndex
                candidateIndex--
            }
            if (sourceIndexes.isEmpty()) continue

            val sources = sourceIndexes.asReversed().map { restored[it] }
            val markerMessage = restored[index]
            restored[index] = markerMessage.copy(
                deliveryState = marker.first,
                deliveryReason = marker.second,
                deliveryUpdatedAtEpochMs = markerMessage.atEpochMs,
                deliverySourceMessageIds = sources.map { it.id },
                deliverySourceTexts = sources.map { it.text.trim() }.filter { it.isNotBlank() }
            )
            sourceIndexes.forEach { sourceIndex ->
                val source = restored[sourceIndex]
                restored[sourceIndex] = source.copy(
                    deliveryState = ChatDeliveryState.SENT_TO_AGENT,
                    deliveryReason = "sent_to_openclaw",
                    deliveryTags = emptyList(),
                    deliveryUpdatedAtEpochMs = markerMessage.atEpochMs
                )
            }
        }
        return restored
    }

    /**
     * A primeira versao do canal podia receber a telemetria de compactacao do
     * OpenClaw como se fosse uma resposta. Reclassifica registros ja gravados
     * para um marcador de sistema discreto e remove o WAV TTS correspondente,
     * para que esse texto nunca volte a ser reproduzido pelo botao de audio.
     */
    private fun migrateLegacyInternalEvents(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.map { message ->
            val event = OpenClawInternalEventClassifier.detect(message.text)
            if (message.role != ChatRole.ASSISTANT || event == null) {
                message
            } else {
                message.audioPath?.let(::deleteLegacyAssistantAudioIfOwned)
                message.copy(
                    role = ChatRole.SYSTEM,
                    text = event.systemMessage,
                    details = null,
                    attachments = emptyList(),
                    audioPath = null,
                    audioDurationMs = null,
                    audioExpiresAtEpochMs = null,
                    audioState = null,
                    audioError = null
                )
            }
        }
    }

    private fun deleteLegacyAssistantAudioIfOwned(path: String) {
        val audio = File(path)
        val expectedDirectory = File(file.parentFile, "assistant-reply-audio")
        val belongsToAssistant = runCatching {
            audio.canonicalFile.parentFile == expectedDirectory.canonicalFile
        }.getOrDefault(false)
        if (belongsToAssistant) {
            runCatching { audio.delete() }
        }
    }

    private fun legacyDeliveryMarker(text: String): Pair<ChatDeliveryState, String>? {
        val normalized = text.trim().lowercase()
        return when {
            normalized.contains("sem chamada ao assistente") ->
                ChatDeliveryState.IGNORED to "ambient_not_directed_to_agent"
            normalized.contains("conversa ambiente") ->
                ChatDeliveryState.IGNORED to "ambient_conversation"
            normalized.contains("divergencia de voz") ->
                ChatDeliveryState.IGNORED to "different_speaker"
            normalized.contains("sobreposicao de vozes") ->
                ChatDeliveryState.HELD_FOR_REVIEW to "multi_voice_overlap"
            normalized.contains("aguardando confirmacao de contexto") ->
                ChatDeliveryState.HELD_FOR_REVIEW to "wake_confirmation_required"
            normalized.contains("trecho neutro") ->
                ChatDeliveryState.IGNORED to "neutral_marker_only"
            normalized.contains("trecho vazio") ->
                ChatDeliveryState.IGNORED to "empty_transcript"
            else -> null
        }
    }

    /** Grava o historico (assincrono, serializado). */
    fun save(messages: List<ChatMessage>) {
        val snapshot = messages.toList()
        io.execute {
            runCatching {
                val array = JSONArray()
                for (m in snapshot) {
                    array.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("role", roleName(m.role))
                            .put("text", m.text)
                            .put("atEpochMs", m.atEpochMs)
                            .apply {
                                m.details?.let { put("details", it) }
                                m.imagePath?.let { put("imagePath", it) }
                                if (m.attachments.isNotEmpty()) {
                                    put("attachments", JSONArray().apply {
                                        m.attachments.forEach { attachment ->
                                            put(
                                                JSONObject()
                                                    .put("kind", attachment.kind)
                                                    .put("name", attachment.name)
                                                    .put("uri", attachment.uri)
                                                    .put("mimeType", attachment.mimeType)
                                                    .put("sizeBytes", attachment.sizeBytes)
                                            )
                                        }
                                    })
                                }
                                val audioAvailable = m.audioPath != null &&
                                    (m.audioExpiresAtEpochMs ?: 0L) > System.currentTimeMillis() &&
                                    File(m.audioPath).isFile
                                if (audioAvailable) {
                                    put("audioPath", m.audioPath)
                                    m.audioDurationMs?.let { put("audioDurationMs", it) }
                                    m.audioExpiresAtEpochMs?.let { put("audioExpiresAtEpochMs", it) }
                                }
                                m.audioState?.let { put("audioState", it.name) }
                                m.audioError?.let { put("audioError", it) }
                                m.deliveryState?.let { put("deliveryState", it.name) }
                                m.deliveryReason?.let { put("deliveryReason", it) }
                                if (m.deliveryTags.isNotEmpty()) {
                                    put("deliveryTags", JSONArray(m.deliveryTags))
                                }
                                m.deliveryUpdatedAtEpochMs?.let { put("deliveryUpdatedAtEpochMs", it) }
                                if (m.deliverySourceMessageIds.isNotEmpty()) {
                                    put("deliverySourceMessageIds", JSONArray(m.deliverySourceMessageIds))
                                }
                                if (m.deliverySourceTexts.isNotEmpty()) {
                                    put("deliverySourceTexts", JSONArray(m.deliverySourceTexts))
                                }
                                m.agentActivityState?.let { put("agentActivityState", it.name) }
                                m.agentActivityUpdatedAtEpochMs?.let {
                                    put("agentActivityUpdatedAtEpochMs", it)
                                }
                            }
                    )
                }
                file.writeText(array.toString())
            }
        }
    }

    private fun parseRole(value: String): ChatRole = when (value.uppercase()) {
        "USER" -> ChatRole.USER
        "SYSTEM" -> ChatRole.SYSTEM
        else -> ChatRole.ASSISTANT
    }

    private fun roleName(role: ChatRole): String = when (role) {
        ChatRole.USER -> "USER"
        ChatRole.SYSTEM -> "SYSTEM"
        ChatRole.ASSISTANT -> "ASSISTANT"
    }

    companion object {
        private const val FILE_NAME = "chat_history.json"
    }
}
