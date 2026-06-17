package com.sufficit.ai.gateway.openclaw

import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class OpenClawGatewayConfig(
    val gatewayUrl: String,
    val gatewayToken: String,
    val deviceToken: String,
    val sessionKey: String,
    // Identidade da PESSOA (userId sufficit-ai) e desta INSTALACAO. O servidor
    // resolve o workspace/perfil pelo userId quando presente; o installationId
    // identifica o aparelho de forma estavel (nao depende do ANDROID_ID).
    val userId: String = "",
    val installationId: String = "",
    val backend: String? = null,
    val model: String? = null,
    val metadata: JSONObject? = null
)

data class OpenClawGatewayReply(
    val runId: String?,
    val rawReplyText: String,
    val replyText: String,
    val spokenReplyText: String,
    val finalState: String,
    val needsAttention: Boolean,
    val shouldSpeak: Boolean,
    val speakBlockReason: String?,
    val isSystemInfo: Boolean,
    val tags: List<String>,
    val confidence: Double?,
    val overlap: Boolean,
    val settingsPatch: JSONObject?,
    /** Falha do agente (campo "error" do envelope) — só log/status, nunca chat/TTS. */
    val errorText: String? = null,
    /** Conteúdo visual-apenas (campo "details") — painel expansível, nunca falado. */
    val detailsText: String? = null,
    /** Comandos de ferramenta escolhidos pelo agente (campo "actions"), executados no aparelho. */
    val actions: List<JSONObject> = emptyList()
)

class OpenClawGatewayClient {
    fun verifyConnection(
        config: OpenClawGatewayConfig,
        timeoutMs: Long = 30_000L
    ): String {
        val result = executeGatewayFlow(
            config = config,
            transcript = null,
            timeoutMs = timeoutMs
        )
        return result.replyText.ifBlank { "hello-ack" }
    }

    fun sendTranscript(
        config: OpenClawGatewayConfig,
        transcript: String,
        timeoutMs: Long = 120_000L
    ): OpenClawGatewayReply {
        return executeGatewayFlow(
            config = config,
            transcript = transcript.trim(),
            timeoutMs = timeoutMs
        )
    }

