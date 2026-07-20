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
    val audioError: String? = null
)

/**
 * Historico de conversa exibido no dashboard (mais recente no FINAL da
 * lista), em memoria e limitado a [CHAT_HISTORY_LIMIT] mensagens.
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewayChatRuntime {
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val pendingAudioFlow = MutableStateFlow<List<PendingAudioCapture>>(emptyList())
    private val chatMessageIdSeq = AtomicLong(0L)
    private val pendingAudioIdSeq = AtomicLong(0L)
    private const val CHAT_HISTORY_LIMIT = 200

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
            val trimmed = initial.takeLast(CHAT_HISTORY_LIMIT)
            chatFlow.value = trimmed
            chatMessageIdSeq.set(trimmed.maxOf { it.id })
        }
        chatPersister = persister
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
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
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
        chatFlow.value = (chatFlow.value + message).takeLast(CHAT_HISTORY_LIMIT)
        persistChat()
    }

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
            audioState = ChatAudioState.TRANSCRIBING
        )
        chatFlow.value = (chatFlow.value + message).takeLast(CHAT_HISTORY_LIMIT)
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
                audioError = error?.trim()?.takeIf { it.isNotBlank() }
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
        chatFlow.value = (chatFlow.value + message).takeLast(CHAT_HISTORY_LIMIT)
        persistChat()
    }
}
