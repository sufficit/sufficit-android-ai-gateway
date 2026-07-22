package com.sufficit.ai.gateway.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Papel de uma mensagem no historico de conversa do dashboard. */
enum class ChatRole { USER, ASSISTANT, SYSTEM }

/** Etapa visível de uma fala capturada no histórico. */
enum class ChatAudioState { TRANSCRIBING, TRANSCRIBED, SENDING, ERROR }

/**
 * Resultado auditável do trecho depois que ele deixa o dispositivo.
 *
 * [ChatAudioState] descreve o WAV/STT; este estado descreve a decisão do
 * OpenClaw. Os dois são mantidos separados para que ✓✓ continue significando
 * "entregue ao sistema", mesmo se o sistema decidir ignorar a fala ambiente.
 */
enum class ChatDeliveryState {
    TRANSCRIBING,
    TRANSCRIBED,
    SENT_TO_AGENT,
    IGNORED,
    HELD_FOR_REVIEW,
    AGENT_REPLIED,
    ACTION_EXECUTED,
    NO_AGENT_REPLY,
    FAILED
}

/**
 * Trecho fechado e enviado para transcricao. E efemero: existe somente ate o
 * resultado chegar (ou falhar), mas contem o WAV exato para o usuario poder
 * conferir o que foi selecionado enquanto aguarda.
 */
data class PendingAudioCapture(
    val id: Long,
    val wavPath: String,
    val durationMs: Long,
    val waveform: List<Float>,
    val backendLabel: String
)

/** Mensagem do historico de conversa (estilo WhatsApp/Telegram). */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val atEpochMs: Long,
    /**
     * Conteudo visual-apenas da resposta (enderecos, links, explicacoes):
     * exibido como painel expansivel na bolha, NUNCA falado. Null = sem painel.
     */
    val details: String? = null,
    /**
     * Caminho absoluto de uma imagem anexa (screenshot/foto da camera tirada
     * pelo agente). Exibida como thumbnail na bolha; toque abre a imagem real.
     * Null = mensagem so de texto.
     */
    val imagePath: String? = null,
    /** WAV do trecho que originou esta mensagem; expira para preservar privacidade. */
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val audioExpiresAtEpochMs: Long? = null,
    val audioState: ChatAudioState? = null,
    val audioError: String? = null,
    /** Decisão do gateway/agent persistida para auditoria visual no chat. */
    val deliveryState: ChatDeliveryState? = null,
    /** Código estável da decisão (ex.: ambient_not_directed_to_agent). */
    val deliveryReason: String? = null,
    /** Tags devolvidas pelo gateway, preservadas para inspeção sob demanda. */
    val deliveryTags: List<String> = emptyList(),
    val deliveryUpdatedAtEpochMs: Long? = null,
    /** IDs das bolhas de usuário que formaram o turno remoto auditado. */
    val deliverySourceMessageIds: List<Long> = emptyList(),
    /** Cópia textual dos trechos do turno, preservada mesmo após o WAV expirar. */
    val deliverySourceTexts: List<String> = emptyList()
)

