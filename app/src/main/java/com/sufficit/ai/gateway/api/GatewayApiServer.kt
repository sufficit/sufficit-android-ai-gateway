package com.sufficit.ai.gateway.api

import android.util.Log
import com.sufficit.ai.gateway.config.toConfigJson
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.vision.GestureCommandIds
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * Servidor HTTP embarcado que expoe TODAS as funcoes e configuracoes do
 * gateway, incluindo participar da conversa. Roda dentro do foreground
 * service de audio (lifecycle atrelado: sobe com a captura quando
 * apiEnabled, cai no stop).
 *
 * Seguranca: bind opcional em todas as interfaces (LAN) — por isso TODA rota
 * (exceto GET /api/health) exige o token configurado, via header
 * `Authorization: Bearer <token>`, header `X-Api-Token`, ou query `?token=`.
 * Sem token configurado o servidor recusa subir (ver companion.start).
 *
 * Respostas sempre JSON. Erros usam status HTTP + corpo {error, detail}.
 */
class GatewayApiServer(
    port: Int,
    bindAll: Boolean,
    private val tokenProvider: () -> String,
    private val actions: GatewayApiActions
) : NanoHTTPD(if (bindAll) null else "127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (ex: Exception) {
            Log.e(TAG, "Erro ao processar ${session.method} ${session.uri}", ex)
            json(Response.Status.INTERNAL_ERROR, errorBody("internal_error", ex.message))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val method = session.method

        // Liveness sem auth: permite checar se a API esta de pe.
        if (method == Method.GET && uri == "/api/health") {
            return json(Response.Status.OK, JSONObject().put("ok", true).put("service", "ai-gateway"))
        }

        if (!isAuthorized(session)) {
            return json(unauthorizedStatus(), errorBody("unauthorized", "token invalido ou ausente"))
        }

        return when {
            method == Method.GET && uri == "/api/status" -> json(Response.Status.OK, statusJson())
            method == Method.GET && uri == "/api/config" -> json(Response.Status.OK, actions.currentSettings().toConfigJson())
            (method == Method.POST || method == Method.PATCH || method == Method.PUT) && uri == "/api/config" -> applyConfig(session)
            method == Method.GET && uri == "/api/chat" -> json(Response.Status.OK, chatJson())
            method == Method.GET && uri == "/api/transcripts" -> json(Response.Status.OK, transcriptsJson())
            method == Method.POST && uri == "/api/chat/clear" -> { actions.clearChat(); ok() }
            method == Method.POST && uri == "/api/listen/start" -> { actions.startListening(); ok() }
            method == Method.POST && uri == "/api/listen/stop" -> { actions.stopListening(); ok() }
            method == Method.POST && uri == "/api/standby" -> { actions.standby(); ok() }
            method == Method.POST && uri == "/api/wake" -> { actions.wake(); ok() }
            method == Method.POST && uri == "/api/interrupt" -> { actions.interruptAssistant(); ok() }
            method == Method.POST && uri == "/api/finalize" -> { actions.finalizeSegment(); ok() }
            method == Method.POST && uri == "/api/say" -> say(session)
            method == Method.POST && uri == "/api/conversation" -> conversation(session)
            method == Method.POST && uri == "/api/gesture" -> gesture(session)
            (method == Method.GET || method == Method.POST) && uri == "/api/screenshot" -> screenshot(session)
            method == Method.POST && uri == "/api/effect" -> effect(session)
            else -> json(Response.Status.NOT_FOUND, errorBody("not_found", "$method $uri"))
        }
    }

    // ------------------------------------------------------------------
    // Rotas com corpo
    // ------------------------------------------------------------------

    private fun applyConfig(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return badRequest("corpo JSON obrigatorio")
        val result = actions.applyConfigPatch(body)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("applied", JSONArray(result.appliedKeys))
                .put("ignored", JSONArray(result.ignoredKeys))
                .put("requiresCaptureRestart", result.requiresCaptureRestart)
                .put("requiresReconnect", result.requiresReconnect)
                .put("requiresApiRestart", result.requiresApiRestart)
        )
    }

    private fun say(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return badRequest("corpo JSON obrigatorio")
        val text = body.optString("text").trim()
        if (text.isBlank()) return badRequest("campo 'text' obrigatorio")
        actions.say(text)
        return ok()
    }

    private fun conversation(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return badRequest("corpo JSON obrigatorio")
        val text = body.optString("text").trim()
        if (text.isBlank()) return badRequest("campo 'text' obrigatorio")
        val speak = if (body.has("speak")) body.optBoolean("speak", true) else true
        actions.injectConversation(text, speak)
        // Despacho assincrono: a resposta do agente aparece em GET /api/chat
        // e nos campos lastAssistantReply de GET /api/status.
        return json(Response.Status.ACCEPTED, JSONObject().put("accepted", true).put("speak", speak))
    }

    private fun screenshot(session: IHTTPSession): Response {
        val label = session.parameters["label"]?.firstOrNull()?.trim()
            ?: runCatching { readJsonBody(session)?.optString("label")?.trim() }.getOrNull()
            ?: ""
        val png = actions.screenshot(label)
            ?: return json(Response.Status.NOT_FOUND, errorBody("no_screen", "app em segundo plano; abra a tela do app"))
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/png", png.inputStream(), png.size.toLong()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Content-Disposition", "inline; filename=screenshot.png")
        return response
    }

    private fun effect(session: IHTTPSession): Response {
        val label = readJsonBody(session)?.optString("label")?.trim() ?: ""
        actions.playEffect(label)
        return ok()
    }

    private fun gesture(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return badRequest("corpo JSON obrigatorio")
        val id = body.optString("id").trim().lowercase()
        val canonical = when (id) {
            GestureCommandIds.INDEX_UP, "index", "index_up", "raise" -> GestureCommandIds.INDEX_UP
            GestureCommandIds.FIST, "fist", "close" -> GestureCommandIds.FIST
            GestureCommandIds.OPEN_HAND, "open", "open_hand", "stop" -> GestureCommandIds.OPEN_HAND
            else -> return badRequest("campo 'id' invalido (use index_up, fist, open_hand)")
        }
        actions.triggerGesture(canonical)
        return ok()
    }

    // ------------------------------------------------------------------
    // Serializacao de estado (lida direto do GatewayRuntime)
    // ------------------------------------------------------------------

    private fun statusJson(): JSONObject {
        val s = GatewayRuntime.state().value
        val speaker = GatewayRuntime.speakerVoice().value
        return JSONObject().apply {
            put("listening", s.listening)
            put("speechDetected", s.speechDetected)
            put("transcribing", s.transcribing)
            put("speakingBack", s.speakingBack)
            put("statusText", s.statusText)
            put("openClawStatus", s.openClawStatus)
            put("transcriptionQueueCount", s.transcriptionQueueCount)
            put("openClawDispatchQueueCount", s.openClawDispatchQueueCount)
            put("currentTranscript", s.currentTranscript)
            put("previousTranscript", s.previousTranscript)
            put("lastAssistantReply", s.lastAssistantReply)
            put("lastAssistantReplyNeedsAttention", s.lastAssistantReplyNeedsAttention)
            put("lastAssistantReplyTags", JSONArray(s.lastAssistantReplyTags))
            put("cameraGestureStatus", s.cameraGestureStatus)
            put("multipleVoicesLikely", s.multipleVoicesLikely)
            put("ambientNoiseDetected", s.ambientNoiseDetected)
            put("currentMicrophoneGain", s.currentMicrophoneGain ?: JSONObject.NULL)
            put("transcriptionBackend", s.transcriptionBackendLabel)
            put("transcriptionModel", s.transcriptionModelLabel)
            put("speakerVoiceEnabled", speaker.enabled)
            put("speakerLastScore", speaker.lastScore ?: JSONObject.NULL)
            GatewayRuntime.gestureCommand().value?.let {
                put("activeGesture", it.gestureId)
            }
        }
    }

    private fun chatJson(): JSONObject {
        val messages = JSONArray()
        for (m in GatewayRuntime.chatMessages().value) {
            messages.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                    .put("text", m.text)
                    .put("atEpochMs", m.atEpochMs)
            )
        }
        return JSONObject().put("messages", messages)
    }

    private fun transcriptsJson(): JSONObject {
        val s = GatewayRuntime.state().value
        return JSONObject()
            .put("current", s.currentTranscript)
            .put("previous", s.previousTranscript)
            .put("recent", JSONArray(s.recentTranscripts))
    }

    // ------------------------------------------------------------------
    // Auth + helpers
    // ------------------------------------------------------------------

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val expected = tokenProvider().trim()
        if (expected.isEmpty()) return false
        val header = session.headers
        val bearer = header["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        val xApi = header["x-api-token"]?.trim()
        val query = session.parameters["token"]?.firstOrNull()?.trim()
        val provided = bearer?.takeIf { it.isNotEmpty() } ?: xApi?.takeIf { it.isNotEmpty() } ?: query
        return provided != null && constantTimeEquals(provided, expected)
    }

    private fun unauthorizedStatus(): Response.Status {
        // NanoHTTPD nao tem 401 no enum; usa o codigo cru.
        return Response.Status.UNAUTHORIZED
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            // Le o corpo bruto direto do inputStream pelo Content-Length, sem
            // parseBody: parseBody so trata POST/PUT e, ao ser chamado, avanca
            // o stream — quebrando a leitura do corpo em PATCH. No inicio do
            // serve() o stream esta posicionado no corpo para qualquer metodo.
            val length = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
            if (length <= 0) return null
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val r = session.inputStream.read(buffer, read, length - read)
                if (r <= 0) break
                read += r
            }
            val raw = String(buffer, 0, read, Charsets.UTF_8).trim()
            if (raw.isBlank()) null else JSONObject(raw)
        } catch (ex: Exception) {
            Log.w(TAG, "Falha ao ler corpo JSON", ex)
            null
        }
    }

    private fun ok(): Response = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun badRequest(detail: String): Response =
        json(Response.Status.BAD_REQUEST, errorBody("bad_request", detail))

    private fun errorBody(error: String, detail: String?): JSONObject =
        JSONObject().put("error", error).apply { detail?.let { put("detail", it) } }

    private fun json(status: Response.Status, body: JSONObject): Response {
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        private const val TAG = "GatewayApiServer"

        /**
         * Sobe o servidor se habilitado E com token configurado. Retorna o
         * server rodando ou null (desabilitado/sem token/falha de porta).
         */
        fun startIfEnabled(
            enabled: Boolean,
            token: String,
            port: Int,
            bindAll: Boolean,
            tokenProvider: () -> String,
            actions: GatewayApiActions
        ): GatewayApiServer? {
            if (!enabled) return null
            if (token.isBlank()) {
                Log.w(TAG, "API habilitada mas sem token configurado; servidor NAO iniciado.")
                return null
            }
            return try {
                val server = GatewayApiServer(port, bindAll, tokenProvider, actions)
                server.start(SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "API HTTP ouvindo em ${if (bindAll) "0.0.0.0" else "127.0.0.1"}:$port")
                server
            } catch (ex: Exception) {
                Log.e(TAG, "Falha ao iniciar API HTTP na porta $port", ex)
                null
            }
        }
    }
}
