package com.sufficit.ai.gateway.history

import android.content.Context
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatAudioState
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
                            }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
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