/**
 * Historico de conversa exibido no dashboard (mais recente no FINAL da
 * lista), preservado para auditoria independente da expiração de áudio.
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewayChatRuntime {
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val pendingAudioFlow = MutableStateFlow<List<PendingAudioCapture>>(emptyList())
    private val chatMessageIdSeq = AtomicLong(0L)
    private val pendingAudioIdSeq = AtomicLong(0L)
    // O WAV expira após seis horas por privacidade, mas o texto e a decisão
    // precisam continuar consultáveis. Este teto evita crescimento sem limite
    // sem aplicar uma remoção silenciosa por tempo ao histórico.
    private const val CHAT_HISTORY_HARD_LIMIT = 5_000

    // Persistencia em disco do historico (sobrevive a reinicio/install -r).
    // Setada pelo servico no onCreate via attachChatPersistence.
    @Volatile
    private var chatPersister: ((List<ChatMessage>) -> Unit)? = null

    fun chatMessages(): StateFlow<List<ChatMessage>> = chatFlow.asStateFlow()

    fun pendingAudioCaptures(): StateFlow<List<PendingAudioCapture>> = pendingAudioFlow.asStateFlow()

    fun addPendingAudioCapture(
        wavPath: String,
        durationMs: Long,
        waveform: List<Float>,
        backendLabel: String
    ): Long {
        val id = pendingAudioIdSeq.incrementAndGet()
        pendingAudioFlow.value = pendingAudioFlow.value + PendingAudioCapture(
            id = id,
            wavPath = wavPath,
            durationMs = durationMs,
            waveform = waveform,
            backendLabel = backendLabel
        )
        return id
    }

    fun removePendingAudioCapture(id: Long) {
        pendingAudioFlow.value = pendingAudioFlow.value.filterNot { it.id == id }
    }

    /**
     * Liga a persistencia do chat: carrega o historico salvo para a memoria e
     * registra o gravador chamado a cada mudanca. Idempotente — chamar de novo
     * apenas recarrega do disco.
     */
    fun attachChatPersistence(initial: List<ChatMessage>, persister: (List<ChatMessage>) -> Unit) {
        if (initial.isNotEmpty()) {
            val trimmed = retainHistory(initial)
            chatFlow.value = trimmed
            chatMessageIdSeq.set(trimmed.maxOf { it.id })
        }
        chatPersister = persister
        // O carregador pode ter descartado duplicatas legadas. Grave o estado
        // normalizado de imediato para que elas nao retornem no proximo boot.
        persistChat()
    }

    private fun persistChat() {
        chatPersister?.invoke(chatFlow.value)
    }

    fun clearChat() {
        chatFlow.value = emptyList()
        persistChat()
    }

    fun appendChatMessage(
        role: ChatRole,
        text: String,
        details: String? = null,
        audioPath: String? = null,
        audioDurationMs: Long? = null,
        audioExpiresAtEpochMs: Long? = null
    ): Long {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return 0L
        val message = ChatMessage(
            id = chatMessageIdSeq.incrementAndGet(),
            role = role,
            text = trimmed,
            atEpochMs = System.currentTimeMillis(),
            details = details?.trim()?.takeIf { it.isNotBlank() },
            audioPath = audioPath?.trim()?.takeIf { it.isNotBlank() },
            audioDurationMs = audioDurationMs,
            audioExpiresAtEpochMs = audioExpiresAtEpochMs
        )
        chatFlow.value = retainHistory(chatFlow.value + message)
        persistChat()
        return message.id
    }

    fun attachChatMessageAudio(
        id: Long,
        audioPath: String,
        audioDurationMs: Long,
        audioExpiresAtEpochMs: Long
    ) {
        chatFlow.value = chatFlow.value.map { message ->
            if (message.id != id) message else message.copy(
                audioPath = audioPath,
                audioDurationMs = audioDurationMs,
                audioExpiresAtEpochMs = audioExpiresAtEpochMs
            )
        }
        persistChat()
    }

    fun hasRecentUserAudioCovering(text: String, windowMs: Long = 90_000L): Boolean {
        val target = text.trim()
        if (target.isBlank()) return false
        val now = System.currentTimeMillis()
        return chatFlow.value.asReversed().any { message ->
            message.role == ChatRole.USER &&
                message.audioPath != null &&
                now - message.atEpochMs in 0..windowMs &&
                message.text.trim().let { existing ->
                    existing.isNotBlank() && (target.startsWith(existing) || existing.startsWith(target))
                }
        }
    }

    /**
     * Recupera bolhas que ja foram transcritas mas perderam a fila efemera de
     * audio por uma recriacao do servico antes do despacho ao agente. O texto
     * do turno final e a associacao persistente: somente trechos que fazem
     * parte dele recebem o segundo tique.
     */
    fun markRecentTranscribedUserAudioAsSending(text: String, windowMs: Long = 90_000L): Boolean {
        val dispatchedText = normalizeDispatchText(text)
        if (dispatchedText.isBlank()) return false
        val now = System.currentTimeMillis()
        var changed = false
        val updated = chatFlow.value.map { message ->
            val messageText = normalizeDispatchText(message.text)
            val belongsToDispatchedTurn =
                messageText.isNotBlank() && dispatchContainsMessage(dispatchedText, messageText)
            if (
                message.role == ChatRole.USER &&
                    message.audioState == ChatAudioState.TRANSCRIBED &&
                    now - message.atEpochMs in 0..windowMs &&
                    belongsToDispatchedTurn
            ) {
                changed = true
                message.copy(
                    audioState = ChatAudioState.SENDING,
                    audioError = null,
                    deliveryState = ChatDeliveryState.SENT_TO_AGENT,
                    deliveryReason = "sent_to_openclaw",
                    deliveryTags = emptyList(),
                    deliveryUpdatedAtEpochMs = now
                )
            } else {
                message
            }
        }
        if (changed) {
            chatFlow.value = updated
            persistChat()
        }
        return changed
    }

    /**
     * Anexa a decisão recebida ao(s) cartão(ões) de áudio que compuseram o
     * turno enviado. A busca por texto cobre tanto um único WAV quanto uma
     * frase formada por vários segmentos consecutivos.
     */
    fun updateRecentUserDelivery(
        dispatchedText: String,
        state: ChatDeliveryState,
        reason: String? = null,
        tags: List<String> = emptyList(),
        windowMs: Long = 90_000L
    ): Boolean {
        val normalizedDispatch = normalizeDispatchText(dispatchedText)
        if (normalizedDispatch.isBlank()) return false
        val now = System.currentTimeMillis()
        var changed = false
        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val updated = chatFlow.value.map { message ->
            val messageText = normalizeDispatchText(message.text)
            val belongsToDispatchedTurn =
                message.role == ChatRole.USER &&
                    messageText.isNotBlank() &&
                    now - message.atEpochMs in 0..windowMs &&
                    dispatchContainsMessage(normalizedDispatch, messageText)
            if (!belongsToDispatchedTurn) {
                message
            } else {
                changed = true
                message.copy(
                    deliveryState = state,
                    deliveryReason = reason?.trim()?.takeIf { it.isNotBlank() },
                    deliveryTags = normalizedTags,
                    deliveryUpdatedAtEpochMs = now
                )
            }
        }
        if (changed) {
            chatFlow.value = updated
            persistChat()
        }
        return changed
    }

    /**
     * Registra uma única decisão do agente para o turno consolidado.
     *
     * As bolhas de áudio continuam representando somente cada captura e seus
     * tiques. A decisão remota vira uma nova bolha do assistente, ligada aos
     * IDs/textos que realmente compuseram o envio; assim ela não é repetida
     * dentro de cada segmento individual.
     */
    fun appendDeliveryAuditMessage(
        dispatchedText: String,
        state: ChatDeliveryState,
        reason: String? = null,
        tags: List<String> = emptyList(),
        decisionText: String,
        windowMs: Long = 10 * 60_000L
    ): Long {
        val normalizedDispatch = normalizeDispatchText(dispatchedText)
        if (normalizedDispatch.isBlank()) return 0L

        val now = System.currentTimeMillis()
        val messages = chatFlow.value
        val lastAssistantIndex = messages.indexOfLast { it.role == ChatRole.ASSISTANT }
        val currentTurnMatches = messages
            .drop(lastAssistantIndex + 1)
            .filter { message ->
                val messageText = normalizeDispatchText(message.text)
                message.role == ChatRole.USER &&
                    messageText.isNotBlank() &&
                    dispatchContainsMessage(normalizedDispatch, messageText)
            }
        val sourceMessages = currentTurnMatches.ifEmpty {
            messages.filter { message ->
                val messageText = normalizeDispatchText(message.text)
                message.role == ChatRole.USER &&
                    messageText.isNotBlank() &&
                    now - message.atEpochMs in 0..windowMs &&
                    dispatchContainsMessage(normalizedDispatch, messageText)
            }
        }
        val sourceIds = sourceMessages.map { it.id }.distinct()
        val sourceTexts = sourceMessages
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(dispatchedText.trim()) }
            .distinct()
        val normalizedReason = reason?.trim()?.takeIf { it.isNotBlank() }
        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val duplicate = messages.asReversed().take(12).firstOrNull { message ->
            message.role == ChatRole.ASSISTANT &&
                message.deliveryState == state &&
                message.deliveryReason == normalizedReason &&
                message.deliverySourceTexts.map(::normalizeDispatchText) ==
                    sourceTexts.map(::normalizeDispatchText)
        }
        if (duplicate != null) return duplicate.id

        val sourceIdSet = sourceIds.toSet()
        val normalizedSources = messages.map { message ->
            if (message.id !in sourceIdSet) {
                message
            } else {
                message.copy(
                    deliveryState = ChatDeliveryState.SENT_TO_AGENT,
                    deliveryReason = "sent_to_openclaw",
                    deliveryTags = emptyList(),
                    deliveryUpdatedAtEpochMs = now
                )
            }
        }
        val countLabel = if (sourceTexts.size == 1) {
            "Avaliei esta mensagem como um turno."
        } else {
            "Avaliei ${sourceTexts.size} mensagens em conjunto."
        }
        val message = ChatMessage(
            id = chatMessageIdSeq.incrementAndGet(),
            role = ChatRole.ASSISTANT,
            text = "$countLabel ${decisionText.trim()}".trim(),
            atEpochMs = now,
            deliveryState = state,
            deliveryReason = normalizedReason,
            deliveryTags = normalizedTags,
            deliveryUpdatedAtEpochMs = now,
            deliverySourceMessageIds = sourceIds,
            deliverySourceTexts = sourceTexts
        )
        chatFlow.value = retainHistory(normalizedSources + message)
        persistChat()
        return message.id
    }

    private fun normalizeDispatchText(text: String): String = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun dispatchContainsMessage(dispatchedText: String, messageText: String): Boolean =
        dispatchedText == messageText ||
            dispatchedText.startsWith("$messageText ") ||
            dispatchedText.endsWith(" $messageText") ||
            dispatchedText.contains(" $messageText ")

    /** Cria imediatamente a bolha do segmento; ela será atualizada no mesmo id. */
    fun appendChatAudioMessage(
        audioPath: String,
        audioDurationMs: Long,
        audioExpiresAtEpochMs: Long
    ): Long {
        val id = chatMessageIdSeq.incrementAndGet()
        val message = ChatMessage(
            id = id,
            role = ChatRole.USER,
            text = "",
            atEpochMs = System.currentTimeMillis(),
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            audioExpiresAtEpochMs = audioExpiresAtEpochMs,
            audioState = ChatAudioState.TRANSCRIBING,
            deliveryState = ChatDeliveryState.TRANSCRIBING,
            deliveryReason = "transcription_started",
            deliveryUpdatedAtEpochMs = System.currentTimeMillis()
        )
        chatFlow.value = retainHistory(chatFlow.value + message)
        persistChat()
        return id
    }

    fun updateChatAudioMessage(
        id: Long,
        text: String? = null,
        state: ChatAudioState,
        error: String? = null
    ) {
        chatFlow.value = chatFlow.value.map { message ->
            if (message.id != id) message else message.copy(
                text = text?.trim() ?: message.text,
                audioState = state,
                audioError = error?.trim()?.takeIf { it.isNotBlank() },
                deliveryState = when (state) {
                    ChatAudioState.TRANSCRIBING -> ChatDeliveryState.TRANSCRIBING
                    ChatAudioState.TRANSCRIBED -> ChatDeliveryState.TRANSCRIBED
                    ChatAudioState.SENDING -> ChatDeliveryState.SENT_TO_AGENT
                    ChatAudioState.ERROR -> ChatDeliveryState.FAILED
                },
                deliveryReason = when (state) {
                    ChatAudioState.TRANSCRIBING -> "transcription_in_progress"
                    ChatAudioState.TRANSCRIBED -> "awaiting_phrase_commit"
                    ChatAudioState.SENDING -> "sent_to_openclaw"
                    ChatAudioState.ERROR -> "transcription_failed"
                },
                deliveryTags = emptyList(),
                deliveryUpdatedAtEpochMs = System.currentTimeMillis()
            )
        }
        persistChat()
    }

    /**
     * Anexa uma imagem (screenshot/foto da camera) ao chat como se o agente a
     * tivesse enviado. Mostra thumbnail; toque abre a imagem real. O texto e a
     * legenda opcional ("Foto (camera frontal)" etc).
     */
    fun appendChatImage(role: ChatRole, caption: String, imagePath: String) {
        val message = ChatMessage(
            id = chatMessageIdSeq.incrementAndGet(),
            role = role,
            text = caption.trim(),
            atEpochMs = System.currentTimeMillis(),
            imagePath = imagePath
        )
        chatFlow.value = retainHistory(chatFlow.value + message)
        persistChat()
    }

    private fun retainHistory(messages: List<ChatMessage>): List<ChatMessage> =
        messages.takeLast(CHAT_HISTORY_HARD_LIMIT)
}
