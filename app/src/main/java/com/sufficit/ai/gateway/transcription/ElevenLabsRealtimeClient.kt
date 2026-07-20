package com.sufficit.ai.gateway.transcription

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Transcreve PCM16 mono/16 kHz pelo Scribe v2 Realtime. */
class ElevenLabsRealtimeClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    private data class PendingRequest(
        val completed: CountDownLatch,
        val committedText: AtomicReference<String>,
        val failure: AtomicReference<Throwable?>,
        val onPartialTranscript: (String) -> Unit,
        val startedAtNanos: Long
    )

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectionReady: CountDownLatch? = null
    @Volatile private var connectionFailure: AtomicReference<Throwable?>? = null
    @Volatile private var pendingRequest: PendingRequest? = null

    @Synchronized
    fun warmUp(apiKey: String) {
        // O endpoint encerra sessões ociosas sem responder a ping/pong. A
        // sessão é aberta somente quando há áudio, evitando uma conexão morta
        // antes do primeiro trecho.
        require(apiKey.isNotBlank()) { "Chave ElevenLabs não configurada." }
    }

    @Synchronized
    fun transcribe(
        pcmBytes: ByteArray,
        apiKey: String,
        previousText: String = "",
        onPartialTranscript: (String) -> Unit = {}
    ): WhisperTranscriptionResult = try {
        transcribeOnce(pcmBytes, apiKey, previousText, onPartialTranscript)
    } catch (firstFailure: Throwable) {
        Log.w(TAG, "Sessao realtime perdida; reconectando e repetindo o trecho uma vez.", firstFailure)
        resetConnection()
        transcribeOnce(pcmBytes, apiKey, previousText, onPartialTranscript)
    }

    private fun transcribeOnce(
        pcmBytes: ByteArray,
        apiKey: String,
        previousText: String,
        onPartialTranscript: (String) -> Unit
    ): WhisperTranscriptionResult {
        require(apiKey.isNotBlank()) { "Chave ElevenLabs nao configurada." }

        val webSocket = ensureConnected(apiKey)
        val completed = CountDownLatch(1)
        val startedAtNanos = System.nanoTime()
        val committedText = AtomicReference("")
        val failure = AtomicReference<Throwable?>(null)
        pendingRequest = PendingRequest(
            completed = completed,
            committedText = committedText,
            failure = failure,
            onPartialTranscript = onPartialTranscript,
            startedAtNanos = startedAtNanos
        )

        Log.i(
            TAG,
            "Sessao realtime pronta; enviando ${pcmBytes.size} bytes " +
                "com ${previousText.length} chars de contexto."
        )
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = (offset + AUDIO_CHUNK_BYTES).coerceAtMost(pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(offset, end)
            val isLast = end == pcmBytes.size
            val payload = JSONObject()
                .put("message_type", "input_audio_chunk")
                .put("audio_base_64", Base64.encodeToString(chunk, Base64.NO_WRAP))
                .put("sample_rate", SAMPLE_RATE_HZ)
                .put("commit", isLast)
            if (offset == 0 && previousText.isNotBlank()) {
                payload.put("previous_text", previousText)
            }
            if (!webSocket.send(payload.toString())) {
                failure.compareAndSet(null, IOException("Falha ao enviar audio para ElevenLabs."))
                completed.countDown()
                break
            }
            offset = end
        }
        Log.i(TAG, "Audio enviado e commit solicitado em ${elapsedMs(startedAtNanos)}ms.")

        try {
            if (!completed.await(REALTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException("Tempo esgotado na transcricao ElevenLabs realtime.")
            }
            failure.get()?.let { throw it }
            return WhisperTranscriptionResult(text = committedText.get())
        } finally {
            pendingRequest = null
            // Scribe v2 Realtime aceita múltiplos commits, mas na prática o
            // servidor encerra sessões ociosas e pode rejeitar o próximo input
            // após um commit. Uma sessão por trecho é mais confiável e mantém
            // a fila livre para o agrupamento por pausa.
            webSocket.close(NORMAL_CLOSE_CODE, "segment_complete")
            if (socket === webSocket) {
                clearConnectionState()
            }
        }
    }

    @Synchronized
    fun close() {
        socket?.close(NORMAL_CLOSE_CODE, "client_shutdown")
        clearConnectionState()
    }

    private fun resetConnection() {
        socket?.cancel()
        clearConnectionState()
    }

    private fun clearConnectionState() {
        socket = null
        connectionReady = null
        connectionFailure = null
        pendingRequest = null
    }

    private fun ensureConnected(apiKey: String): WebSocket {
        socket?.let { return it }

        val ready = CountDownLatch(1)
        val connectFailure = AtomicReference<Throwable?>(null)
        connectionReady = ready
        connectionFailure = connectFailure
        val request = Request.Builder()
            .url(
                "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
                    "?model_id=scribe_v2_realtime&language_code=pt" +
                    "&audio_format=pcm_16000&include_timestamps=false"
            )
            .header("xi-api-key", apiKey.trim())
            .build()

        val createdSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket persistente conectado.")
                ready.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = JSONObject(text)
                val pending = pendingRequest
                val elapsed = pending?.let { elapsedMs(it.startedAtNanos) }
                Log.i(TAG, "Evento ${event.optString("message_type")}${elapsed?.let { " em ${it}ms" }.orEmpty()}.")
                when (event.optString("message_type")) {
                    "partial_transcript" -> event.optString("text").trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { partial -> pending?.onPartialTranscript?.invoke(partial) }
                    "committed_transcript", "committed_transcript_with_timestamps" -> {
                        pending?.committedText?.set(event.optString("text").trim())
                        pending?.completed?.countDown()
                    }
                    "error", "input_error" -> {
                        val detail = event.optString("error")
                            .ifBlank { event.optString("message") }
                            .ifBlank { text }
                        Log.w(TAG, "ElevenLabs recusou o trecho: ${detail.take(160)}")
                        pending?.failure?.compareAndSet(
                            null,
                            IllegalStateException(detail)
                        )
                        pending?.completed?.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                val detail = response?.let { " HTTP ${it.code}" }.orEmpty()
                val error = IllegalStateException("ElevenLabs realtime falhou.$detail", throwable)
                connectFailure.compareAndSet(null, error)
                ready.countDown()
                pendingRequest?.failure?.compareAndSet(null, error)
                pendingRequest?.completed?.countDown()
                socket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                val error = IllegalStateException("Sessao ElevenLabs encerrada: $code $reason")
                pendingRequest?.failure?.compareAndSet(null, error)
                pendingRequest?.completed?.countDown()
            }
        })
        socket = createdSocket
        if (!ready.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            createdSocket.cancel()
            socket = null
            error("Tempo esgotado ao conectar na ElevenLabs realtime.")
        }
        connectFailure.get()?.let { throw it }
        return createdSocket
    }

    private companion object {
        const val TAG = "ElevenLabsRealtime"
        const val SAMPLE_RATE_HZ = 16_000
        const val AUDIO_CHUNK_BYTES = 6_400 // 200 ms de PCM16 mono.
        const val REALTIME_TIMEOUT_SECONDS = 45L
        const val CONNECTION_TIMEOUT_SECONDS = 20L
        const val NORMAL_CLOSE_CODE = 1000

        fun elapsedMs(startedAtNanos: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
    }
}
