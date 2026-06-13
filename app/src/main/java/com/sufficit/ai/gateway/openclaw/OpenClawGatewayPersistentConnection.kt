package com.sufficit.ai.gateway.openclaw

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OpenClawGatewayPersistentConnection(
    private val listener: Listener
) {
    private data class ConnectionIdentity(
        val gatewayUrl: String,
        val deviceToken: String,
        val sessionKey: String
    )

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onReply(reply: OpenClawGatewayReply)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val helper = OpenClawGatewayClient()
    private val socketRef = AtomicReference<WebSocket?>()
    private val connected = AtomicBoolean(false)
    private val manualCloseRequested = AtomicBoolean(false)
    private val pendingMessages = ConcurrentLinkedQueue<JSONObject>()
    private val currentConfig = AtomicReference<OpenClawGatewayConfig?>()
    private val currentIdentity = AtomicReference<ConnectionIdentity?>()
    private var heartbeatExecutor: ScheduledExecutorService? = null

    fun connect(config: OpenClawGatewayConfig) {
        val normalized = config.copy(
            gatewayUrl = config.gatewayUrl.trim(),
            deviceToken = config.deviceToken.trim(),
            sessionKey = config.sessionKey.trim()
        )
        val identity = ConnectionIdentity(
            gatewayUrl = normalized.gatewayUrl,
            deviceToken = normalized.deviceToken,
            sessionKey = normalized.sessionKey
        )
        if (normalized.gatewayUrl.isBlank() || normalized.deviceToken.isBlank()) {
            return
        }
        val existingIdentity = currentIdentity.get()
        if (connected.get() && existingIdentity == identity) {
            currentConfig.set(normalized)
            return
        }
        disconnect()
        currentConfig.set(normalized)
        currentIdentity.set(identity)
        manualCloseRequested.set(false)
        Log.i(TAG, "Abrindo websocket persistente OpenClaw Android em ${normalized.gatewayUrl}")
        val webSocket = httpClient.newWebSocket(
            Request.Builder().url(normalized.gatewayUrl).build(),
            buildListener()
        )
        socketRef.set(webSocket)
    }

    fun disconnect() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
        connected.set(false)
        pendingMessages.clear()
        currentIdentity.set(null)
        manualCloseRequested.set(true)
        socketRef.getAndSet(null)?.close(1000, "disconnect")
    }

    fun sendTranscript(config: OpenClawGatewayConfig, transcript: String): String {
        val normalizedTranscript = transcript.trim()
        require(normalizedTranscript.isNotBlank()) { "Transcript vazio." }
        connect(config)
        val segmentId = UUID.randomUUID().toString()
        pendingMessages.add(buildFinalTranscriptPayload(config, normalizedTranscript, segmentId))
        flushPendingMessages()
        return segmentId
    }

    private fun buildListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = currentConfig.get() ?: return
                try {
                    webSocket.send(buildHelloPayload(config).toString())
                } catch (ex: Exception) {
                    listener.onError("Falha ao enviar hello do canal Android.", ex)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        // Server may send hello_ack (underscore) or hello.ack (dot) depending on version.
                        "hello_ack", "hello.ack" -> {
                            connected.set(true)
                            startHeartbeat()
                            flushPendingMessages()
                            listener.onConnected()
                        }

                        "ack" -> {
                            // Nao ha acao local obrigatoria; apenas confirma recebimento.
                        }

                        "reply" -> {
                            val replyText = message.optString("text").trim()
                            if (replyText.isBlank()) {
                                return
                            }
                            listener.onReply(
                                buildGatewayReply(
                                    runId = null,
                                    replyText = replyText,
                                    finalState = "final"
                                )
                            )
                        }

                        "error" -> {
                            val errorMessage = buildChannelErrorMessage(message)
                            listener.onError(errorMessage)
                        }
                    }
                } catch (ex: Exception) {
                    listener.onError("Falha ao processar mensagem do canal Android.", ex)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                if (
                    manualCloseRequested.get() &&
                    t is SocketException &&
                    t.localizedMessage.equals("Socket closed", ignoreCase = false)
                ) {
                    Log.i(TAG, "Websocket persistente OpenClaw Android encerrado localmente.")
                    return
                }
                listener.onError("Falha websocket OpenClaw: ${t.message}", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                heartbeatExecutor?.shutdownNow()
                heartbeatExecutor = null
                listener.onDisconnected("Websocket Android fechado ($code): ${reason.ifBlank { "sem motivo" }}")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutor?.scheduleAtFixedRate(
            {
                val socket = socketRef.get() ?: return@scheduleAtFixedRate
                if (!connected.get()) {
                    return@scheduleAtFixedRate
                }
                socket.send(JSONObject().put("type", "heartbeat").toString())
            },
            HEARTBEAT_INTERVAL_SECONDS,
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun flushPendingMessages() {
        if (!connected.get()) {
            return
        }
        val socket = socketRef.get() ?: return
        while (true) {
            val payload = pendingMessages.poll() ?: break
            socket.send(payload.toString())
        }
    }

    private fun buildHelloPayload(config: OpenClawGatewayConfig): JSONObject {
        return JSONObject()
            .put("type", "hello")
            .put("deviceId", helper.describeAndroidDevice())
            .put("token", config.deviceToken.trim())
            .put("sessionKey", config.sessionKey.trim())
    }

    private fun buildFinalTranscriptPayload(
        config: OpenClawGatewayConfig,
        transcript: String,
        segmentId: String
    ): JSONObject {
        val metadata = JSONObject()
        config.metadata?.let { source ->
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                metadata.put(key, source.opt(key))
            }
        }
        if (config.sessionKey.isNotBlank()) {
            metadata.put("sessionKey", config.sessionKey.trim())
        }
        metadata.put("deviceId", helper.describeAndroidDevice())
        return JSONObject()
            .put("type", "transcript.final")
            .put("segmentId", segmentId)
            .put("text", transcript)
            .put("backend", config.backend?.trim().orEmpty())
            .put("model", config.model?.trim().orEmpty())
            .put("metadata", metadata)
    }

    private fun buildChannelErrorMessage(message: JSONObject): String {
        val code = message.optString("code").trim()
        // Server sends { type: "error", error: "..." } — fall back to "message" for future protocol variants.
        val reason = message.optString("error").ifBlank { message.optString("message") }.trim()
        return "android channel falhou: ${listOf(code, reason).filter { it.isNotBlank() }.joinToString(" | ")}"
    }

    private fun buildGatewayReply(
        runId: String?,
        replyText: String,
        finalState: String
    ): OpenClawGatewayReply {
        val envelope = OpenClawReplyEnvelopeParser.parse(
            rawText = replyText,
            uncertainPrefix = "[?]"
        )
        return OpenClawGatewayReply(
            runId = runId,
            rawReplyText = envelope.rawText,
            replyText = envelope.replyText,
            spokenReplyText = envelope.spokenReplyText,
            finalState = finalState,
            needsAttention = envelope.needsAttention,
            shouldSpeak = envelope.shouldSpeak,
            speakBlockReason = envelope.speakBlockReason,
            isSystemInfo = envelope.isSystemInfo,
            tags = envelope.tags,
            confidence = envelope.confidence,
            overlap = envelope.overlap,
            settingsPatch = envelope.settingsPatch,
            errorText = envelope.errorText
        )
    }

    companion object {
        private const val TAG = "OpenClawPersistent"
        private const val HEARTBEAT_INTERVAL_SECONDS = 15L
        private val httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
