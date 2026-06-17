package com.sufficit.ai.gateway.history

import android.content.Context
import com.sufficit.ai.gateway.runtime.ChatMessage
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
                    add(
                        ChatMessage(
                            id = o.optLong("id"),
                            role = parseRole(o.optString("role")),
                            text = text,
                            atEpochMs = o.optLong("atEpochMs"),
                            details = o.optString("details").takeIf { it.isNotBlank() },
                            imagePath = imagePath
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
