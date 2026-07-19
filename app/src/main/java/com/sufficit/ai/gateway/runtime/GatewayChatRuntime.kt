package com.sufficit.ai.gateway.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Papel de uma mensagem no historico de conversa do dashboard. */
enum class ChatRole { USER, ASSISTANT, SYSTEM }

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
    val imagePath: String? = null
)

/**
 * Historico de conversa exibido no dashboard (mais recente no FINAL da
 * lista), em memoria e limitado a [CHAT_HISTORY_LIMIT] mensagens.
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewayChatRuntime {
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val chatMessageIdSeq = AtomicLong(0L)
    private const val CHAT_HISTORY_LIMIT = 200

    // Persistencia em disco do historico (sobrevive a reinicio/install -r).
    // Setada pelo servico no onCreate via attachChatPersistence.
    @Volatile
    private var chatPersister: ((List<ChatMessage>) -> Unit)? = null

    fun chatMessages(): StateFlow<List<ChatMessage>> = chatFlow.asStateFlow()

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

    fun appendChatMessage(role: ChatRole, text: String, details: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val message = ChatMessage(
            id = chatMessageIdSeq.incrementAndGet(),
            role = role,
            text = trimmed,
            atEpochMs = System.currentTimeMillis(),
            details = details?.trim()?.takeIf { it.isNotBlank() }
        )
        chatFlow.value = (chatFlow.value + message).takeLast(CHAT_HISTORY_LIMIT)
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
