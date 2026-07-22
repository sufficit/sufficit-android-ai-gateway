package com.sufficit.ai.gateway.history

import android.content.Context
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatAudioState
import com.sufficit.ai.gateway.runtime.ChatDeliveryState
import com.sufficit.ai.gateway.runtime.ChatRole
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
                            text = text,
                            atEpochMs = o.optLong("atEpochMs"),
                            details = o.optString("details").takeIf { it.isNotBlank() },
                            imagePath = imagePath,
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
                            deliverySourceTexts = deliverySourceTexts
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
        }.getOrDefault(emptyList())
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