    private fun executeGatewayFlow(
        config: OpenClawGatewayConfig,
        transcript: String?,
        timeoutMs: Long
    ): OpenClawGatewayReply {
        val normalizedTranscript = transcript?.trim()
        if (transcript != null) {
            require(!normalizedTranscript.isNullOrBlank()) { "Transcript vazio." }
        }
        require(config.gatewayUrl.isNotBlank()) { "Gateway OpenClaw nao configurado." }
        require(config.deviceToken.isNotBlank()) { "Token do device OpenClaw nao configurado." }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<OpenClawGatewayReply?>()
        val errorRef = AtomicReference<Throwable?>()
        val helloAckReceived = AtomicBoolean(false)
        val currentSegmentId = AtomicReference<String?>()
        val socketRef = AtomicReference<WebSocket?>()
        val manualCloseRequested = AtomicBoolean(false)

        val gatewayUrl = config.gatewayUrl.trim()
        Log.i(TAG, "Abrindo websocket OpenClaw Android em $gatewayUrl")
        val webSocket = httpClient.newWebSocket(
            Request.Builder().url(gatewayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        webSocket.send(buildHelloPayload(config).toString())
                    } catch (ex: Exception) {
                        errorRef.compareAndSet(null, ex)
                        latch.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "hello_ack", "hello.ack" -> {
                                helloAckReceived.set(true)
                                if (normalizedTranscript == null) {
                                    resultRef.compareAndSet(
                                        null,
                                        buildGatewayReply(
                                            runId = null,
                                            replyText = "hello-ack",
                                            finalState = "connected"
                                        )
                                    )
                                    latch.countDown()
                                } else {
                                    val segmentId = UUID.randomUUID().toString()
                                    currentSegmentId.set(segmentId)
                                    webSocket.send(
                                        buildFinalTranscriptPayload(
                                            config = config,
                                            transcript = normalizedTranscript,
                                            segmentId = segmentId
                                        ).toString()
                                    )
                                }
                            }

                            "ack" -> {
                                if (normalizedTranscript != null &&
                                    message.optString("ackType") == "final" &&
                                    message.optString("segmentId") == currentSegmentId.get()
                                ) {
                                    Log.i(TAG, "Android channel ack final recebido: ${currentSegmentId.get()}")
                                }
                            }

                            "reply" -> {
                                val segmentId = currentSegmentId.get()
                                if (segmentId != null && message.optString("segmentId") != segmentId) {
                                    return
                                }
                                val replyText = message.optString("text").trim()
                                resultRef.compareAndSet(
                                    null,
                                    buildGatewayReply(
                                        runId = null,
                                        replyText = replyText,
                                        finalState = "final"
                                    )
                                )
                                latch.countDown()
                            }

                            "error" -> {
                                val errorMessage = buildChannelErrorMessage(message)
                                Log.e(TAG, errorMessage)
                                errorRef.compareAndSet(null, IllegalStateException(errorMessage))
                                latch.countDown()
                            }
                        }
                    } catch (ex: Exception) {
                        errorRef.compareAndSet(null, ex)
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (
                        manualCloseRequested.get() &&
                        t is java.net.SocketException &&
                        t.localizedMessage.equals("Socket closed", ignoreCase = false)
                    ) {
                        Log.i(TAG, "Websocket OpenClaw Android encerrado localmente.")
                        return
                    }
                    Log.e(TAG, "Falha websocket OpenClaw Android em $gatewayUrl", t)
                    errorRef.compareAndSet(null, IllegalStateException("Falha websocket OpenClaw: ${t.message}", t))
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!manualCloseRequested.get() && resultRef.get() == null && errorRef.get() == null) {
                        val message = if (helloAckReceived.get()) {
                            "Gateway OpenClaw Android fechou antes da resposta final ($code): ${reason.ifBlank { "sem motivo" }}"
                        } else {
                            "Gateway OpenClaw Android fechou antes do hello.ack ($code): ${reason.ifBlank { "sem motivo" }}"
                        }
                        errorRef.compareAndSet(null, IllegalStateException(message))
                    }
                    latch.countDown()
                }
            }
        )
        socketRef.set(webSocket)

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        manualCloseRequested.set(true)
        socketRef.get()?.close(1000, "done")

        if (!completed) {
            throw IllegalStateException("Timeout aguardando resposta do canal Android do OpenClaw.")
        }

        errorRef.get()?.let { throw it }
        return resultRef.get() ?: throw IllegalStateException("OpenClaw nao retornou resposta final.")
    }

    private fun buildHelloPayload(config: OpenClawGatewayConfig): JSONObject {
        return JSONObject()
            .put("type", "hello")
            .put("deviceId", resolveAndroidClientId())
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
        metadata.put("deviceId", resolveAndroidClientId())

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
            errorText = envelope.errorText,
            detailsText = envelope.detailsText,
            actions = envelope.actions
        )
    }

    fun describeAndroidDevice(): String {
        return resolveAndroidClientId()
    }

    fun resolvePreferredSessionKey(rawSessionKey: String): String {
        val normalized = rawSessionKey.trim().ifBlank { "agent:main:android:room" }
        val androidIdentity = resolveAndroidClientId()
        val prefixedAndroidIdentity = resolvePrefixedAndroidClientId()
        val legacyAndroidIdentity = resolveLegacyAndroidClientId()
        return when {
            normalized.equals("android-room", ignoreCase = true) -> androidIdentity
            normalized.equals("android:room", ignoreCase = true) -> androidIdentity
            normalized.endsWith(":android-room", ignoreCase = true) ->
                normalized.replaceAfterLast(':', androidIdentity)
            normalized.endsWith(":android:room", ignoreCase = true) ->
                normalized.replaceAfterLast(':', androidIdentity)
            normalized.equals(prefixedAndroidIdentity, ignoreCase = true) -> androidIdentity
            normalized.endsWith(":$prefixedAndroidIdentity", ignoreCase = true) ->
                replaceTrailingIdentity(normalized, prefixedAndroidIdentity, androidIdentity)
            normalized.equals(legacyAndroidIdentity, ignoreCase = true) -> androidIdentity
            normalized.endsWith(":$legacyAndroidIdentity", ignoreCase = true) ->
                replaceTrailingIdentity(normalized, legacyAndroidIdentity, androidIdentity)
            normalized.substringAfterLast(':', "").startsWith("android-", ignoreCase = true) ->
                normalized.replaceAfterLast(':', canonicalizeAndroidClientId(normalized.substringAfterLast(':')))
            else -> normalized
        }
    }

    private fun resolveAndroidInstanceId(): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().trim().lowercase()
        return androidId.ifBlank { "device" }
    }

    private fun resolveAndroidClientId(): String {
        val modelSlug = sanitizeSlug(Build.MODEL.orEmpty()).ifBlank { "android" }
        val manufacturerSlug = sanitizeSlug(Build.MANUFACTURER.orEmpty())
        val stableSuffix = resolveAndroidInstanceId()
        return listOf(manufacturerSlug, modelSlug, stableSuffix)
            .filter { it.isNotBlank() }
            .joinToString(":")
            .replace(Regex(":+"), ":")
            .trim(':')
    }

    private fun resolvePrefixedAndroidClientId(): String {
        return listOf("android", resolveAndroidClientId())
            .filter { it.isNotBlank() }
            .joinToString(":")
            .replace(Regex(":+"), ":")
            .trim(':')
    }

    private fun resolveLegacyAndroidClientId(): String {
        val modelSlug = sanitizeSlug(Build.MODEL.orEmpty()).ifBlank { "android" }
        val manufacturerSlug = sanitizeSlug(Build.MANUFACTURER.orEmpty())
        val stableSuffix = resolveAndroidInstanceId()
        return listOf("android", manufacturerSlug, modelSlug, stableSuffix)
            .filter { it.isNotBlank() }
            .joinToString("-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun canonicalizeAndroidClientId(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("android:", ignoreCase = true) ->
                trimmed.substringAfter(':').trim(':')
            !trimmed.startsWith("android-", ignoreCase = true) -> trimmed
            else -> {
            val pieces = value.split('-').filter { it.isNotBlank() }
            if (pieces.size < 4) {
                trimmed.removePrefix("android-").trim('-').replace(Regex("-+"), ":")
            } else {
                val manufacturer = pieces[1]
                val suffix = pieces.last()
                val model = pieces.subList(2, pieces.lastIndex).joinToString("-")
                listOf(manufacturer, model, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString(":")
            }
            }
        }
    }

    private fun replaceTrailingIdentity(sessionKey: String, previousIdentity: String, nextIdentity: String): String {
        val prefix = sessionKey.removeSuffix(previousIdentity).trimEnd(':')
        return if (prefix.isBlank()) nextIdentity else "$prefix:$nextIdentity"
    }

    private fun sanitizeSlug(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    companion object {
        private const val TAG = "OpenClawGateway"
        private val httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        lateinit var appContext: android.content.Context
    }
}
