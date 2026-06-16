package com.sufficit.ai.gateway.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sufficit.ai.gateway.MainActivity
import com.sufficit.ai.gateway.R
import com.sufficit.ai.gateway.audio.speaker.SpeakerVerifier
import com.sufficit.ai.gateway.audio.speaker.SpeakerVoiceStore
import com.sufficit.ai.gateway.audio.wake.WakeWordDetector
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.vision.GestureCommandIds
import com.sufficit.ai.gateway.config.AssistantVoiceStyle
import com.sufficit.ai.gateway.config.GatewaySettings
import com.sufficit.ai.gateway.config.GatewaySettingsPatchResult
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.LocalExecutionMode
import com.sufficit.ai.gateway.config.LocalModelCatalog
import com.sufficit.ai.gateway.config.TranscriptionMode
import com.sufficit.ai.gateway.config.applyWebSocketSettingsPatch
import com.sufficit.ai.gateway.history.TranscriptHistoryEntry
import com.sufficit.ai.gateway.history.TranscriptHistoryLogger
import com.sufficit.ai.gateway.history.SpeakerContinuityHistoryEntry
import com.sufficit.ai.gateway.history.SpeakerContinuityHistoryLogger
import com.sufficit.ai.gateway.history.SpectrumDiagnosticsEntry
import com.sufficit.ai.gateway.history.SpectrumDiagnosticsLogger
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayClient
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayConfig
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayPersistentConnection
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.runtime.GatewayUiState
import com.sufficit.ai.gateway.transcription.local.LocalSherpaOnnxEngine
import com.sufficit.ai.gateway.transcription.local.LocalWhisperEngine
import com.sufficit.ai.gateway.transcription.WhisperApiClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RoomAudioForegroundService : Service(), TextToSpeech.OnInitListener, com.sufficit.ai.gateway.api.GatewayApiActions {
    private val captureRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var captureExecutor: ExecutorService? = null
    private var transcriptionExecutor: ThreadPoolExecutor? = null
    private var audioRecord: AudioRecord? = null

    // PARTIAL_WAKE_LOCK mantido durante TODA a captura: sem ele a CPU entra em
    // sono profundo (doze) com a tela apagada e o loop de audio / wake word
    // congela — o aparelho deixa de responder a palavra de ativacao. A tela
    // continua podendo apagar (so a CPU fica acordada).
    private var captureWakeLock: PowerManager.WakeLock? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var lastNotificationText: String = "Escuta de sala iniciando."
    private var settingsStore: GatewaySettingsStore? = null

    // API HTTP de controle (servidor embarcado). Sobe/cai conforme apiEnabled.
    private var apiServer: com.sufficit.ai.gateway.api.GatewayApiServer? = null

    // injectConversation(speak=false): suprime a fala da PROXIMA resposta do
    // agente uma unica vez (consumido em handleOpenClawReply).
    @Volatile private var suppressNextReplySpeech = false
    private val whisperApiClient = WhisperApiClient()
    private val openClawGatewayClient by lazy { OpenClawGatewayClient() }
    private var persistentOpenClawConnection: OpenClawGatewayPersistentConnection? = null
    private val selfTestExecuted = AtomicBoolean(false)
    @Volatile private var phraseCommitPending = false
    @Volatile private var phraseAdvanceReady = false
    @Volatile private var localWhisperEngine: LocalWhisperEngine? = null
    @Volatile private var localSherpaOnnxEngine: LocalSherpaOnnxEngine? = null
    @Volatile private var speakerContinuityState: SpeakerContinuityState? = null
    private var openClawExecutor: ExecutorService? = null
    @Volatile private var textToSpeech: TextToSpeech? = null
    @Volatile private var textToSpeechReady = false
    @Volatile private var assistantSpeaking = false
    @Volatile private var assistantInterruptedByUser = false
    @Volatile private var assistantSpeechStartedAtEpochMs = 0L
    @Volatile private var assistantLeakBaselineRms = 0.0
    @Volatile private var assistantLeakBaselineSamples = 0
    @Volatile private var suppressMicrophoneUntilEpochMs = 0L

    // Em espera: captura ativa apenas para a palavra de ativacao;
    // pipeline de transcricao suspenso ate a palavra ser detectada.
    @Volatile private var standbyMode = false

    // Pedido de finalizacao imediata do segmento de fala (gesto de punho
    // fechado = "terminei de falar, envie para processamento").
    private val finalizeSegmentRequested = AtomicBoolean(false)

    // Punho fechado tambem pede commit/despacho imediato apos a transcricao
    // terminar (sem aguardar a janela de silencio do fluxo normal).
    // Timestamp em vez de boolean: o pedido EXPIRA (COMMIT_REQUEST_TTL_MS) —
    // uma flag permanente disparava commits espurios muito depois do punho,
    // em transcricoes que nada tinham a ver com o gesto.
    private val commitAfterTranscriptionRequestedAt = java.util.concurrent.atomic.AtomicLong(0L)

    // Momento em que a ultima transcricao COM TEXTO terminou: ancora do
    // commit automatico (ver loop). O lastSpeechAt e renovado por segmentos
    // espurios de ruido/eco pos-fala (transcricao vazia) e a janela de
    // silencio classica raramente fecha; a ancora de transcricao nao sofre
    // disso.
    @Volatile private var lastTextTranscriptionAtEpochMs = 0L

    // Gravador de depuracao: ultimos 5 min de audio (rolante + segmentos
    // enviados ao Whisper) em filesDir/audio_debug para diagnostico de
    // transcricoes erradas. Ver AudioDebugStore.
    private val audioDebugStore by lazy { AudioDebugStore(this) }

    // ------------------------------------------------------------------
    // Atividade labial durante o segmento de fala (camera frontal, FaceMesh).
    // O loop de captura amostra GatewayRuntime.lipActivity() a cada chunk com
    // fala ativa; o finalize agrega (media) e o despacho anexa ao metadata
    // (lipActivityScore/lipActivitySamples) para o pre-agente do servidor
    // separar "dono falando para o aparelho" de "TV/gravacao com a voz do
    // dono". Acumuladores tocados so pela thread de captura; o agregado da
    // ultima fala e @Volatile para o thread de despacho ler.
    // ------------------------------------------------------------------
    private data class SegmentLipActivity(
        val score: Double,
        val samples: Int,
        val atEpochMs: Long
    )

    @Volatile private var lastSegmentLipActivity: SegmentLipActivity? = null
    private var lipSampleSum = 0.0
    private var lipSampleCount = 0
    private var lipLastSampledFrameAtMs = 0L

    // Palavra de ativacao (acessado apenas pela thread de captura).
    private val wakeWordDetector = WakeWordDetector()
    private var wakeWordStore: WakeWordStore? = null
    private var wakeWordConfigVersionSeen = -1
    private var wakeWordEnabled = false
    private var wakeWordRecordBuffer: ShortArray? = null
    private var wakeWordRecordFill = 0
    private var lastWakeWordDiagnosticLogAt = 0L
    @Volatile private var assistantConversationUntilEpochMs = 0L
    @Volatile private var assistantReplyInterruptedPending = false
    @Volatile private var interruptedAssistantReplyPreview = ""
    @Volatile private var lastDirectAddressToOpenClawEpochMs = 0L
    private val pendingDispatchLock = Any()
    @Volatile private var pendingOpenClawDispatchText: String = ""
    @Volatile private var pendingOpenClawDispatchState: GatewayUiState? = null
    private val pendingOpenClawDispatchGeneration = AtomicLong(0L)
    @Volatile private var activeTranscriptionStartedAtEpochMs = 0L
    @Volatile private var activeOpenClawDispatchStartedAtEpochMs = 0L

    // "Agente processando": de quando o pedido e enviado ao OpenClaw ate o
    // reply chegar (handleOpenClawReply). Timestamp para expirar caso o reply
    // nunca volte (websocket caido), evitando o balao preso.
    @Volatile private var assistantProcessingSinceMs = 0L
    @Volatile private var lastQueueReconcileAtEpochMs = 0L
    @Volatile private var cameraGestureGateOpen = false

    @Volatile private var transcriptClearTimeoutSecs = GatewaySettingsStore.DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS
    @Volatile private var openClawAccumulationWindowMs: Long = GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS * 1000L
    @Volatile private var lastTranscriptCommittedAtEpochMs = 0L
    private var transcriptClearScheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OpenClawGatewayClient.appContext = applicationContext
        settingsStore = GatewaySettingsStore(this)
        startApiServerIfEnabled(loadCurrentSettings())
        transcriptionExecutor = object : ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) {
            override fun beforeExecute(thread: Thread, runnable: Runnable) {
                activeTranscriptionStartedAtEpochMs = System.currentTimeMillis()
                super.beforeExecute(thread, runnable)
            }

            override fun afterExecute(runnable: Runnable, throwable: Throwable?) {
                activeTranscriptionStartedAtEpochMs = 0L
                super.afterExecute(runnable, throwable)
            }
        }
        openClawExecutor = Executors.newSingleThreadExecutor()
        textToSpeech = TextToSpeech(applicationContext, this)
        GatewayRuntime.cameraGestureGate().value.let { gateOpen ->
            cameraGestureGateOpen = gateOpen
            Log.i(TAG, "Camera gesture gate synced on create: open=$gateOpen")
        }
        persistentOpenClawConnection = OpenClawGatewayPersistentConnection(
            object : OpenClawGatewayPersistentConnection.Listener {
                override fun onConnected() {
                    Log.i(TAG, "Handshake OpenClaw validado: hello-ack")
                    GatewayRuntime.update {
                        it.copy(openClawStatus = "OpenClaw websocket conectado.")
                    }
                }

                override fun onDisconnected(reason: String) {
                    Log.w(TAG, reason)
                    GatewayRuntime.update {
                        it.copy(openClawStatus = reason)
                    }
                }

                override fun onReply(reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply) {
                    handleOpenClawReply(reply)
                }

                override fun onError(message: String, throwable: Throwable?) {
                    Log.e(TAG, message, throwable)
                    GatewayRuntime.update {
                        it.copy(openClawStatus = message)
                    }
                }
            }
        )
        runOpenClawHandshakeIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INTERRUPT_ASSISTANT -> {
                interruptAssistantSpeechByTouch()
                return START_STICKY
            }
            // Gesto de punho fechado ("terminei de falar"): finaliza o
            // segmento de fala em andamento na hora, sem esperar o tempo de
            // silencio, e envia para transcricao. A flag e consumida pela
            // thread de captura na proxima iteracao do loop.
            ACTION_FINALIZE_SEGMENT -> {
                finalizeSegmentRequested.set(true)
                commitAfterTranscriptionRequestedAt.set(System.currentTimeMillis())
                return START_STICKY
            }
            // Mensagem digitada na barra de chat do dashboard: vai direto
            // para o OpenClaw (sem janela de acumulacao), pelo mesmo caminho
            // das frases faladas. Digitar e enderecamento direto por
            // definicao — sem isso o pre-agente retem a mensagem como
            // "conversa ambiente".
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotBlank()) {
                    markDirectAddressNow()
                    scheduleTranscriptDispatchToOpenClaw(
                        phrase = text,
                        state = GatewayRuntime.state().value,
                        immediate = true
                    )
                }
                return START_STICKY
            }
            // Gesto de "vou falar" (indicador/apontar para a tela): alem de
            // garantir a escuta, marca enderecamento direto — a fala que vem
            // em seguida e para o assistente, nao conversa ambiente.
            ACTION_MARK_DIRECT_ADDRESS -> {
                markDirectAddressNow()
                return START_STICKY
            }
            // Config da API mudou na UI: reinicia o servidor HTTP com os
            // novos valores (porta/bind/token/enabled).
            ACTION_RELOAD_API -> {
                restartApiServer(loadCurrentSettings())
                return START_STICKY
            }
            // Identidade (userId) mudou na UI: reconecta o websocket para
            // reenviar o hello com o userId/installationId atualizados.
            ACTION_RELOAD_CONFIG -> {
                val s = loadCurrentSettings()
                refreshOpenClawConnection(s)
                restartApiServer(s)
                return START_STICKY
            }
            ACTION_STOP -> {
                // Com palavra de ativacao configurada, "parar" suspende so a
                // transcricao: a captura segue em espera escutando pela palavra.
                if (captureRunning.get() && isWakeWordStandbyAvailable()) {
                    standbyMode = true
                    GatewayRuntime.setListening(
                        active = false,
                        statusText = "Em espera. Diga a palavra de ativacao para retomar."
                    )
                    GatewayRuntime.updateWakeWord {
                        it.copy(status = "Em espera, escutando pela palavra de ativacao.")
                    }
                    refreshNotification("Em espera | aguardando palavra de ativacao")
                    return START_STICKY
                }
                stopRequested.set(true)
                shutdownCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                standbyMode = false
                startForeground(NOTIFICATION_ID, createNotification(lastNotificationText))
                startCaptureIfNeeded()
                if (captureRunning.get()) {
                    GatewayRuntime.setListening(
                        active = true,
                        statusText = "Microfone ativo. Aguardando fala."
                    )
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopApiServer()
        releaseCaptureWakeLock()
        shutdownCapture()
        releaseLocalWhisperEngine()
        releaseLocalSherpaOnnxEngine()
        speakerVerifier?.close()
        speakerVerifier = null
        speakerContinuityState = null
        transcriptionExecutor?.shutdownNow()
        transcriptionExecutor = null
        openClawExecutor?.shutdownNow()
        openClawExecutor = null
        persistentOpenClawConnection?.disconnect()
        persistentOpenClawConnection = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady = false
        assistantSpeaking = false
        assistantInterruptedByUser = false
        assistantSpeechStartedAtEpochMs = 0L
        assistantLeakBaselineRms = 0.0
        assistantLeakBaselineSamples = 0
        suppressMicrophoneUntilEpochMs = 0L
        assistantConversationUntilEpochMs = 0L
        assistantReplyInterruptedPending = false
        interruptedAssistantReplyPreview = ""
        lastDirectAddressToOpenClawEpochMs = 0L
        synchronized(pendingDispatchLock) {
            pendingOpenClawDispatchText = ""
            pendingOpenClawDispatchState = null
        }
        activeOpenClawDispatchStartedAtEpochMs = 0L
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Falha ao inicializar TextToSpeech: status=$status")
            textToSpeechReady = false
            return
        }
        val tts = textToSpeech ?: return
        val localeResult = tts.setLanguage(Locale("pt", "BR"))
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    assistantSpeaking = true
                    assistantInterruptedByUser = false
                    assistantSpeechStartedAtEpochMs = System.currentTimeMillis()
                    assistantLeakBaselineRms = 0.0
                    assistantLeakBaselineSamples = 0
                    suppressMicrophoneUntilEpochMs = System.currentTimeMillis() + ASSISTANT_SPEECH_GRACE_MS
                    GatewayRuntime.update {
                        it.copy(statusText = "Assistente falando.")
                    }
                    // Acende a tela sempre que o assistente comeca a falar:
                    // com a tela apagada a Activity pausa, a camera de gestos
                    // desliga e o usuario fica SEM como interromper (o
                    // microfone fica suprimido durante a fala). Tela acesa =
                    // camera ativa = gesto de mao aberta consegue cortar a
                    // resposta. Renovado a cada utterance; expira sozinho
                    // depois que a fala termina. Respeita ScreenMode.ALWAYS_OFF.
                    wakeScreenForAssistantSpeech()
                }

                override fun onDone(utteranceId: String?) {
                    assistantSpeaking = false
                    assistantSpeechStartedAtEpochMs = 0L
                    assistantLeakBaselineRms = 0.0
                    assistantLeakBaselineSamples = 0
                    suppressMicrophoneUntilEpochMs = if (assistantInterruptedByUser) {
                        assistantInterruptedByUser = false
                        0L
                    } else {
                        System.currentTimeMillis() + ASSISTANT_SPEECH_GRACE_MS
                    }
                    GatewayRuntime.update {
                        it.copy(statusText = if (it.listening) "Escutando ambiente." else it.statusText)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    assistantSpeaking = false
                    assistantSpeechStartedAtEpochMs = 0L
                    assistantLeakBaselineRms = 0.0
                    assistantLeakBaselineSamples = 0
                    suppressMicrophoneUntilEpochMs = if (assistantInterruptedByUser) {
                        assistantInterruptedByUser = false
                        0L
                    } else {
                        System.currentTimeMillis() + ASSISTANT_SPEECH_GRACE_MS
                    }
                    Log.w(TAG, "Falha no TTS do OpenClaw: errorCode=$errorCode")
                }
            }
        )
        textToSpeechReady = localeResult != TextToSpeech.LANG_MISSING_DATA &&
            localeResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!textToSpeechReady) {
            Log.w(TAG, "TextToSpeech pt-BR indisponivel neste aparelho.")
            return
        }
        applyAssistantVoiceSettings(loadCurrentSettings())
    }

    private fun createNotification(contentText: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenClaw escuta ativa",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Indica que o OpenClaw continua ouvindo e respondendo em segundo plano."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RoomAudioForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val interruptIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RoomAudioForegroundService::class.java).apply {
                action = ACTION_INTERRUPT_ASSISTANT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentStateLabel = when {
            assistantSpeaking -> "Assistente falando"
            captureRunning.get() -> "Escutando ambiente"
            else -> "Inicializando escuta"
        }
        val expandedText = buildString {
            append(currentStateLabel)
            if (contentText.isNotBlank() && contentText != currentStateLabel) {
                append('\n')
                append(contentText)
            }
            append('\n')
            append("Use Parar escuta para encerrar o servico.")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw ativo em segundo plano")
            .setContentText(currentStateLabel)
            .setSubText("Puxe a barra superior para controlar.")
            .setSmallIcon(R.drawable.ic_mic_status)
            .setContentIntent(contentIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .addAction(0, "Abrir", contentIntent)
            .apply {
                if (assistantSpeaking) {
                    addAction(0, "Interromper voz", interruptIntent)
                }
            }
            .addAction(0, "Parar escuta", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun startCaptureIfNeeded() {
        if (captureRunning.get()) {
            return
        }

        stopRequested.set(false)

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastNotificationText = "Permissao de microfone ausente."
            GatewayRuntime.setListening(active = false, statusText = lastNotificationText)
            refreshNotification(lastNotificationText)
            stopSelf()
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            lastNotificationText = "Falha ao preparar buffer de audio."
            GatewayRuntime.setListening(active = false, statusText = lastNotificationText)
            refreshNotification(lastNotificationText)
            stopSelf()
            return
        }

        val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE_HZ)
        val recorder = createPreferredAudioRecord(bufferSize)

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            lastNotificationText = "Nao foi possivel inicializar o microfone."
            GatewayRuntime.setListening(active = false, statusText = lastNotificationText)
            refreshNotification(lastNotificationText)
            stopSelf()
            return
        }

        audioRecord = recorder
        attachInputAudioEffects(recorder)
        captureRunning.set(true)
        captureExecutor = Executors.newSingleThreadExecutor()
        captureExecutor?.execute {
            runCaptureLoop(recorder, bufferSize)
        }
        startTranscriptClearScheduler()
    }

    private fun runCaptureLoop(recorder: AudioRecord, bufferSize: Int) {
        acquireCaptureWakeLock()
        val buffer = ShortArray(bufferSize / 2)
        val loadedSettings = settingsStore?.load() ?: GatewaySettingsStore(this).load()
        var settings = normalizeRuntimeSettings(loadedSettings)
        transcriptClearTimeoutSecs = settings.transcriptClearTimeoutSecs
        openClawAccumulationWindowMs = settings.openClawAccumulationWindowSecs * 1000L
        if (settings != loadedSettings) {
            runCatching { settingsStore?.save(settings) ?: GatewaySettingsStore(this).save(settings) }
        }
        cameraGestureGateOpen = if (settings.cameraGestureEnabled) {
            GatewayRuntime.cameraGestureGate().value
        } else {
            true
        }
        Log.i(TAG, "Capture loop gate state: enabled=${settings.cameraGestureEnabled}, open=$cameraGestureGateOpen")
        updateCameraGestureGateStatus(settings)
        val captureProfile = resolveCaptureProfile(settings)
        Log.i(
            TAG,
            "Capture settings: mode=${settings.transcriptionMode.persistedValue}, " +
                "model=${settings.localModelPath}, localExecution=${settings.localExecutionMode.persistedValue}, " +
                "micGain=${"%.2f".format(settings.microphoneGain)}, " +
                "cameraGestureEnabled=${settings.cameraGestureEnabled}, " +
                "remoteModel=${settings.remoteModel}, " +
                "whisperUrl=${settings.whisperUrl}"
        )
        Log.i(TAG, "Local transcription timeout: ${LOCAL_TRANSCRIPTION_TIMEOUT_MS / 1000}s")
        // Historico de niveis RMS CRUS (pre-ganho): alimenta as metricas de
        // estabilidade ambiente (spectrumMotion/variancia). Nunca misturar
        // valores pos-ganho aqui — o ganho dinamico variando criaria saltos
        // artificiais que contaminam a deteccao de ruido ambiente.
        val spectrum = MutableList(SPECTRUM_SIZE) { 0f }

        // Historico exibido na UI (pos-ganho): o usuario ve o nivel efetivo
        // apos a regulagem automatica de volume.
        val uiSpectrum = MutableList(SPECTRUM_SIZE) { 0f }
        val speechBuffer = ByteArrayOutputStream()
        var speechActive = false
        var lastNotificationAt = 0L
        var captureStartedAt = 0L
        var lastSpeechAt = 0L
        var speechCandidateFrames = 0
        // Bytes de pre-roll prefixados no segmento ABERTO (0 quando o
        // segmento continua apos a janela maxima): a verificacao de locutor
        // avalia o audio sem esse prefixo de ambiente.
        var segmentPreRollBytes = 0
        var noiseFloorRms = 0.008
        val recentRmsWindow = ArrayDeque<Double>(NOISE_STABILITY_WINDOW)
        val recentSpectrumMotionWindow = ArrayDeque<Double>(NOISE_STABILITY_WINDOW)
        var ambientNoiseHoldFrames = 0
        var ambientNoiseReleaseFrames = 0
        var ambientNoiseDetected = false
        var ambientNoiseKind: String? = null
        var ambientNoiseScore: Double? = null
        var lastLoggedDynamicGain = Double.NaN
        var smoothedDynamicGain = settings.microphoneGain
        var lastWarnedGain = Double.NaN
        var lastSpectrumDiagnosticAtEpochMs = 0L

        try {
            Log.i(TAG, "AudioRecord.startRecording() iniciando...")
            recorder.startRecording()
            Log.i(TAG, "AudioRecord.startRecording() concluido.")
            GatewayRuntime.setListening(
                active = true,
                statusText = "Microfone ativo. Aguardando fala."
            )
            GatewayRuntime.update {
                it.copy(
                    transcriptionBackendLabel = when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> "Remoto"
                        TranscriptionMode.LOCAL -> {
                            when (settings.localExecutionMode) {
                                LocalExecutionMode.CPU -> "Local CPU"
                                LocalExecutionMode.NNAPI -> "Local NNAPI"
                            }
                        }
                    },
                    transcriptionModelLabel = when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> settings.remoteModel.trim()
                        TranscriptionMode.LOCAL -> File(settings.localModelPath).name.trim()
                    }
                )
            }
            refreshNotification("Microfone ativo. Aguardando fala.")
            selfTestExecuted.set(true)
            // Sessao de captura nova: anel de pre-roll nunca herda audio da
            // sessao anterior.
            clearPreRoll()

            while (captureRunning.get()) {
                val readCount = recorder.read(buffer, 0, buffer.size)
                if (readCount <= 0) {
                    continue
                }

                val rawRms = calculateRms(buffer, readCount)
                val rawPeak = calculatePeak(buffer, readCount)
                val rawPeakNormalized = rawPeak.toDouble() / Short.MAX_VALUE.toDouble()
                val rawZeroCrossingRate = calculateZeroCrossingRate(buffer, readCount)
                val speechLikeFrameRaw = isSpeechLikeFrame(
                    rms = rawRms,
                    peakNormalized = rawPeakNormalized,
                    zeroCrossingRate = rawZeroCrossingRate,
                    vadThreshold = settings.vadThreshold,
                    noiseFloorRms = noiseFloorRms,
                    transcriptionMode = settings.transcriptionMode,
                    settings = settings
                )
                val now = System.currentTimeMillis()
                if (now - lastQueueReconcileAtEpochMs >= TRANSCRIPTION_QUEUE_RECONCILE_INTERVAL_MS) {
                    lastQueueReconcileAtEpochMs = now
                    updateQueueCount()
                }
                // Expira o balao de "processando" se o reply nunca voltar
                // (ex.: websocket caiu) — evita o balao preso.
                if (assistantProcessingSinceMs > 0L && now - assistantProcessingSinceMs > ASSISTANT_PROCESSING_TIMEOUT_MS) {
                    setAssistantProcessing(false)
                }
                settings = normalizeRuntimeSettings(loadCurrentSettings())
                updateCameraGestureGateStatus(settings)
                updateSpectrum(spectrum, rawRms.toFloat())
                val spectrumMotion = estimateRecentSpectrumMotion(spectrum)
                pushLimitedSample(recentRmsWindow, rawRms, NOISE_STABILITY_WINDOW)
                pushLimitedSample(recentSpectrumMotionWindow, spectrumMotion, NOISE_STABILITY_WINDOW)
                val rmsVariance = estimateNormalizedRmsVariance(recentRmsWindow)
                val dynamicContrast = (rawRms - noiseFloorRms).coerceAtLeast(0.0)
                val stabilityScore = estimateAmbientStabilityScore(
                    dynamicContrast = dynamicContrast,
                    rmsVariance = rmsVariance,
                    spectrumMotion = spectrumMotion,
                    speechLikeFrameRaw = speechLikeFrameRaw,
                    settings = settings
                )
                val environmentLooksStable = stabilityScore >= settings.ambientStabilityThreshold
                val likelyMusic = environmentLooksStable &&
                    rawZeroCrossingRate in AMBIENT_MUSIC_MIN_ZERO_CROSSING_RATE..AMBIENT_MUSIC_MAX_ZERO_CROSSING_RATE &&
                    dynamicContrast >= AMBIENT_MUSIC_MIN_DYNAMIC_CONTRAST

                if (environmentLooksStable) {
                    ambientNoiseHoldFrames += 1
                    ambientNoiseReleaseFrames = 0
                } else {
                    ambientNoiseHoldFrames = 0
                    ambientNoiseReleaseFrames += 1
                }

                if (!ambientNoiseDetected && ambientNoiseHoldFrames >= settings.ambientDetectionHoldFrames) {
                    ambientNoiseDetected = true
                }
                if (ambientNoiseDetected && ambientNoiseReleaseFrames >= settings.ambientDetectionReleaseFrames) {
                    ambientNoiseDetected = false
                }

                ambientNoiseKind = if (ambientNoiseDetected) {
                    if (likelyMusic) "music" else "noise"
                } else {
                    null
                }
                ambientNoiseScore = if (ambientNoiseDetected) stabilityScore else null

                val dynamicSpeechOverride =
                    dynamicContrast >= settings.ambientSpeechOverrideDynamicContrast ||
                        spectrumMotion >= settings.ambientSpeechOverrideSpectrumMotion
                val shouldBlockAsAmbientNoise =
                    ambientNoiseDetected &&
                        !speechActive &&
                        !dynamicSpeechOverride
                // Gain-only stability: computed without the speechLikeFrameRaw penalty so that
                // music (which triggers the VAD but is acoustically stable) can still cause gain
                // reduction, breaking the deadlock where music prevents ambient detection which
                // prevents gain reduction.
                val environmentStableForGain =
                    settings.microphoneAutoSensitivityEnabled &&
                        estimateAmbientStabilityScore(
                            dynamicContrast = dynamicContrast,
                            rmsVariance = rmsVariance,
                            spectrumMotion = spectrumMotion,
                            speechLikeFrameRaw = false,
                            settings = settings
                        ) >= settings.ambientGainStabilityThreshold
                // Gain reduction path: uses environmentStableForGain (no speechLikeFrame penalty,
                // no dynamicSpeechOverride guard) so that acoustically-stable environments like music
                // always reduce the gain, even when contrast keeps dynamicSpeechOverride true.
                val shouldReduceGainForAmbient = !speechActive && environmentStableForGain
                val shouldCompensateAmbientNoise =
                    shouldReduceGainForAmbient ||
                        (!speechActive && !dynamicSpeechOverride &&
                            (environmentLooksStable || ambientNoiseDetected))
                var dynamicMicrophoneGain = if (settings.microphoneAutoSensitivityEnabled) {
                    resolveAutomaticMicrophoneGain(
                        peakGain = settings.microphoneGain,
                        noiseFloorRms = noiseFloorRms,
                        speechLikeFrame = speechLikeFrameRaw && !shouldCompensateAmbientNoise,
                        speechActive = speechActive && !shouldCompensateAmbientNoise,
                        inputPeakNormalized = rawPeakNormalized,
                        settings = settings
                    )
                } else {
                    settings.microphoneGain
                }
                if (settings.microphoneAutoSensitivityEnabled && shouldCompensateAmbientNoise) {
                    dynamicMicrophoneGain = minOf(
                        dynamicMicrophoneGain,
                        settings.microphoneGain * (
                            settings.ambientGainFactor -
                                (stabilityScore * settings.ambientGainStabilityReduction)
                            )
                    ).coerceIn(settings.ambientGainMinGain, settings.microphoneGain.coerceAtLeast(settings.ambientGainMinGain))
                }
                if (settings.microphoneAutoSensitivityEnabled) {
                    if ((speechLikeFrameRaw || speechActive) && dynamicMicrophoneGain > smoothedDynamicGain) {
                        // Ataque imediato: ao detectar fala o ganho salta
                        // direto para o alvo. Com a subida suavizada (bug
                        // antigo) o inicio de cada frase era gravado com o
                        // ganho reduzido de ambiente e o volume subia no meio
                        // do segmento — pessimo para transcricao, palavra de
                        // ativacao e perfil de voz. A QUEDA continua suave
                        // (fast/slow) para o ambiente nao "bombear".
                        smoothedDynamicGain = dynamicMicrophoneGain
                    } else {
                        val smoothingFactor = if (dynamicMicrophoneGain < smoothedDynamicGain) {
                            settings.ambientGainSmoothingFast
                        } else {
                            settings.ambientGainSmoothingSlow
                        }
                        smoothedDynamicGain += (dynamicMicrophoneGain - smoothedDynamicGain) * smoothingFactor
                    }
                    dynamicMicrophoneGain = smoothedDynamicGain.coerceIn(
                        settings.ambientGainMinGain,
                        settings.microphoneGain.coerceAtLeast(settings.ambientGainMinGain)
                    )
                }
                if (settings.microphoneAutoSensitivityEnabled) {
                    if (lastLoggedDynamicGain.isNaN() || abs(dynamicMicrophoneGain - lastLoggedDynamicGain) >= 0.05) {
                        val noiseLabel = if (ambientNoiseDetected || environmentLooksStable) "ambient" else "normal"
                        Log.i(
                            TAG,
                            "Auto gain update: gain=${"%.2f".format(dynamicMicrophoneGain)} mode=$noiseLabel contrast=${"%.4f".format(dynamicContrast)} score=${"%.2f".format(stabilityScore)}"
                        )
                        lastLoggedDynamicGain = dynamicMicrophoneGain
                    }
                    if (
                        shouldCompensateAmbientNoise &&
                        (lastWarnedGain.isNaN() || abs(dynamicMicrophoneGain - lastWarnedGain) >= AMBIENT_GAIN_WARNING_DELTA)
                    ) {
                        lastWarnedGain = dynamicMicrophoneGain
                        val gainLabel = String.format(Locale.US, "%.2fx", dynamicMicrophoneGain)
                        GatewayRuntime.update {
                            it.copy(
                                microphoneGainAdjustedUntilEpochMs = now + MICROPHONE_GAIN_WARNING_HOLD_MS,
                                microphoneGainAdjustedMessage = "Ajuste de ganho: $gainLabel"
                            )
                        }
                    }
                }
                applyMicrophoneGain(buffer, readCount, dynamicMicrophoneGain)
                // Depuracao: grava TUDO que o mic captou (pos-ganho), antes
                // de qualquer supressao/descarte da segmentacao.
                audioDebugStore.appendRolling(buffer, readCount)
                val rms = calculateRms(buffer, readCount)
                val peak = calculatePeak(buffer, readCount)
                val peakNormalized = peak.toDouble() / Short.MAX_VALUE.toDouble()
                val zeroCrossingRate = calculateZeroCrossingRate(buffer, readCount)
                // Metricas continuam no historico cru; a UI recebe o pos-ganho
                // em lista separada (ver comentario na declaracao de spectrum).
                updateSpectrum(uiSpectrum, rms.toFloat())
                var suppressMicrophone = assistantSpeaking || now < suppressMicrophoneUntilEpochMs
                if (assistantSpeaking) {
                    val assistantSpeechAgeMs = now - assistantSpeechStartedAtEpochMs
                    if (
                        assistantSpeechStartedAtEpochMs > 0L &&
                        assistantSpeechAgeMs < ASSISTANT_BARGE_IN_STARTUP_BLOCK_MS
                    ) {
                        val sampleCount = assistantLeakBaselineSamples + 1
                        assistantLeakBaselineRms =
                            ((assistantLeakBaselineRms * assistantLeakBaselineSamples) + rms) / sampleCount.toDouble()
                        assistantLeakBaselineSamples = sampleCount
                    }
                } else {
                }
                if (suppressMicrophone) {
                    // Fala do assistente: o que o microfone capta agora e
                    // eco de TTS — nunca pode prefixar o proximo segmento.
                    clearPreRoll()
                    if (speechActive) {
                        speechActive = false
                        speechBuffer.reset()
                        segmentPreRollBytes = 0
                    }
                    GatewayRuntime.update {
                        it.copy(
                            // Microfone suprimido durante a fala do assistente:
                            // espectro zerado para nao parecer que ha captura.
                            spectrum = FLAT_SPECTRUM,
                            speechDetected = false,
                            statusText = if (assistantSpeaking) {
                                "Assistente falando. Toque para interromper."
                            } else {
                                "Aguardando fim da fala do assistente."
                            }
                        )
                    }
                    if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                        lastNotificationAt = now
                        refreshNotification(
                            if (assistantSpeaking) {
                                "Assistente falando..."
                            } else {
                                "Aguardando fim da fala do assistente..."
                            }
                        )
                    }
                    continue
                }
                handleWakeWordAudio(buffer, readCount, now, settings)

                if (standbyMode) {
                    if (!wakeWordEnabled) {
                        // Palavra de ativacao desligada durante a espera: nada
                        // mais a escutar, encerra a captura de vez.
                        Log.i(TAG, "Standby sem palavra de ativacao disponivel; encerrando captura.")
                        stopRequested.set(true)
                        captureRunning.set(false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        continue
                    }
                    // Standby: microfone aberto so para a palavra de
                    // ativacao; nada daqui prefixa segmento.
                    clearPreRoll()
                    if (speechActive) {
                        speechActive = false
                        speechBuffer.reset()
                        segmentPreRollBytes = 0
                    }
                    GatewayRuntime.update {
                        it.copy(
                            // Espectro zerado: o microfone segue aberto (so
                            // para a palavra de ativacao), mas nada esta sendo
                            // aproveitado — o visual deve refletir "mudo".
                            spectrum = FLAT_SPECTRUM,
                            speechDetected = false,
                            listening = false,
                            statusText = "Em espera. Diga a palavra de ativacao para retomar."
                        )
                    }
                    if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                        lastNotificationAt = now
                        refreshNotification("Em espera | aguardando palavra de ativacao")
                    }
                    continue
                }

                // Durante o cadastro de voz o gate do gesto e ignorado: o
                // usuario esta na tela de configuracao falando as frases de
                // amostra — exigir o indicador levantado aqui travava o
                // cadastro sem nenhum feedback.
                val cameraGateBlocking = isCameraGestureGateBlocking(settings) &&
                    !GatewayRuntime.isSpeakerEnrollmentPending()
                val speechLikeFrame = speechLikeFrameRaw && !shouldBlockAsAmbientNoise && !cameraGateBlocking

                // Pre-roll continuo (pos-ganho): com o gate fechado o
                // microfone esta semanticamente mudo — anel zerado para nada
                // dali prefixar o proximo segmento.
                if (cameraGateBlocking) {
                    clearPreRoll()
                } else {
                    pushPreRollChunk(buffer, readCount)
                }

                if (now - lastSpectrumDiagnosticAtEpochMs >= SPECTRUM_DIAGNOSTIC_LOG_INTERVAL_MS) {
                    lastSpectrumDiagnosticAtEpochMs = now
                    appendSpectrumDiagnostics(
                        rawRms = rawRms,
                        adjustedRms = rms,
                        noiseFloorRms = noiseFloorRms,
                        dynamicContrast = dynamicContrast,
                        rmsVariance = rmsVariance,
                        spectrumMotion = spectrumMotion,
                        stabilityScore = stabilityScore,
                        ambientNoiseDetected = ambientNoiseDetected,
                        ambientNoiseKind = ambientNoiseKind,
                        speechLikeRaw = speechLikeFrameRaw,
                        speechLikeEffective = speechLikeFrame,
                        dynamicSpeechOverride = dynamicSpeechOverride,
                        shouldCompensateAmbientNoise = shouldCompensateAmbientNoise,
                        shouldBlockAsAmbientNoise = shouldBlockAsAmbientNoise,
                        dynamicMicrophoneGain = dynamicMicrophoneGain,
                        zeroCrossingRate = zeroCrossingRate,
                        peakNormalized = peakNormalized,
                        spectrum = spectrum
                    )
                }

                if (cameraGateBlocking && speechActive) {
                    val duration = now - captureStartedAt
                    Log.i(TAG, "Finalizando segmento por fechamento do gesto (${duration}ms).")
                    finalizeSpeechSegment(
                        pcmBytes = speechBuffer.toByteArray(),
                        settings = settings,
                        durationMs = duration,
                        captureProfile = captureProfile,
                        preRollPrefixBytes = segmentPreRollBytes
                    )
                    speechBuffer.reset()
                    segmentPreRollBytes = 0
                    speechActive = false
                    phraseAdvanceReady = false
                    if (duration > 0L) {
                        lastSpeechAt = now
                    }
                }

                if (!speechLikeFrame) {
                    noiseFloorRms = ((noiseFloorRms * 0.94) + (rawRms * 0.06)).coerceIn(0.003, 0.05)
                } else if (ambientNoiseDetected && !speechActive) {
                    // Ruido continuo que passa o VAD (ex.: musica estavel):
                    // sem esta atualizacao o piso de ruido congela e o VAD
                    // fica permanentemente aberto. Sobe bem devagar — fala
                    // real raramente segura ambientNoiseDetected por muito
                    // tempo, e speechActive bloqueia durante segmentos.
                    noiseFloorRms = ((noiseFloorRms * 0.985) + (rawRms * 0.015)).coerceIn(0.003, 0.05)
                }
                if (speechLikeFrame) {
                    speechCandidateFrames += 1
                } else {
                    speechCandidateFrames = 0
                }
                val speechDetected = speechLikeFrame || speechActive
                val minimumSpeechCandidateFrames = when (settings.transcriptionMode) {
                    TranscriptionMode.REMOTE -> maxOf(1, settings.minSpeechCandidateFrames - 1)
                    TranscriptionMode.LOCAL -> settings.minSpeechCandidateFrames
                }

                // Correlacao audio/labios: enquanto o segmento esta vivo,
                // amostra o sinal labial publicado pela camera. Antes dos
                // finalizes abaixo para o ultimo chunk do segmento contar.
                if (speechActive) {
                    sampleLipActivityForSegment(now)
                }

                // Gesto de punho fechado ("terminei de falar"): finaliza o
                // segmento em andamento AGORA e envia para transcricao, sem
                // aguardar o tempo de silencio. A flag e consumida mesmo sem
                // fala ativa para um punho antigo nao disparar mais tarde.
                if (finalizeSegmentRequested.getAndSet(false) && speechActive) {
                    val duration = now - captureStartedAt
                    Log.i(TAG, "Finalizando segmento por gesto de punho (${duration}ms).")
                    finalizeSpeechSegment(
                        pcmBytes = speechBuffer.toByteArray(),
                        settings = settings,
                        durationMs = duration,
                        captureProfile = captureProfile,
                        preRollPrefixBytes = segmentPreRollBytes
                    )
                    speechBuffer.reset()
                    segmentPreRollBytes = 0
                    speechActive = false
                    phraseAdvanceReady = false
                    lastSpeechAt = now
                }

                if (speechDetected) {
                    if (!speechActive) {
                        if (speechCandidateFrames < minimumSpeechCandidateFrames) {
                            GatewayRuntime.update {
                                it.copy(
                                    spectrum = uiSpectrum.toList(),
                                    speechDetected = false,
                                    ambientNoiseDetected = ambientNoiseDetected,
                                    ambientNoiseKind = ambientNoiseKind,
                                    ambientNoiseScore = ambientNoiseScore,
                                    statusText = if (ambientNoiseDetected) {
                                        "Ambiente estavel detectado. Aguardando mudanca de voz."
                                    } else if (cameraGateBlocking) {
                                        "Aguardando gesto da camera para abrir o microfone."
                                    } else {
                                        "Escutando ambiente."
                                    }
                                )
                            }
                            continue
                        }
                        if (
                            phraseCommitPending &&
                            phraseAdvanceReady
                        ) {
                            commitCurrentTranscriptToPrevious()
                            phraseCommitPending = false
                            phraseAdvanceReady = false
                        }
                        speechActive = true
                        speechBuffer.reset()
                        // Segmento abre com o pre-roll na frente: o comeco da
                        // frase aconteceu DURANTE a deteccao (candidate
                        // frames) e ja passou — sem isso a transcricao perdia
                        // as primeiras palavras. captureStartedAt recua para
                        // a duracao do segmento refletir o audio real.
                        segmentPreRollBytes = drainPreRollInto(speechBuffer)
                        val preRollMs = segmentPreRollBytes * 1000L / (SAMPLE_RATE_HZ * 2L)
                        captureStartedAt = now - preRollMs
                        if (preRollMs > 0L) {
                            Log.i(TAG, "Segmento aberto com pre-roll de ${preRollMs}ms.")
                        }
                    }

                    // So fala REAL (speechLikeFrame) renova o relogio de
                    // silencio. O codigo original fazia lastSpeechAt = now
                    // incondicional — como speechDetected inclui speechActive,
                    // o relogio renovava a cada chunk e o corte por silencio
                    // (no else-if abaixo) era INALCANCAVEL: o segmento so
                    // fechava por janela maxima (que REABRE), punho, gate ou
                    // fala do assistente. Resultado: gravacao eterna e nenhum
                    // envio automatico por silencio.
                    if (speechLikeFrame) {
                        lastSpeechAt = now
                    }
                    appendPcm16(speechBuffer, buffer, readCount)

                    // Corte por silencio dentro do branch vivo: a fala parou
                    // ha speechHoldMs (e o indicador nao esta segurando).
                    if (
                        speechActive &&
                        now - lastSpeechAt > captureProfile.speechHoldMs &&
                        !isIndexFingerHeld(now)
                    ) {
                        val duration = now - captureStartedAt
                        Log.i(TAG, "Finalizando segmento por silencio (${duration}ms).")
                        finalizeSpeechSegment(
                            pcmBytes = speechBuffer.toByteArray(),
                            settings = settings,
                            durationMs = duration,
                            captureProfile = captureProfile,
                            preRollPrefixBytes = segmentPreRollBytes
                        )
                        speechBuffer.reset()
                        segmentPreRollBytes = 0
                        speechActive = false
                        phraseAdvanceReady = false
                    }
                } else if (speechActive) {
                    appendPcm16(speechBuffer, buffer, readCount)
                    // Corte por silencio — EXCETO enquanto o usuario mantem o
                    // dedo indicador levantado: o gesto sustentado e um pedido
                    // explicito de manter a gravacao aberta (pausa para pensar
                    // no meio da frase, por exemplo). Ao abaixar o dedo, este
                    // fluxo normal de silencio volta a valer. O limite duro de
                    // maxSpeechSegmentMs (abaixo) continua valendo sempre.
                    if (now - lastSpeechAt > captureProfile.speechHoldMs && !isIndexFingerHeld(now)) {
                        val duration = now - captureStartedAt
                        Log.i(TAG, "Finalizando segmento por silencio (${duration}ms).")
                        finalizeSpeechSegment(
                            pcmBytes = speechBuffer.toByteArray(),
                            settings = settings,
                            durationMs = now - captureStartedAt,
                            captureProfile = captureProfile,
                            preRollPrefixBytes = segmentPreRollBytes
                        )
                        speechBuffer.reset()
                        segmentPreRollBytes = 0
                        speechActive = false
                        phraseAdvanceReady = false
                    }
                }

                // Commit automatico (envio sem gesto). Duas ancoras, vale a
                // que fechar primeiro:
                //  1. CLASSICA: silencio de phraseBreakSilenceMs apos a fala
                //     (lastSpeechAt). Fragil em ambiente vivo: segmentos
                //     espurios de ruido/eco renovam lastSpeechAt e a janela
                //     nunca fecha — era o motivo de "so envia com punho".
                //  2. POR TRANSCRICAO: a ultima transcricao COM TEXTO
                //     terminou ha AUTO_COMMIT_AFTER_TRANSCRIPTION_MS e nao ha
                //     fala ativa nem fila pendente — o texto esta parado em
                //     currentTranscript, envia. Segmentos espurios (texto
                //     vazio) nao renovam esta ancora.
                // Pausa para PENSAR nao e fim de frase: transcricao pendente
                // terminando em virgula/hifen/conector ("...chamada do tipo-",
                // "...e aí,") indica fala inacabada — as janelas de commit
                // esticam para nao despachar ao OpenClaw no meio do raciocinio
                // (a resposta interrompia o usuario). Indicador levantado
                // tambem segura o commit, mesmo contrato do corte por
                // silencio: o gesto e o "pera, ainda vou falar" explicito.
                val unfinishedSpeech =
                    transcriptLooksUnfinished(GatewayRuntime.state().value.currentTranscript)
                val phraseBreakWindowMs = if (unfinishedSpeech) {
                    captureProfile.phraseBreakSilenceMs * UNFINISHED_SPEECH_SILENCE_MULTIPLIER
                } else {
                    captureProfile.phraseBreakSilenceMs
                }
                val transcriptionCommitWindowMs = if (unfinishedSpeech) {
                    AUTO_COMMIT_UNFINISHED_TRANSCRIPTION_MS
                } else {
                    AUTO_COMMIT_AFTER_TRANSCRIPTION_MS
                }
                val autoCommitBySilence =
                    lastSpeechAt > 0L &&
                        now - lastSpeechAt > phraseBreakWindowMs
                val autoCommitByTranscription =
                    lastTextTranscriptionAtEpochMs > 0L &&
                        now - lastTextTranscriptionAtEpochMs > transcriptionCommitWindowMs &&
                        transcriptionExecutor?.let { reconcileTranscriptionQueue(it) == 0 } != false
                if (
                    !speechActive &&
                    phraseCommitPending &&
                    !isIndexFingerHeld(now) &&
                    (autoCommitBySilence || autoCommitByTranscription)
                ) {
                    val runtimeSnapshot = GatewayRuntime.state().value
                    if (!runtimeSnapshot.transcribing) {
                        Log.i(
                            TAG,
                            "Commit automatico (${if (autoCommitBySilence) "silencio" else "pos-transcricao"}): despachando frase."
                        )
                        commitCurrentTranscriptToPrevious()
                        phraseCommitPending = false
                        phraseAdvanceReady = false
                        lastSpeechAt = 0L
                        lastTextTranscriptionAtEpochMs = 0L
                    } else {
                        phraseAdvanceReady = true
                    }
                }

                if (speechActive && now - captureStartedAt >= captureProfile.maxSpeechSegmentMs) {
                    val duration = now - captureStartedAt
                    Log.i(TAG, "Finalizando segmento por janela maxima (${duration}ms).")
                    finalizeSpeechSegment(
                        pcmBytes = speechBuffer.toByteArray(),
                        settings = settings,
                        durationMs = duration,
                        captureProfile = captureProfile,
                        preRollPrefixBytes = segmentPreRollBytes
                    )
                    speechBuffer.reset()
                    segmentPreRollBytes = 0
                    captureStartedAt = now
                    lastSpeechAt = now
                    speechActive = true
                }

                GatewayRuntime.update {
                    it.copy(
                        spectrum = uiSpectrum.toList(),
                        speechDetected = speechActive,
                        currentMicrophoneGain = dynamicMicrophoneGain,
                        estimatedNoiseFloorRms = noiseFloorRms,
                        ambientNoiseDetected = ambientNoiseDetected,
                        ambientNoiseKind = ambientNoiseKind,
                        ambientNoiseScore = ambientNoiseScore,
                        microphoneGainAdjustedUntilEpochMs = if (
                            it.microphoneGainAdjustedUntilEpochMs <= now
                        ) {
                            0L
                        } else {
                            it.microphoneGainAdjustedUntilEpochMs
                        },
                        microphoneGainAdjustedMessage = if (
                            it.microphoneGainAdjustedUntilEpochMs <= now
                        ) {
                            null
                        } else {
                            it.microphoneGainAdjustedMessage
                        },
                        statusText = when {
                            it.transcribing -> "Transcrevendo trecho de voz..."
                            cameraGateBlocking -> "Aguardando gesto da camera para abrir o microfone."
                            ambientNoiseDetected -> "Ambiente estavel detectado. Aguardando mudanca de voz."
                            speechActive -> "Fala detectada."
                            else -> "Escutando ambiente."
                        }
                    )
                }

                if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                    lastNotificationAt = now
                    val text = when {
                        GatewayRuntime.state().value.transcribing -> "Transcrevendo fala detectada."
                        cameraGateBlocking -> "Aguardando gesto da camera"
                        speechActive -> "Fala detectada | pico $peak"
                        ambientNoiseDetected -> "Ruido estavel | sem envio para transcricao"
                        else -> "Silencio | RMS ${"%.4f".format(rms)}"
                    }
                    refreshNotification(text)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Audio capture loop failed", ex)
            GatewayRuntime.setListening(active = false, statusText = "Falha na captura: ${ex.javaClass.simpleName}")
            refreshNotification("Falha na captura: ${ex.javaClass.simpleName}")
        } finally {
            runCatching { audioDebugStore.flushRolling() }
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }

            recorder.release()
            if (audioRecord === recorder) {
                audioRecord = null
            }
            captureRunning.set(false)
            releaseCaptureWakeLock()
            GatewayRuntime.setListening(active = false, statusText = "Servico parado.")
        }
    }

    private fun acquireCaptureWakeLock() {
        if (captureWakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            captureWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG:capture"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "PARTIAL_WAKE_LOCK de captura adquirido (CPU acordada com tela apagada).")
        }.onFailure { Log.w(TAG, "Falha ao adquirir wake lock de captura", it) }
    }

    private fun releaseCaptureWakeLock() {
        runCatching {
            captureWakeLock?.let { if (it.isHeld) it.release() }
        }
        captureWakeLock = null
    }

    private fun runLocalSelfTestIfNeeded(settings: GatewaySettings) {
        if (settings.transcriptionMode != TranscriptionMode.LOCAL) {
            return
        }

        if (!selfTestExecuted.compareAndSet(false, true)) {
            return
        }

        transcriptionExecutor?.execute {
            try {
                GatewayRuntime.update {
                    it.copy(
                        transcribing = true,
                        statusText = "Executando autoteste local..."
                    )
                }
                refreshNotification("Executando autoteste local...")

                val pcmBytes = loadSelfTestPcm16()
                val result = transcribeLocal(settings, pcmBytes)
                val correctedText = TranscriptTextPipeline.applyCorrections(this, result.text, settings)

                Log.i(TAG, "Local self-test transcript: $correctedText")
                GatewayRuntime.update {
                    it.copy(
                        transcribing = false,
                        currentTranscript = correctedText.ifBlank { it.currentTranscript },
                        statusText = if (correctedText.isBlank()) {
                            "Autoteste local concluido sem texto."
                        } else {
                            "Autoteste local concluido."
                        }
                    )
                }
                phraseCommitPending = correctedText.isNotBlank()
                phraseAdvanceReady = false
                refreshNotification("Autoteste local concluido.")
            } catch (ex: Exception) {
                Log.e(TAG, "Local self-test failed", ex)
                handleFatalError("Falha no autoteste local", ex)
            }
        }
    }

    private fun loadSelfTestPcm16(): ByteArray {
        val wavBytes = resources.openRawResource(R.raw.local_selftest).use { input ->
            input.readBytes()
        }

        if (wavBytes.size <= 44) {
            throw IOException("Arquivo de autoteste WAV invalido.")
        }

        return wavBytes.copyOfRange(44, wavBytes.size)
    }

    // Amostra o sinal labial atual para o segmento de fala em andamento.
    // So conta quadros FRESCOS (camera viva) com rosto no quadro; cada quadro
    // do FaceMesh conta uma unica vez (dedupe por atEpochMs). Tela apagada ou
    // visao indisponivel = lipActivity null = segmento sem amostras = campo
    // omitido do metadata (sinal opcional, nunca penaliza por ausencia).
    private fun sampleLipActivityForSegment(now: Long) {
        val lip = GatewayRuntime.lipActivity().value ?: return
        if (lip.faceCount <= 0) return
        if (now - lip.atEpochMs > LIP_SAMPLE_FRESH_MS) return
        if (lip.atEpochMs == lipLastSampledFrameAtMs) return
        lipLastSampledFrameAtMs = lip.atEpochMs
        lipSampleSum += lip.score
        lipSampleCount += 1
    }

    // Consome os acumuladores labiais do segmento que esta sendo finalizado
    // e publica o agregado (media) para o despacho. Sempre zera — segmento
    // descartado nao pode vazar amostras para o proximo.
    private fun consumeSegmentLipActivity() {
        val samples = lipSampleCount
        val sum = lipSampleSum
        lipSampleSum = 0.0
        lipSampleCount = 0
        lipLastSampledFrameAtMs = 0L
        if (samples > 0) {
            lastSegmentLipActivity = SegmentLipActivity(
                score = sum / samples,
                samples = samples,
                atEpochMs = System.currentTimeMillis()
            )
        }
    }

    private fun finalizeSpeechSegment(
        pcmBytes: ByteArray,
        settings: GatewaySettings,
        durationMs: Long,
        captureProfile: CaptureProfile,
        // Bytes de pre-roll (ambiente pre-fala) no INICIO de pcmBytes: vao
        // para a transcricao (e o motivo do pre-roll existir), mas ficam
        // FORA da verificacao de locutor — ambiente dilui o embedding e
        // infla a duracao usada no limiar adaptativo de trecho curto.
        preRollPrefixBytes: Int = 0
    ) {
        consumeSegmentLipActivity()
        // Anel zerado: o rabo do segmento que acabou de fechar ja foi (ou
        // sera) transcrito — prefixa-lo no proximo segmento duplicaria as
        // ultimas palavras na proxima transcricao.
        clearPreRoll()
        if (pcmBytes.isEmpty() || durationMs < captureProfile.minTranscriptionMs) {
            Log.i(TAG, "Segmento descartado: bytes=${pcmBytes.size}, duracao=${durationMs}ms.")
            return
        }
        val segmentLooksLikeSpeech = segmentLooksLikeSpeech(
            pcmBytes = pcmBytes,
            settings = settings
        )
        if (!segmentLooksLikeSpeech) {
            Log.i(TAG, "Segmento com baixa confianca local mantido para o OpenClaw decidir: bytes=${pcmBytes.size}, duracao=${durationMs}ms.")
        }
        Log.i(TAG, "Segmento pronto para transcricao: bytes=${pcmBytes.size}, duracao=${durationMs}ms.")
        val executor = transcriptionExecutor
        if (executor == null) {
            Log.w(TAG, "Fila de transcricao indisponivel; segmento descartado.")
            return
        }

        val queuedTranscriptions = reconcileTranscriptionQueue(executor)
        val pendingTranscriptions = queuedTranscriptions + if (executor.activeCount > 0) 1 else 0
        if (pendingTranscriptions >= MAX_TRANSCRIPTION_QUEUE) {
            Log.w(TAG, "Fila de transcricao cheia (${pendingTranscriptions}); segmento descartado.")
            GatewayRuntime.update {
                it.copy(
                    statusText = "Fila de transcricao cheia. Aguarde processamento.",
                    transcriptionQueueCount = queuedTranscriptions
                )
            }
            return
        }

        val wavBytes = buildWavPcm16(
            pcmBytes = pcmBytes,
            sampleRate = SAMPLE_RATE_HZ,
            channels = 1,
            bitsPerSample = 16
        )
        // Depuracao: copia exata do WAV enviado ao Whisper fica no aparelho
        // (5 min) para comparar com a transcricao devolvida.
        val debugSegmentName = audioDebugStore.saveSegment(
            wavBytes = wavBytes,
            durationMs = durationMs,
            preRollPrefixBytes = preRollPrefixBytes
        )

        executor.execute(
            QueuedTranscriptionTask(System.currentTimeMillis()) {
                updateQueueCount()
                // Verificacao de locutor ("so a minha voz"): roda aqui no
                // executor de transcricao (custo de CPU fora da captura).
                // Retorna false quando o segmento foi consumido pelo cadastro
                // de voz ou rejeitado por nao ser a voz do usuario.
                if (!evaluateSpeakerVoiceGate(pcmBytes, preRollPrefixBytes)) {
                    updateQueueCount()
                    return@QueuedTranscriptionTask
                }
                GatewayRuntime.update {
                    it.copy(
                        transcribing = true,
                        statusText = when (settings.transcriptionMode) {
                            TranscriptionMode.REMOTE -> "Enviando trecho para transcricao..."
                            TranscriptionMode.LOCAL -> {
                                when (settings.localExecutionMode) {
                                    LocalExecutionMode.CPU -> "Transcrevendo localmente na CPU..."
                                    LocalExecutionMode.NNAPI -> "Transcrevendo localmente via NNAPI..."
                                }
                            }
                        }
                    )
                }
                requestScreenAttention(settings)
                refreshNotification(
                    when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> "Enviando trecho para transcricao..."
                        TranscriptionMode.LOCAL -> {
                            when (settings.localExecutionMode) {
                                LocalExecutionMode.CPU -> "Transcrevendo localmente na CPU..."
                                LocalExecutionMode.NNAPI -> "Transcrevendo localmente via NNAPI..."
                            }
                        }
                    }
                )

                try {
                val rawResult = when (settings.transcriptionMode) {
                    TranscriptionMode.REMOTE -> {
                        if (settings.whisperUrl.isBlank()) {
                            throw IllegalStateException("Endpoint remoto nao configurado.")
                        }

                        whisperApiClient.transcribe(
                            wavBytes = wavBytes,
                            whisperUrl = settings.whisperUrl,
                            authToken = settings.whisperAuthToken,
                            model = settings.remoteModel,
                            prompt = TranscriptTextPipeline.buildPrompt(settings),
                            vadFilter = settings.whisperVadFilter,
                            conditionOnPreviousText = settings.whisperConditionOnPreviousText,
                            noSpeechThreshold = settings.whisperNoSpeechThreshold,
                            compressionRatioThreshold = settings.whisperCompressionRatioThreshold,
                            repetitionPenalty = settings.whisperRepetitionPenalty
                        )
                    }

                    TranscriptionMode.LOCAL -> {
                        transcribeLocalWithTimeout(settings, pcmBytes)
                    }
                }

                val correctedTextRaw = TranscriptTextPipeline.applyCorrections(this, rawResult.text, settings)
                // Depuracao: associa o texto devolvido ao WAV salvo do
                // segmento (cru + corrigido) para auditoria local.
                audioDebugStore.appendTranscript(
                    segmentFileName = debugSegmentName,
                    transcript = correctedTextRaw,
                    extra = JSONObject()
                        .put("rawTranscript", rawResult.text)
                        .put("mode", settings.transcriptionMode.name)
                        .put("durationMs", durationMs)
                        .put("preRollPrefixBytes", preRollPrefixBytes)
                )
                // Guarda anti-alucinacao: o Whisper em ruido pode devolver a
                // mesma palavra em loop ("xuxu, xuxu, ..."). Descarta aqui,
                // independente do servidor — nada disso vai para o chat nem
                // para o OpenClaw.
                val correctedText = if (TranscriptTextPipeline.isLikelyHallucinatedRepetition(correctedTextRaw)) {
                    Log.w(TAG, "Transcricao descartada como alucinacao repetitiva: ${correctedTextRaw.take(80)}")
                    GatewayRuntime.update {
                        it.copy(statusText = "Trecho descartado: repeticao tipica de alucinacao do Whisper.")
                    }
                    ""
                } else {
                    correctedTextRaw
                }
                // Transcricao que e SO palavra de ativacao ("xuxu", "xuxu xuxu",
                // "openclaw"): ja cumpriu o papel de acordar a escuta. Nao vira
                // bolha de conversa nem vai para o OpenClaw — registra apenas
                // uma marca de sistema discreta no chat.
                val wakeTermOnly = if (correctedText.isNotBlank()) {
                    TranscriptTextPipeline.wakeTermOnlyTranscript(correctedText, settings)
                } else {
                    null
                }
                if (wakeTermOnly != null) {
                    updateQueueCount()
                    Log.i(TAG, "Transcricao e apenas wake term '$wakeTermOnly'; nao despachada (marca de sistema).")
                    GatewayRuntime.appendChatMessage(
                        ChatRole.SYSTEM,
                        "palavra de ativacao reconhecida: \u201C$wakeTermOnly\u201D"
                    )
                    GatewayRuntime.update {
                        it.copy(statusText = "Palavra de ativacao reconhecida: $wakeTermOnly.")
                    }
                    return@QueuedTranscriptionTask
                }
                val neutralTranscriptMarker = TranscriptTextPipeline.isNeutralMarkerTranscript(correctedText)
                val ambientTranscriptLikely = TranscriptTextPipeline.shouldIgnoreAmbientTranscript(correctedText)

                if (neutralTranscriptMarker) {
                    Log.i(TAG, "Marcador neutro mantido para avaliacao do OpenClaw: $correctedText")
                }
                if (ambientTranscriptLikely) {
                    Log.i(TAG, "Marcador de ambiente mantido para avaliacao do OpenClaw: $correctedText")
                }

                val enriched = enrichLocalVoiceAnalysisIfNeeded(
                    settings = settings,
                    pcmBytes = pcmBytes,
                    transcriptionResult = rawResult
                )
                // Only update speaker continuity when transcription returned actual text.
                // Blank results (silence, noise, music) must not corrupt continuity state.
                if (rawResult.text.isNotBlank()) {
                    if (enriched.analysis?.multipleVoicesLikely == true) {
                        Log.i(TAG, "Sobreposicao provavel de vozes detectada; continuidade de falante congelada neste trecho.")
                        enriched.analysis.voiceSignature?.let { signature ->
                            appendSpeakerContinuityHistory(
                                SpeakerContinuityHistoryEntry(
                                    occurredAt = Instant.now(),
                                    decision = "overlap_skipped",
                                    rawProbability = speakerContinuityState?.sameSpeakerProbability,
                                    adjustedProbability = speakerContinuityState?.sameSpeakerProbability,
                                    sampleCount = speakerContinuityState?.sampleCount ?: 0,
                                    mismatchStreak = speakerContinuityState?.mismatchStreak ?: 0,
                                    anchorConfidence = speakerContinuityState?.anchorConfidence ?: 0.0,
                                    anchor = speakerContinuityState?.anchor,
                                    current = signature
                                )
                            )
                        }
                    } else {
                        val continuityUpdate = SpeakerContinuityTracker.updateWithComputation(
                            currentState = speakerContinuityState,
                            signature = enriched.analysis?.voiceSignature
                        )
                        speakerContinuityState = continuityUpdate.state
                        continuityUpdate.computation?.let { computation ->
                            appendSpeakerContinuityHistory(
                                SpeakerContinuityHistoryEntry(
                                    occurredAt = Instant.now(),
                                    decision = computation.decision,
                                    rawProbability = computation.rawProbability,
                                    adjustedProbability = computation.adjustedProbability,
                                    sampleCount = computation.sampleCount,
                                    mismatchStreak = computation.mismatchStreak,
                                    anchorConfidence = computation.anchorConfidence,
                                    anchor = computation.anchor,
                                    current = computation.current
                                )
                            )
                        }
                    }
                }
                val result = enriched.result

                GatewayRuntime.update {
                    val currentState = it
                    val shouldAdvanceWindow = TranscriptWindowing.shouldAdvanceTranscriptWindow(
                        current = currentState.currentTranscript,
                        incoming = correctedText,
                        phraseAdvanceReady = phraseAdvanceReady,
                        repeatSuppression = settings.transcriptionRepeatSuppression
                    )
                    if (shouldAdvanceWindow) {
                        appendTranscriptHistory(
                            phrase = currentState.currentTranscript,
                            state = currentState
                        )
                    }
                    val baseState = if (shouldAdvanceWindow) {
                        val committedTranscript = currentState.currentTranscript.trim()
                        currentState.copy(
                            previousTranscript = committedTranscript,
                            currentTranscript = "",
                            recentTranscripts = pushRecentTranscript(
                                currentState.recentTranscripts,
                                committedTranscript
                            )
                        )
                    } else {
                        currentState
                    }
                    val mergedCurrent = TranscriptWindowing.mergeCurrentTranscript(
                        current = baseState.currentTranscript,
                        incoming = correctedText,
                        repeatSuppression = settings.transcriptionRepeatSuppression
                    )
                    baseState.copy(
                        transcribing = false,
                        currentTranscript = mergedCurrent,
                        lastError = null,
                        lastGender = translateGender(result.gender),
                        lastEmotion = translateEmotion(result.emotion),
                        sameSpeakerProbability = speakerContinuityState?.sameSpeakerProbability,
                        voiceLearningProgress = speakerContinuityState?.anchorConfidence,
                        multipleVoicesLikely = enriched.analysis?.multipleVoicesLikely == true,
                        statusText = if (mergedCurrent.isBlank()) {
                            "Trecho processado, sem texto retornado."
                        } else if (neutralTranscriptMarker) {
                            "Marcador neutro enviado ao OpenClaw para decidir contexto."
                        } else if (ambientTranscriptLikely) {
                            "Marcador de ambiente enviado ao OpenClaw para decidir contexto."
                        } else if (enriched.analysis?.multipleVoicesLikely == true) {
                            "Possivel sobreposicao de vozes detectada."
                        } else {
                            "Transcricao recebida."
                        }
                    )
                }
                if (correctedText.isNotBlank()) {
                    phraseCommitPending = true
                    phraseAdvanceReady = false
                }
                Log.i(TAG, "Transcricao processada. texto=${correctedText.length} chars")
                if (correctedText.isNotBlank()) {
                    lastTextTranscriptionAtEpochMs = System.currentTimeMillis()
                }
                // Punho fechado = "terminei, ENVIE": despacho deterministico.
                // O caminho normal de commit exige ~1.8s de silencio limpo sem
                // transcricao em andamento — em ambiente com VAD ativo essa
                // janela pode nunca chegar e a frase fica presa em
                // currentTranscript ("transcrevendo..." eterno). Aqui, apos a
                // transcricao disparada pelo punho concluir e a fila esvaziar,
                // o commit roda na hora e envia ao OpenClaw. O pedido expira
                // (TTL) para nao disparar commits espurios em transcricoes
                // posteriores sem relacao com o gesto.
                val commitRequestedAt = commitAfterTranscriptionRequestedAt.get()
                if (
                    commitRequestedAt > 0L &&
                    System.currentTimeMillis() - commitRequestedAt <= COMMIT_REQUEST_TTL_MS &&
                    transcriptionExecutor?.let { reconcileTranscriptionQueue(it) == 0 } != false &&
                    GatewayRuntime.state().value.currentTranscript.isNotBlank()
                ) {
                    commitAfterTranscriptionRequestedAt.set(0L)
                    Log.i(TAG, "Commit imediato por gesto de punho: despachando frase acumulada.")
                    commitCurrentTranscriptToPrevious()
                    phraseCommitPending = false
                    phraseAdvanceReady = false
                } else if (commitRequestedAt > 0L &&
                    System.currentTimeMillis() - commitRequestedAt > COMMIT_REQUEST_TTL_MS
                ) {
                    // Pedido velho: descarta sem agir.
                    commitAfterTranscriptionRequestedAt.set(0L)
                }
                requestScreenAttention(settings)
                refreshNotification("Transcricao recebida.")
            } catch (ex: Exception) {
                if (ex is CancellationException || ex is InterruptedException || stopRequested.get()) {
                    Log.i(TAG, "Transcricao cancelada durante parada do servico.")
                    GatewayRuntime.update {
                        it.copy(
                            transcribing = false,
                            statusText = if (captureRunning.get()) {
                                "Escutando ambiente."
                            } else {
                                "Servico parado."
                            }
                        )
                    }
                    refreshNotification("Servico parado.")
                    return@QueuedTranscriptionTask
                }
                Log.e(TAG, "Whisper transcription failed", ex)
                val msg = ex.message.orEmpty()
                // HTTP 4xx/5xx are transient (e.g. 429 capacity, 503 unavailable) — don't kill the service
                if (ex is IllegalStateException && (msg.contains("HTTP 4") || msg.contains("HTTP 5"))) {
                    GatewayRuntime.update {
                        it.copy(
                            transcribing = false,
                            lastError = msg.take(120),
                            statusText = "Whisper indisponivel: ${msg.take(80)}"
                        )
                    }
                    refreshNotification("Whisper indisponivel.")
                } else {
                    handleFatalError("Falha na transcricao", ex)
                }
            } finally {
                updateQueueCount()
            }
        })

        updateQueueCount()
    }

    private fun transcribeLocalWithTimeout(
        settings: GatewaySettings,
        pcmBytes: ByteArray
    ): com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult {
        val worker = Executors.newSingleThreadExecutor()
        return try {
            val future = worker.submit<com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult> {
                transcribeLocal(settings, pcmBytes)
            }
            future.get(LOCAL_TRANSCRIPTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Transcricao local interrompida.")
        } catch (_: TimeoutException) {
            throw IllegalStateException(
                "Tempo limite na transcricao local (${LOCAL_TRANSCRIPTION_TIMEOUT_MS / 1000}s). " +
                    "Modelo pesado para este dispositivo."
            )
        } finally {
            worker.shutdownNow()
        }
    }

    private fun handleFatalError(title: String, ex: Exception) {
        val details = buildString {
            append(ex.javaClass.simpleName)
            ex.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }
        GatewayRuntime.setError(title, details)
        refreshNotification(title)
        shutdownCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun transcribeLocal(
        settings: GatewaySettings,
        pcmBytes: ByteArray
    ): com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult {
        val modelFile = File(settings.localModelPath)
        val bundle = LocalModelCatalog.findByPath(settings.localModelPath)
        check(modelFile.exists()) {
            val availableModels = modelFile.parentFile
                ?.listFiles()
                ?.filter { it.isDirectory || it.isFile }
                ?.joinToString(separator = ", ") { it.name }
                .orEmpty()
                .ifBlank { "nenhum arquivo local encontrado" }
            "Modelo local nao encontrado: ${modelFile.name}. Disponiveis: $availableModels"
        }

        val language = bundle?.language ?: if (modelFile.name.lowercase().contains(".en")) "en" else "pt"
        return when (settings.localExecutionMode) {
            LocalExecutionMode.CPU -> {
                if (bundle != null) {
                    val engine = synchronized(this) {
                        localSherpaOnnxEngine ?: LocalSherpaOnnxEngine(this).also {
                            localSherpaOnnxEngine = it
                            Log.i(TAG, "sherpa-onnx local inicializado para reuso em memoria.")
                        }
                    }
                    engine.transcribePcm16(
                        pcmBytes = pcmBytes,
                        modelPath = settings.localModelPath,
                        executionMode = settings.localExecutionMode,
                        language = language
                    )
                } else {
                    val engine = synchronized(this) {
                        localWhisperEngine ?: LocalWhisperEngine(this).also {
                            localWhisperEngine = it
                            Log.i(TAG, "Whisper local inicializado para reuso em memoria.")
                        }
                    }
                    engine.transcribePcm16(
                        pcmBytes = pcmBytes,
                        modelPath = settings.localModelPath,
                        useGpu = false,
                        language = language
                    )
                }
            }
            LocalExecutionMode.NNAPI -> {
                val engine = synchronized(this) {
                    localSherpaOnnxEngine ?: LocalSherpaOnnxEngine(this).also {
                        localSherpaOnnxEngine = it
                        Log.i(TAG, "sherpa-onnx local inicializado para reuso em memoria.")
                    }
                }
                engine.transcribePcm16(
                    pcmBytes = pcmBytes,
                    modelPath = settings.localModelPath,
                    executionMode = settings.localExecutionMode,
                    language = language
                )
            }
        }
    }

    private data class EnrichedTranscriptionResult(
        val result: com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult,
        val analysis: LocalVoiceAnalysisResult?
    )

    private fun enrichLocalVoiceAnalysisIfNeeded(
        settings: GatewaySettings,
        pcmBytes: ByteArray,
        transcriptionResult: com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult
    ): EnrichedTranscriptionResult {
        val analysis = LocalVoiceAnalyzer.analyzePcm16(
            pcmBytes = pcmBytes,
            sampleRate = SAMPLE_RATE_HZ
        )

        return EnrichedTranscriptionResult(
            result = transcriptionResult.copy(
                gender = analysis.gender,
                emotion = analysis.emotion
            ),
            analysis = analysis
        )
    }

    private fun normalizeRuntimeSettings(settings: GatewaySettings): GatewaySettings {
        val model = settings.localModelPath.trim()
        val recommendedVadThreshold = when {
            settings.transcriptionMode != TranscriptionMode.LOCAL -> 0.008
            isHeavyLocalModel(settings.copy(localModelPath = model)) -> 0.010
            isBalancedLocalModel(settings.copy(localModelPath = model)) -> 0.006
            isFastLocalModel(settings.copy(localModelPath = model)) -> 0.004
            else -> GatewaySettingsStore.DEFAULT_VAD_THRESHOLD
        }
        val vadThreshold = if (
            settings.vadThreshold <= 0.0 ||
            (
                settings.transcriptionMode == TranscriptionMode.LOCAL &&
                    settings.vadThreshold < recommendedVadThreshold
            )
        ) {
            recommendedVadThreshold
        } else {
            settings.vadThreshold
        }
        val microphoneGain = when {
            settings.transcriptionMode != TranscriptionMode.LOCAL -> maxOf(settings.microphoneGain, 2.4)
            else -> settings.microphoneGain
        }
        val openClawSessionKey = openClawGatewayClient.resolvePreferredSessionKey(settings.openClawSessionKey)
        return settings.copy(
            localExecutionMode = settings.localExecutionMode,
            localModelPath = model.ifBlank { GatewaySettingsStore.DEFAULT_LOCAL_MODEL_PATH },
            vadThreshold = vadThreshold,
            microphoneGain = microphoneGain,
            openClawSessionKey = openClawSessionKey
        )
    }

    private fun syncCameraGestureGateFromRuntime(settings: GatewaySettings): Boolean {
        cameraGestureGateOpen = if (settings.cameraGestureEnabled) {
            GatewayRuntime.cameraGestureGate().value
        } else {
            true
        }
        return cameraGestureGateOpen
    }

    private fun isCameraGestureGateBlocking(settings: GatewaySettings): Boolean {
        return settings.cameraGestureEnabled && !syncCameraGestureGateFromRuntime(settings)
    }

    private fun updateCameraGestureGateStatus(settings: GatewaySettings) {
        val gateOpen = syncCameraGestureGateFromRuntime(settings)
        if (!settings.cameraGestureEnabled) {
            return
        }
        GatewayRuntime.setCameraGestureStatus(
            if (gateOpen) {
                "Gesto detectado. Microfone liberado."
            } else {
                "Aguardando gesto da camera para abrir o microfone."
            }
        )
    }

    private fun resolveCaptureProfile(settings: GatewaySettings): CaptureProfile {
        val baseProfile = when {
            isHeavyLocalModel(settings) -> CaptureProfile(
                speechHoldMs = 450L,
                maxSpeechSegmentMs = 1_800L,
                minTranscriptionMs = 650L,
                phraseBreakSilenceMs = 1_600L
            )
            isBalancedLocalModel(settings) -> CaptureProfile(
                speechHoldMs = 320L,
                maxSpeechSegmentMs = 1_200L,
                minTranscriptionMs = 350L,
                phraseBreakSilenceMs = 1_350L
            )
            isFastLocalModel(settings) -> CaptureProfile(
                speechHoldMs = 460L,
                maxSpeechSegmentMs = 1_900L,
                minTranscriptionMs = 520L,
                phraseBreakSilenceMs = 1_700L
            )
            else -> CaptureProfile(
                speechHoldMs = DEFAULT_SPEECH_HOLD_MS,
                maxSpeechSegmentMs = DEFAULT_MAX_SPEECH_SEGMENT_MS,
                minTranscriptionMs = DEFAULT_MIN_TRANSCRIPTION_MS,
                phraseBreakSilenceMs = DEFAULT_PHRASE_BREAK_SILENCE_MS
            )
        }

        if (!settings.development) {
            return baseProfile
        }

        return baseProfile.copy(
            speechHoldMs = (settings.debugSpeechHoldMs?.takeIf { it > 0 } ?: baseProfile.speechHoldMs.toInt()).toLong(),
            maxSpeechSegmentMs = (settings.debugMaxSpeechSegmentMs?.takeIf { it > 0 } ?: baseProfile.maxSpeechSegmentMs.toInt()).toLong(),
            minTranscriptionMs = (settings.debugMinTranscriptionMs?.takeIf { it > 0 } ?: baseProfile.minTranscriptionMs.toInt()).toLong(),
            phraseBreakSilenceMs = (settings.debugPhraseBreakSilenceMs?.takeIf { it > 0 } ?: baseProfile.phraseBreakSilenceMs.toInt()).toLong()
        )
    }

    private fun isFastLocalModel(settings: GatewaySettings): Boolean {
        val path = settings.localModelPath.lowercase()
        return path.contains("sherpa-whisper-tiny")
    }

    private fun isBalancedLocalModel(settings: GatewaySettings): Boolean {
        val path = settings.localModelPath.lowercase()
        return path.contains("sherpa-whisper-base") || path.contains("sherpa-whisper-small")
    }

    private fun isHeavyLocalModel(settings: GatewaySettings): Boolean {
        val path = settings.localModelPath.lowercase()
        return path.contains("sherpa-whisper-medium") || path.contains("sherpa-whisper-turbo")
    }

    private fun downloadModel(url: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                connect()
            }
            if (connection.responseCode !in 200..299) {
                throw IOException("Download HTTP ${connection.responseCode} para ${targetFile.name}")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (tempFile.length() <= 0L) {
                throw IOException("Download vazio para ${targetFile.name}")
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Nao foi possivel substituir ${targetFile.name}")
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Nao foi possivel finalizar download de ${targetFile.name}")
            }
        } finally {
            runCatching { connection?.disconnect() }
            if (tempFile.exists() && (!targetFile.exists() || targetFile.length() <= 0L)) {
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun shutdownCapture() {
        stopRequested.set(true)
        captureRunning.set(false)
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
        }
        captureExecutor?.shutdownNow()
        captureExecutor = null
        transcriptClearScheduler?.shutdownNow()
        transcriptClearScheduler = null
        audioRecord?.release()
        audioRecord = null
        updateQueueCount()
    }

    private fun startTranscriptClearScheduler() {
        if (transcriptClearScheduler != null) return
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        transcriptClearScheduler = scheduler
        scheduler.scheduleAtFixedRate({
            val timeoutSecs = transcriptClearTimeoutSecs
            if (timeoutSecs <= 0) return@scheduleAtFixedRate
            val committedAt = lastTranscriptCommittedAtEpochMs
            if (committedAt <= 0L) return@scheduleAtFixedRate
            if (System.currentTimeMillis() - committedAt < timeoutSecs * 1000L) return@scheduleAtFixedRate
            val current = GatewayRuntime.state().value
            val recents = current.recentTranscripts
            when {
                recents.isNotEmpty() ->
                    GatewayRuntime.update { s -> s.copy(recentTranscripts = s.recentTranscripts.dropLast(1)) }
                current.previousTranscript.isNotBlank() ->
                    GatewayRuntime.update { s -> s.copy(previousTranscript = "") }
                current.currentTranscript.isNotBlank() ->
                    GatewayRuntime.update { s -> s.copy(currentTranscript = "") }
                else -> lastTranscriptCommittedAtEpochMs = 0L
            }
        }, 2L, 2L, TimeUnit.SECONDS)
    }

    private fun createPreferredAudioRecord(bufferSize: Int): AudioRecord {
        val preferredSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        preferredSources.forEach { source ->
            val recorder = AudioRecord(
                source,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord inicializado com source=$source")
                return recorder
            }
            recorder.release()
        }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    private fun attachInputAudioEffects(recorder: AudioRecord) {
        val sessionId = recorder.audioSessionId
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null

        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.also { effect ->
                    effect.enabled = true
                    Log.i(TAG, "AcousticEchoCanceler habilitado para audioSessionId=$sessionId")
                }
            }.getOrElse {
                Log.w(TAG, "Falha ao habilitar AcousticEchoCanceler", it)
                null
            }
        } else {
            Log.i(TAG, "AcousticEchoCanceler indisponivel neste aparelho.")
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.also { effect ->
                    effect.enabled = true
                    Log.i(TAG, "NoiseSuppressor habilitado para audioSessionId=$sessionId")
                }
            }.getOrElse {
                Log.w(TAG, "Falha ao habilitar NoiseSuppressor", it)
                null
            }
        } else {
            Log.i(TAG, "NoiseSuppressor indisponivel neste aparelho.")
        }
    }

    private fun updateQueueCount() {
        val executor = transcriptionExecutor
        val queueSize = if (executor == null) {
            0
        } else {
            val pendingQueue = reconcileTranscriptionQueue(executor)
            pendingQueue + if (activeTranscriptionStartedAtEpochMs > 0L) 1 else 0
        }
        GatewayRuntime.update {
            it.copy(transcriptionQueueCount = queueSize)
        }
    }

    private fun reconcileTranscriptionQueue(executor: ThreadPoolExecutor): Int {
        val now = System.currentTimeMillis()
        val activeTranscriptionAgeMs = if (activeTranscriptionStartedAtEpochMs > 0L) {
            now - activeTranscriptionStartedAtEpochMs
        } else {
            0L
        }
        val clearBacklogBecauseActiveStalled =
            executor.activeCount > 0 && activeTranscriptionAgeMs >= ACTIVE_TRANSCRIPTION_STALL_BACKLOG_CLEAR_MS

        var droppedCount = 0
        val iterator = executor.queue.iterator()
        while (iterator.hasNext()) {
            val queuedTask = iterator.next() as? QueuedTranscriptionTask ?: continue
            val queuedAgeMs = now - queuedTask.enqueuedAtEpochMs
            if (clearBacklogBecauseActiveStalled || queuedAgeMs >= MAX_QUEUED_TRANSCRIPTION_AGE_MS) {
                iterator.remove()
                droppedCount += 1
            }
        }

        if (droppedCount > 0) {
            val reason = if (clearBacklogBecauseActiveStalled) {
                "processamento anterior lento"
            } else {
                "tempo de espera excedido"
            }
            Log.w(TAG, "Fila de transcricao descartou $droppedCount item(ns) por $reason.")
            GatewayRuntime.update {
                it.copy(
                    statusText = if (clearBacklogBecauseActiveStalled) {
                        "Fila limpou trecho preso atras de uma transcricao lenta."
                    } else {
                        "Fila limpou trecho expirado antes do envio."
                    }
                )
            }
        }

        return executor.queue.size
    }

    private class QueuedTranscriptionTask(
        val enqueuedAtEpochMs: Long,
        private val block: () -> Unit
    ) : Runnable {
        override fun run() {
            block()
        }
    }

    private fun releaseLocalWhisperEngine() {
        val engine = synchronized(this) {
            val existing = localWhisperEngine
            localWhisperEngine = null
            existing
        }

        runCatching { engine?.close() }
            .onFailure { error ->
                Log.w(TAG, "Falha ao liberar Whisper local: ${error.message}", error)
            }
    }

    private fun releaseLocalSherpaOnnxEngine() {
        val engine = synchronized(this) {
            val existing = localSherpaOnnxEngine
            localSherpaOnnxEngine = null
            existing
        }

        runCatching { engine?.close() }
            .onFailure { error ->
                Log.w(TAG, "Falha ao liberar sherpa-onnx local: ${error.message}", error)
            }
    }

    private fun updateSpectrum(spectrum: MutableList<Float>, rms: Float) {
        val normalized = (rms * SPECTRUM_GAIN).coerceIn(0.03f, 1f)
        spectrum.removeAt(0)
        spectrum.add(normalized)
    }

    private fun pushLimitedSample(buffer: ArrayDeque<Double>, value: Double, maxSize: Int) {
        if (buffer.size >= maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(value)
    }

    private fun estimateRecentSpectrumMotion(spectrum: List<Float>): Double {
        if (spectrum.size < 2) {
            return 0.0
        }
        val fromIndex = (spectrum.size - AMBIENT_SPECTRUM_DELTA_WINDOW).coerceAtLeast(1)
        var totalDelta = 0.0
        var count = 0
        for (index in fromIndex until spectrum.size) {
            totalDelta += abs(spectrum[index] - spectrum[index - 1]).toDouble()
            count += 1
        }
        return if (count == 0) 0.0 else totalDelta / count.toDouble()
    }

    private fun estimateNormalizedRmsVariance(rmsWindow: ArrayDeque<Double>): Double {
        if (rmsWindow.size < 2) {
            return 0.0
        }
        val mean = rmsWindow.average()
        if (mean <= 0.0001) {
            return 0.0
        }
        val variance = rmsWindow
            .map { sample ->
                val delta = sample - mean
                delta * delta
            }
            .average()
        val stdDev = sqrt(variance)
        return (stdDev / mean).coerceIn(0.0, 1.0)
    }

    private fun estimateAmbientStabilityScore(
        dynamicContrast: Double,
        rmsVariance: Double,
        spectrumMotion: Double,
        speechLikeFrameRaw: Boolean,
        settings: GatewaySettings
    ): Double {
        val lowDynamicScore = (1.0 - (dynamicContrast / settings.ambientDynamicContrastMax).coerceIn(0.0, 1.0))
        val lowVarianceScore = (1.0 - (rmsVariance / settings.ambientRmsVarianceMax).coerceIn(0.0, 1.0))
        val lowMotionScore = (1.0 - (spectrumMotion / settings.ambientSpectrumMotionMax).coerceIn(0.0, 1.0))
        val speechPenalty = if (speechLikeFrameRaw) settings.ambientSpeechPenalty else 0.0
        return ((lowDynamicScore * 0.35) + (lowVarianceScore * 0.35) + (lowMotionScore * 0.30) - speechPenalty)
            .coerceIn(0.0, 1.0)
    }

    private fun refreshNotification(contentText: String) {
        lastNotificationText = contentText
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    // ------------------------------------------------------------------
    // Verificacao de voz do usuario ("so a minha voz").
    //
    // Contrato de comportamento:
    //  - Cadastro: GatewayRuntime.requestSpeakerEnrollment(n) arma n slots.
    //    Cada segmento de fala finalizado consome um slot, vira amostra do
    //    perfil (embedding CAM++) e NAO segue para transcricao/OpenClaw.
    //  - Verificacao: com o recurso habilitado, perfil cadastrado e modelo
    //    presente, todo segmento e comparado (similaridade cosseno) com a
    //    media dos embeddings do perfil. Abaixo do limiar = descartado, com
    //    status na UI mostrando o score para ajuste fino.
    //  - Qualquer falha (modelo ausente, embedding curto, erro do runtime)
    //    DEIXA O SEGMENTO PASSAR: a verificacao e um filtro de conveniencia,
    //    nunca pode silenciar o gateway por erro interno.
    // ------------------------------------------------------------------

    private var speakerVoiceStore: SpeakerVoiceStore? = null
    private var speakerVerifier: SpeakerVerifier? = null

    private fun obtainSpeakerStore(): SpeakerVoiceStore =
        speakerVoiceStore ?: SpeakerVoiceStore(this).also { speakerVoiceStore = it }

    private fun obtainSpeakerVerifier(modelPath: String): SpeakerVerifier {
        speakerVerifier?.let { return it }
        return SpeakerVerifier(modelPath).also { speakerVerifier = it }
    }

    /** @return true = segmento segue para transcricao; false = consumido/rejeitado. */
    private fun evaluateSpeakerVoiceGate(pcmBytes: ByteArray, preRollPrefixBytes: Int = 0): Boolean {
        // Embedding e duracao avaliados sobre a FALA, sem o pre-roll de
        // ambiente que prefixa o segmento para a transcricao.
        val speechPcm = if (preRollPrefixBytes in 1 until pcmBytes.size) {
            pcmBytes.copyOfRange(preRollPrefixBytes, pcmBytes.size)
        } else {
            pcmBytes
        }
        val store = obtainSpeakerStore()
        if (!store.isModelReady()) {
            // Sem modelo nao ha verificacao nem cadastro possivel.
            if (GatewayRuntime.takeSpeakerEnrollSlot() > 0) {
                GatewayRuntime.cancelSpeakerEnrollment()
                GatewayRuntime.updateSpeakerVoice {
                    it.copy(status = "Baixe o modelo de voz antes de cadastrar.")
                }
            }
            return true
        }
        val verifier = obtainSpeakerVerifier(store.modelFile().absolutePath)

        // Modo cadastro: a fala vira amostra do perfil e nao e transcrita.
        val slotBefore = GatewayRuntime.takeSpeakerEnrollSlot()
        if (slotBefore > 0) {
            val embedding = verifier.embed(speechPcm, SAMPLE_RATE_HZ)
            if (embedding == null) {
                GatewayRuntime.updateSpeakerVoice {
                    it.copy(status = "Fala curta demais para o cadastro. Fale uma frase completa.")
                }
                // Devolve o slot para o usuario tentar de novo.
                GatewayRuntime.requestSpeakerEnrollment(slotBefore)
                return false
            }
            val total = store.addEmbedding(embedding)
            val remaining = slotBefore - 1
            GatewayRuntime.updateSpeakerVoice {
                it.copy(
                    sampleCount = total,
                    enrollRemaining = remaining,
                    status = if (remaining > 0) {
                        "Amostra registrada. Fale mais $remaining frase(s)."
                    } else {
                        "Perfil de voz aprendido ($total amostras)."
                    }
                )
            }
            Log.i(TAG, "Speaker enrollment: amostra registrada (total=$total, restam=$remaining).")
            return false
        }

        // Modo verificacao.
        val config = store.loadConfig()
        if (!config.enabled) {
            return true
        }
        val profile = store.meanEmbedding() ?: return true
        val embedding = verifier.embed(speechPcm, SAMPLE_RATE_HZ) ?: return true
        val score = SpeakerVerifier.cosineSimilarity(embedding, profile)
        GatewayRuntime.updateSpeakerVoice { it.copy(lastScore = score) }
        // Limiar adaptativo por duracao: embeddings de trechos curtos
        // ("bom dia", ~1s) sao menos confiaveis e pontuam mais baixo mesmo
        // sendo a voz certa (frases longas do dono ~0.63-0.78; um "bom dia"
        // do dono ~0.53). Trecho curto ganha desconto no limiar; vozes de
        // outras pessoas ficam tipicamente abaixo de 0.40, entao a margem
        // de seguranca se mantem.
        val durationMs = (speechPcm.size / 2) * 1000L / SAMPLE_RATE_HZ
        val effectiveThreshold = if (durationMs < SHORT_SEGMENT_FOR_SPEAKER_MS) {
            (config.threshold - SHORT_SEGMENT_THRESHOLD_DISCOUNT).coerceAtLeast(0.30)
        } else {
            config.threshold
        }
        // Faixa cinzenta: o dono falando LONGE do mic (ganho dinamico
        // oscilando) pontua 0.49-0.53 — abaixo do limiar mas bem acima de
        // outras vozes (<0.40). Rejeicao dura so abaixo de limiar-GRAY_ZONE;
        // na faixa cinzenta o segmento segue para transcricao e o PRE-AGENTE
        // do servidor decide com o score no metadata (speakerVerifiedScore,
        // labios, historico) — visto em campo: frase longa real descartada
        // aqui enquanto segmentos curtos de ruido passavam com o desconto.
        val hardRejectThreshold = (effectiveThreshold - SPEAKER_GRAY_ZONE).coerceAtLeast(0.30)
        if (score < hardRejectThreshold) {
            Log.i(TAG, "Segmento rejeitado pela verificacao de voz: score=${"%.3f".format(score)} < ${"%.2f".format(hardRejectThreshold)} (duracao=${durationMs}ms).")
            GatewayRuntime.updateSpeakerVoice {
                it.copy(status = "Voz nao reconhecida (score ${"%.2f".format(score)}). Segmento ignorado.")
            }
            GatewayRuntime.update {
                it.copy(statusText = "Fala ignorada: voz nao reconhecida (${"%.2f".format(score)}).")
            }
            return false
        }
        if (score < effectiveThreshold) {
            Log.i(TAG, "Verificacao de voz em faixa cinzenta: score=${"%.3f".format(score)} (limiar ${"%.2f".format(effectiveThreshold)}); servidor decide.")
        } else {
            Log.i(TAG, "Segmento aceito pela verificacao de voz: score=${"%.3f".format(score)}.")
        }
        return true
    }

    /**
     * True enquanto o usuario mantem o dedo indicador levantado (gesto de
     * "vou falar" sustentado). Usado para NAO finalizar a gravacao por
     * silencio: o dedo erguido e um pedido explicito de manter o microfone
     * aberto. O estado continuo vem do reconhecedor de gestos com timestamp
     * renovado a cada quadro; consideramos valido por ate
     * GESTURE_HOLD_VALIDITY_MS para tolerar a cadencia da camera.
     */
    private var lastIndexHoldLogAt = 0L

    private fun isIndexFingerHeld(now: Long): Boolean {
        val command = GatewayRuntime.gestureCommand().value ?: return false
        val held = command.gestureId == GestureCommandIds.INDEX_UP &&
            now - command.atEpochMs <= GESTURE_HOLD_VALIDITY_MS
        if (!held) {
            return false
        }
        // Limite de seguranca: ninguem segura o indicador por minutos — um
        // "hold" muito longo e falso positivo do reconhecedor e NAO pode
        // bloquear o corte por silencio para sempre (segura a gravacao
        // aberta e o commit/despacho ao OpenClaw nunca acontece).
        if (now - command.sinceEpochMs > INDEX_HOLD_MAX_MS) {
            if (now - lastIndexHoldLogAt >= 5_000L) {
                lastIndexHoldLogAt = now
                Log.w(TAG, "Indicador 'mantido' ha mais de ${INDEX_HOLD_MAX_MS / 1000}s: ignorando como falso positivo.")
            }
            return false
        }
        if (now - lastIndexHoldLogAt >= 5_000L) {
            lastIndexHoldLogAt = now
            Log.i(TAG, "Corte por silencio adiado: indicador mantido levantado.")
        }
        return true
    }

    /**
     * Marca "enderecamento direto ao assistente" agora: abre a janela de
     * follow-up do VoiceChannelSkill como se o usuario tivesse chamado pelo
     * termo de wake no texto. Usado pelos sinais explicitos de intencao:
     * gesto de indicador/apontar, palavra de ativacao falada e texto
     * digitado. Sem isso, com o microfone sempre aberto, toda fala vira
     * "ambient_conversation" e o pre-agente do servidor retem o trecho
     * aguardando confirmacao de contexto.
     *
     * A janela usa no minimo DIRECT_ADDRESS_MIN_WINDOW_SECS: entre o gesto e
     * o despacho existem transcricao + janela de acumulacao, e a janela
     * curta padrao podia expirar antes do evaluate.
     */
    private fun markDirectAddressNow() {
        val now = System.currentTimeMillis()
        val followUpSeconds = runCatching { loadCurrentSettings().voiceChannelFollowUpSeconds }
            .getOrDefault(12)
            .coerceAtLeast(DIRECT_ADDRESS_MIN_WINDOW_SECS)
        lastDirectAddressToOpenClawEpochMs = now
        assistantConversationUntilEpochMs = now + followUpSeconds * 1000L
        Log.i(TAG, "Enderecamento direto marcado (janela=${followUpSeconds}s).")
    }

    private fun isWakeWordStandbyAvailable(): Boolean {
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        val config = store.loadConfig()
        return config.enabled && store.sampleCount() > 0
    }

    private fun syncWakeWordConfig() {
        val version = GatewayRuntime.wakeWordConfigVersion().value
        if (version == wakeWordConfigVersionSeen) {
            return
        }
        wakeWordConfigVersionSeen = version
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        var config = store.loadConfig()
        val samples = store.loadSamples()
        var validTemplates = wakeWordDetector.configure(samples, config.threshold)
        if (config.autoThreshold) {
            val suggestion = wakeWordDetector.suggestedThreshold()
            if (suggestion != null && kotlin.math.abs(suggestion - config.threshold) > 0.05) {
                config = config.copy(threshold = suggestion)
                store.saveConfig(config)
                validTemplates = wakeWordDetector.configure(samples, config.threshold)
                Log.i(TAG, "Wake word limiar automatico: ${"%.2f".format(suggestion)}")
                // Reflete o novo limiar na UI de configuracao.
                GatewayRuntime.bumpWakeWordConfigVersion()
            }
        }
        wakeWordEnabled = config.enabled && validTemplates > 0
        GatewayRuntime.updateWakeWord {
            it.copy(
                enabled = config.enabled,
                threshold = config.threshold,
                sampleCount = samples.size,
                status = when {
                    !config.enabled -> "Palavra de ativacao desativada."
                    samples.isEmpty() -> "Grave ao menos uma amostra da palavra."
                    validTemplates == 0 -> "Nenhuma amostra valida. Regrave em ambiente silencioso."
                    else -> "Escutando pela palavra de ativacao ($validTemplates amostras validas)."
                }
            )
        }
        Log.i(
            TAG,
            "Wake word config: enabled=${config.enabled} samples=${samples.size} " +
                "validTemplates=$validTemplates threshold=${config.threshold}"
        )
    }

    private fun handleWakeWordAudio(
        buffer: ShortArray,
        readCount: Int,
        now: Long,
        settings: GatewaySettings
    ) {
        syncWakeWordConfig()

        if (GatewayRuntime.takeWakeWordRecordingRequest()) {
            wakeWordRecordBuffer = ShortArray(WAKE_WORD_RECORD_SAMPLES)
            wakeWordRecordFill = 0
            GatewayRuntime.updateWakeWord {
                it.copy(recording = true, status = "Gravando amostra... fale a palavra agora.")
            }
        }
        val recordBuffer = wakeWordRecordBuffer
        if (recordBuffer != null) {
            val toCopy = minOf(readCount, recordBuffer.size - wakeWordRecordFill)
            System.arraycopy(buffer, 0, recordBuffer, wakeWordRecordFill, toCopy)
            wakeWordRecordFill += toCopy
            if (wakeWordRecordFill >= recordBuffer.size) {
                wakeWordRecordBuffer = null
                finishWakeWordRecording(recordBuffer)
            }
            return
        }

        if (!wakeWordEnabled || !wakeWordDetector.hasTemplates) {
            return
        }
        val result = wakeWordDetector.feed(buffer, readCount, now)
        if (result.distance != null) {
            GatewayRuntime.updateWakeWord { it.copy(lastDistance = result.distance) }
        }
        if (now - lastWakeWordDiagnosticLogAt >= WAKE_WORD_DIAGNOSTIC_LOG_INTERVAL_MS) {
            lastWakeWordDiagnosticLogAt = now
            val chunkRms = calculateRms(buffer, readCount)
            Log.i(
                TAG,
                "Wake word check: dist=${result.distance?.let { "%.2f".format(it) } ?: "sem-energia"} " +
                    "rms=${"%.4f".format(chunkRms)} matched=${result.matched}"
            )
        }
        if (result.matched) {
            // A palavra de ativacao serve para ACORDAR/retomar:
            //  - standby (escuta parada): retoma o microfone;
            //  - gate do gesto fechado: abre o microfone;
            //  - TELA APAGADA: acende a tela (acordar o aparelho), mesmo com
            //    a escuta ja ativa — e o caso "chuchu" com o telefone dormindo.
            // Somente com a escuta ativa E a tela acesa o "chuchu" no meio da
            // conversa e ignorado (nao interfere no papo em andamento).
            val gateBlocked = settings.cameraGestureEnabled && !cameraGestureGateOpen
            val screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false
            if (!standbyMode && !gateBlocked && !screenOff) {
                Log.i(
                    TAG,
                    "Wake word ignorada: escuta ativa com tela acesa (dist=${"%.2f".format(result.distance)})."
                )
                return
            }
            Log.i(TAG, "Wake word detectada (dist=${"%.2f".format(result.distance)}).")
            GatewayRuntime.updateWakeWord {
                it.copy(lastMatchAtEpochMs = now, status = "Palavra detectada! Abrindo microfone.")
            }
            if (standbyMode) {
                standbyMode = false
                GatewayRuntime.setListening(
                    active = true,
                    statusText = "Palavra detectada. Microfone retomado."
                )
                refreshNotification("Palavra detectada. Microfone retomado.")
            }
            // Chamar pela palavra de ativacao = enderecamento direto: a fala
            // seguinte e para o assistente.
            markDirectAddressNow()
            cameraGestureGateOpen = true
            GatewayRuntime.setCameraGestureGateOpen(true)
            GatewayRuntime.setCameraGestureStatus("Palavra de ativacao detectada. Abrindo microfone.")
            requestScreenAttention(settings)
        }
    }

    private fun finishWakeWordRecording(samples: ShortArray) {
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        val saved = store.saveSample(samples)
        GatewayRuntime.updateWakeWord {
            it.copy(
                recording = false,
                status = if (saved) "Amostra gravada." else "Falha ao salvar amostra."
            )
        }
        if (saved) {
            GatewayRuntime.bumpWakeWordConfigVersion()
        }
    }

    /**
     * Garante a tela acesa enquanto o assistente fala, para os gestos de
     * comando (camera) continuarem disponiveis — em especial a mao aberta
     * que interrompe a resposta. Sem isso, tela apagada = camera parada =
     * usuario sem canal de interrupcao (o microfone fica suprimido durante
     * a fala do assistente).
     */
    private fun wakeScreenForAssistantSpeech() {
        val settings = runCatching { normalizeRuntimeSettings(loadCurrentSettings()) }.getOrNull() ?: return
        if (settings.screenMode == com.sufficit.ai.gateway.config.ScreenMode.ALWAYS_OFF) {
            return
        }
        GatewayRuntime.requestScreenAttention(ASSISTANT_SPEECH_SCREEN_HOLD_MS)
        wakeDevice(ASSISTANT_SPEECH_SCREEN_HOLD_MS)
    }

    private fun requestScreenAttention(settings: GatewaySettings) {
        when (settings.screenMode) {
            com.sufficit.ai.gateway.config.ScreenMode.ALWAYS_OFF -> return
            com.sufficit.ai.gateway.config.ScreenMode.ALWAYS_ON -> {
                val holdMs = settings.screenHoldSeconds * 1000L
                GatewayRuntime.requestScreenAttention(holdMs)
                wakeDevice(holdMs)
            }
            com.sufficit.ai.gateway.config.ScreenMode.ACTIVITY -> {
                val holdMs = settings.screenHoldSeconds * 1000L
                GatewayRuntime.requestScreenAttention(holdMs)
                wakeDevice(holdMs)
            }
        }
    }

    private fun wakeDevice(holdMs: Long) {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:screen-attention"
        )
        wakeLock.acquire(holdMs.coerceAtLeast(1_000L))
    }

    private fun calculateRms(buffer: ShortArray, readCount: Int): Double {
        var sum = 0.0
        for (index in 0 until readCount) {
            val sample = buffer[index] / Short.MAX_VALUE.toDouble()
            sum += sample * sample
        }

        return sqrt(sum / readCount.coerceAtLeast(1))
    }

    private fun calculatePeak(buffer: ShortArray, readCount: Int): Int {
        var peak = 0
        for (index in 0 until readCount) {
            peak = maxOf(peak, abs(buffer[index].toInt()))
        }

        return peak
    }

    private fun calculateZeroCrossingRate(buffer: ShortArray, readCount: Int): Double {
        if (readCount <= 1) {
            return 0.0
        }
        var crossings = 0
        var previous = buffer[0].toInt()
        for (index in 1 until readCount) {
            val current = buffer[index].toInt()
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) {
                crossings += 1
            }
            previous = current
        }
        return crossings.toDouble() / (readCount - 1).toDouble()
    }

    private fun isSpeechLikeFrame(
        rms: Double,
        peakNormalized: Double,
        zeroCrossingRate: Double,
        vadThreshold: Double,
        noiseFloorRms: Double,
        transcriptionMode: TranscriptionMode,
        settings: GatewaySettings
    ): Boolean {
        val isRemote = transcriptionMode == TranscriptionMode.REMOTE
        // Remote mode uses slightly looser thresholds (Whisper API tolerates more noise)
        val noiseGateMult = if (isRemote) settings.noiseGateMultiplier * 0.806 else settings.noiseGateMultiplier
        val minRms = if (isRemote) settings.minSpeechRms * 0.8 else settings.minSpeechRms
        val minPeak = if (isRemote) settings.minSpeechPeakNormalized * 0.686 else settings.minSpeechPeakNormalized
        val maxCrest = if (isRemote) settings.maxTransientCrestFactor * 1.241 else settings.maxTransientCrestFactor
        val adaptiveThreshold = maxOf(vadThreshold, noiseFloorRms * noiseGateMult, minRms)
        if (rms < adaptiveThreshold) {
            return false
        }

        val crestFactor = peakNormalized / rms.coerceAtLeast(0.0001)
        val speechBandZcr = zeroCrossingRate in settings.minZeroCrossingRate..settings.maxZeroCrossingRate
        val notImpulse = crestFactor <= maxCrest
        val enoughBody = peakNormalized >= minPeak

        return speechBandZcr && notImpulse && enoughBody
    }

    private fun resolveAutomaticMicrophoneGain(
        peakGain: Double,
        noiseFloorRms: Double,
        speechLikeFrame: Boolean,
        speechActive: Boolean,
        inputPeakNormalized: Double,
        settings: GatewaySettings
    ): Double {
        val minGain = settings.ambientGainMinGain
        val maxGain = peakGain.coerceAtLeast(minGain)

        // Teto de ganho que mantem o PICO de entrada no alvo (headroom abaixo
        // do joelho do soft-clip em 0.85). Acima deste pico o ganho cai abaixo
        // de 1.0 — e o que impede musica/fala alta de saturar e estourar o
        // espectro. Sinal quase-silencioso nao tem teto util: usa o ganho
        // cheio para captar fala distante.
        val ceilToTarget = if (inputPeakNormalized > MIN_PEAK_FOR_AGC) {
            (TARGET_PEAK_NORMALIZED / inputPeakNormalized).coerceIn(minGain, maxGain)
        } else {
            maxGain
        }

        if (speechLikeFrame || speechActive) {
            // AGC: normaliza a fala ao alvo. Antes retornava peakGain cego — o
            // que fazia musica (transientes que parecem fala) ir a 2.4x e
            // saturar. Agora amplifica fala fraca ate o alvo e ABAIXA o ganho
            // quando o sinal ja chega forte.
            return ceilToTarget
        }

        val backgroundGain = when {
            noiseFloorRms >= 0.030 -> peakGain * 0.22
            noiseFloorRms >= 0.020 -> peakGain * 0.28
            noiseFloorRms >= 0.012 -> peakGain * 0.34
            else -> peakGain * 0.42
        }

        // Fundo: reducao por ruido E pelo teto de pico (o que for menor).
        return backgroundGain
            .coerceIn(minGain, maxGain)
            .coerceAtMost(ceilToTarget)
    }

    private fun segmentLooksLikeSpeech(
        pcmBytes: ByteArray,
        settings: GatewaySettings
    ): Boolean {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount < 512) {
            return false
        }

        val samples = ShortArray(sampleCount)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            samples[index] = byteBuffer.short
        }

        val segmentRms = calculateRms(samples, samples.size)
        val segmentPeakNormalized = calculatePeak(samples, samples.size).toDouble() / Short.MAX_VALUE.toDouble()
        val frameSize = 512
        val hopSize = 256
        var speechLikeFrames = 0
        var totalFrames = 0
        var offset = 0
        while (offset + frameSize <= samples.size) {
            val frame = ShortArray(frameSize)
            samples.copyInto(frame, 0, offset, offset + frameSize)
            val frameRms = calculateRms(frame, frame.size)
            val framePeakNormalized = calculatePeak(frame, frame.size).toDouble() / Short.MAX_VALUE.toDouble()
            val frameZeroCrossingRate = calculateZeroCrossingRate(frame, frame.size)
            if (
                isSpeechLikeFrame(
                    rms = frameRms,
                    peakNormalized = framePeakNormalized,
                    zeroCrossingRate = frameZeroCrossingRate,
                    vadThreshold = settings.vadThreshold,
                    noiseFloorRms = 0.0,
                    transcriptionMode = settings.transcriptionMode,
                    settings = settings
                )
            ) {
                speechLikeFrames += 1
            }
            totalFrames += 1
            offset += hopSize
        }

        if (totalFrames == 0) {
            return false
        }

        val speechLikeRatio = speechLikeFrames.toDouble() / totalFrames.toDouble()
        val localVoiceAnalysis = LocalVoiceAnalyzer.analyzePcm16(
            pcmBytes = pcmBytes,
            sampleRate = SAMPLE_RATE_HZ
        )
        val voiceSignature = localVoiceAnalysis.voiceSignature
        val minSegmentRms = when (settings.transcriptionMode) {
            TranscriptionMode.REMOTE -> 0.012
            TranscriptionMode.LOCAL -> 0.015
        }
        val hasVoicedSignature = voiceSignature != null &&
            voiceSignature.voicedRatio >= 0.10 &&
            voiceSignature.energyMean >= 0.015 &&
            voiceSignature.pitchMeanHz != null

        return segmentRms >= minSegmentRms &&
            segmentPeakNormalized >= settings.minSpeechPeakNormalized &&
            speechLikeFrames >= settings.minSpeechCandidateFrames &&
            speechLikeRatio >= 0.18 &&
            hasVoicedSignature
    }

    private fun applyMicrophoneGain(buffer: ShortArray, readCount: Int, gain: Double) {
        if (gain == 1.0) {
            return
        }

        for (index in 0 until readCount) {
            buffer[index] = softClipToShort(buffer[index] * gain)
        }
    }

    /**
     * Limitador suave: linear ate o joelho (85% da escala), compressao tanh
     * acima. Evita o serrilhado do clipping duro quando o ganho automatico
     * amplifica picos de fala alta — clipping duro gera harmonicos que
     * atrapalham transcricao e os detectores baseados em espectro.
     */
    private fun softClipToShort(value: Double): Short {
        val limit = Short.MAX_VALUE.toDouble()
        val knee = limit * 0.85
        val magnitude = abs(value)
        if (magnitude <= knee) {
            return value.toInt().toShort()
        }
        val excess = (magnitude - knee) / (limit - knee)
        val compressed = knee + (limit - knee) * kotlin.math.tanh(excess)
        val limited = if (value >= 0) compressed else -compressed
        return limited
            .coerceIn(Short.MIN_VALUE.toDouble(), limit)
            .toInt()
            .toShort()
    }

    // ------------------------------------------------------------------
    // Pre-roll: anel dos ultimos PRE_ROLL_MS de audio pos-ganho. O segmento
    // de fala so ABRE depois de minimumSpeechCandidateFrames chunks
    // consecutivos de fala — sem o pre-roll, esses chunks de deteccao (o
    // comeco da frase, ex.: "consegue" de "consegue me ouvir") eram jogados
    // fora e a transcricao chegava cortada ("...me ouvir"). Ao abrir o
    // segmento, o anel inteiro e prefixado no buffer de fala.
    // Tocado APENAS pela thread de captura.
    // ------------------------------------------------------------------
    private val preRollChunks = ArrayDeque<ByteArray>()
    private var preRollByteCount = 0

    private fun pushPreRollChunk(buffer: ShortArray, readCount: Int) {
        val bytes = ByteBuffer.allocate(readCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until readCount) {
            bytes.putShort(buffer[index])
        }
        preRollChunks.addLast(bytes.array())
        preRollByteCount += readCount * 2
        while (preRollByteCount > PRE_ROLL_MAX_BYTES && preRollChunks.isNotEmpty()) {
            preRollByteCount -= preRollChunks.removeFirst().size
        }
    }

    // Audio que nao pode prefixar segmento nenhum (fala do assistente/eco de
    // TTS, standby, gate fechado, segmento ja enviado): anel zerado.
    private fun clearPreRoll() {
        preRollChunks.clear()
        preRollByteCount = 0
    }

    /**
     * Prefixa o pre-roll no buffer do segmento recem-aberto e retorna os
     * BYTES escritos (o chamador recua o captureStartedAt e repassa o
     * tamanho do prefixo ate a verificacao de locutor, que avalia o audio
     * SEM ele). EXCLUI o ultimo chunk do anel: e o chunk atual, que o fluxo
     * normal ja anexa via appendPcm16.
     */
    private fun drainPreRollInto(output: ByteArrayOutputStream): Int {
        var written = 0
        while (preRollChunks.size > 1) {
            val chunk = preRollChunks.removeFirst()
            preRollByteCount -= chunk.size
            output.write(chunk)
            written += chunk.size
        }
        return written
    }

    private fun appendPcm16(output: ByteArrayOutputStream, buffer: ShortArray, readCount: Int) {
        val bytes = ByteBuffer.allocate(readCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until readCount) {
            bytes.putShort(buffer[index])
        }
        output.write(bytes.array())
    }

    private fun mergeCurrentTranscript(
        current: String,
        incoming: String
    ): String {
        val existing = current.trim()
        val fresh = incoming.trim()
        if (fresh.isBlank()) {
            return existing
        }
        if (existing.isBlank()) {
            return fresh.takeLast(MAX_TRANSCRIPT_CHARS)
        }

        val normalizedExisting = normalizeTranscriptForMatch(existing)
        val normalizedFresh = normalizeTranscriptForMatch(fresh)

        if (normalizedExisting == normalizedFresh) {
            return existing.takeLast(MAX_TRANSCRIPT_CHARS)
        }

        val existingWords = existing.splitWhitespace()
        val freshWords = fresh.splitWhitespace()
        val maxOverlap = minOf(existingWords.size, freshWords.size, MAX_WORD_OVERLAP)

        for (overlap in maxOverlap downTo 2) {
            val suffix = existingWords.takeLast(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            val prefix = freshWords.take(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            if (suffix == prefix) {
                val merged = buildString {
                    append(existing)
                    append(' ')
                    append(freshWords.drop(overlap).joinToString(" "))
                }.trim()
                return merged.takeLast(MAX_TRANSCRIPT_CHARS)
            }
        }

        return "$existing $fresh".trim().takeLast(MAX_TRANSCRIPT_CHARS)
    }

    private fun shouldAdvanceTranscriptWindow(
        current: String,
        incoming: String,
        phraseAdvanceReady: Boolean
    ): Boolean {
        val existing = current.trim()
        val fresh = incoming.trim()
        if (existing.isBlank() || fresh.isBlank()) {
            return false
        }
        if (phraseAdvanceReady) {
            return true
        }

        val normalizedExisting = normalizeTranscriptForMatch(existing)
        val normalizedFresh = normalizeTranscriptForMatch(fresh)
        if (normalizedExisting.isBlank() || normalizedFresh.isBlank()) {
            return false
        }
        if (
            normalizedExisting.contains(normalizedFresh) ||
            normalizedFresh.contains(normalizedExisting)
        ) {
            return false
        }

        val existingWords = existing.splitWhitespace()
        val freshWords = fresh.splitWhitespace()
        val maxOverlap = minOf(existingWords.size, freshWords.size, MAX_WORD_OVERLAP)
        for (overlap in maxOverlap downTo 2) {
            val suffix = existingWords.takeLast(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            val prefix = freshWords.take(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            if (suffix == prefix) {
                return false
            }
        }

        return freshWords.size >= 2
    }

    private fun buildTranscriptionPrompt(settings: GatewaySettings): String {
        val terms = settings.transcriptionTerms
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val replacements = parseTranscriptionDictionary(settings.transcriptionDictionary)

        if (terms.isEmpty() && replacements.isEmpty()) {
            return ""
        }

        return buildString {
            append("Use portugues do Brasil e priorize estes termos da empresa. ")
            append("Interprete variantes coloquiais e sotaques brasileiros de forma canonica, por exemplo ")
            append("\"intendi\" como \"entendi\", sem inventar palavras.")
            if (terms.isNotEmpty()) {
                append(" Termos preferidos: ")
                append(terms.joinToString(", "))
                append('.')
            }
            if (replacements.isNotEmpty()) {
                append(" Correcos desejadas: ")
                append(
                    replacements.joinToString("; ") { (wrong, right) ->
                        "\"$wrong\" -> \"$right\""
                    }
                )
                append('.')
            }
        }
    }

    private fun applyTranscriptionDictionary(text: String, settings: GatewaySettings): String {
        var corrected = applySafePortugueseColloquialNormalization(
            text = text.trim(),
            strength = settings.colloquialNormalizationStrength
        )
        if (corrected.isBlank()) {
            return corrected
        }

        parseTranscriptionDictionary(settings.transcriptionDictionary).forEach { (wrong, right) ->
            val normalizedWrong = wrong.trim()
            val normalizedRight = right.trim()
            if (normalizedWrong.isBlank() || normalizedRight.isBlank()) {
                return@forEach
            }

            val pattern = Regex("\\b${Regex.escape(normalizedWrong)}\\b", RegexOption.IGNORE_CASE)
            corrected = pattern.replace(corrected, normalizedRight)
        }

        corrected = removeImprobableIsolatedWords(
            text = corrected,
            settings = settings,
            strength = settings.colloquialNormalizationStrength
        )

        corrected = sanitizeImplausibleShortTranscript(
            text = corrected,
            settings = settings
        )

        return corrected.replace(Regex("\\s+"), " ").trim()
    }

    private fun applySafePortugueseColloquialNormalization(text: String, strength: Double): String {
        // Pipeline legado local. As regras seguras agora vivem em assets/colloquial-normalization-safe.txt.
        return text.trim().replace(Regex("\\s+"), " ").trim()
    }





















    private fun removeImprobableIsolatedWords(
        text: String,
        settings: GatewaySettings,
        strength: Double
    ): String {
        if (strength < 0.34) {
            return text
        }

        val tokens = text.splitWhitespace()
        if (tokens.size < 4) {
            return text
        }

        val knownWords = buildKnownWordAllowList(settings)
        val filtered = tokens.filterIndexed { index, token ->
            val normalizedToken = normalizeTokenForLexicalCheck(token)
            if (!isImprobableIsolatedWordCandidate(normalizedToken, knownWords)) {
                return@filterIndexed true
            }

            val previous = tokens.getOrNull(index - 1)?.let(::normalizeTokenForLexicalCheck).orEmpty()
            val next = tokens.getOrNull(index + 1)?.let(::normalizeTokenForLexicalCheck).orEmpty()
            val surroundedByCommonWords = previous in COMMON_PORTUGUESE_CONNECTORS &&
                next in COMMON_PORTUGUESE_CONNECTORS

            !surroundedByCommonWords
        }

        return filtered.joinToString(" ")
    }

    private fun sanitizeImplausibleShortTranscript(
        text: String,
        settings: GatewaySettings
    ): String {
        val tokens = text.splitWhitespace()
        if (tokens.isEmpty() || tokens.size > 2) {
            return text
        }

        val knownWords = buildKnownWordAllowList(settings)
        val hasHyphenatedUnknown = tokens.any { token ->
            '-' in token &&
                normalizeTokenForLexicalCheck(token).let { normalized ->
                    normalized.isNotBlank() &&
                        normalized !in knownWords &&
                        isImprobableIsolatedWordCandidate(normalized, knownWords)
                }
        }

        if (hasHyphenatedUnknown) {
            Log.i(TAG, "Transcricao curta descartada por token hifenizado improvavel: $text")
            return ""
        }

        return text
    }

    private fun buildKnownWordAllowList(settings: GatewaySettings): Set<String> {
        val preferredTerms = settings.transcriptionTerms
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { it.splitWhitespace().asSequence() }
            .map { normalizeTokenForLexicalCheck(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val dictionaryWords = parseTranscriptionDictionary(settings.transcriptionDictionary)
            .flatMap { (wrong, right) ->
                (wrong.splitWhitespace() + right.splitWhitespace())
                    .map { normalizeTokenForLexicalCheck(it) }
            }
            .filter { it.isNotBlank() }
            .toSet()

        return preferredTerms + dictionaryWords + COMMON_PORTUGUESE_CONNECTORS
    }

    private fun isImprobableIsolatedWordCandidate(
        normalizedToken: String,
        knownWords: Set<String>
    ): Boolean {
        if (normalizedToken.length < 6) {
            return false
        }
        if (!normalizedToken.all { it.isLetter() }) {
            return false
        }
        if (normalizedToken in knownWords) {
            return false
        }
        if (normalizedToken.any { it in "Ã¡Ã Ã¢Ã£Ã©ÃªÃ­Ã³Ã´ÃµÃºÃ§" }) {
            return false
        }
        return true
    }

    private fun normalizeTokenForLexicalCheck(token: String): String {
        return token
            .lowercase()
            .replace(Regex("[^\\p{L}]"), "")
            .trim()
    }

    private fun applyPortugueseColloquialNormalization(text: String): String {
        // Metodo legado mantido temporariamente apenas para compatibilidade local.
        // A fonte de verdade das regras de normalizacao agora fica em assets/colloquial-normalization-safe.txt.
        return text.trim().replace(Regex("\\s+"), " ").trim()
    }























    private fun shouldIgnoreAmbientTranscript(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return false
        }

        val normalized = trimmed
            .lowercase()
            .replace("[", " ")
            .replace("]", " ")
            .replace("(", " ")
            .replace(")", " ")
            .replace("â™ª", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) {
            return true
        }

        val ambientPatterns = listOf(
            "musica",
            "mÃºsica",
            "music",
            "tocando musica",
            "tocando mÃºsica",
            "musica ao fundo",
            "mÃºsica ao fundo",
            "som ambiente",
            "audio ambiente",
            "Ã¡udio ambiente",
            "aplausos",
            "aplauso",
            "ruido",
            "ruÃ­do",
            "barulho",
            "instrumental"
        )

        if (ambientPatterns.any { normalized == it }) {
            return true
        }

        val tokens = normalized.splitWhitespace()
        if (tokens.isEmpty()) {
            return true
        }

        val ambientVocabulary = setOf(
            "musica",
            "mÃºsica",
            "music",
            "tocando",
            "fundo",
            "som",
            "ambiente",
            "audio",
            "Ã¡udio",
            "aplausos",
            "aplauso",
            "ruido",
            "ruÃ­do",
            "barulho",
            "instrumental"
        )

        return tokens.all { it in ambientVocabulary }
    }

    private fun parseTranscriptionDictionary(raw: String): List<Pair<String, String>> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                when {
                    "=>" in line -> line.split("=>", limit = 2)
                    "->" in line -> line.split("->", limit = 2)
                    "=" in line -> line.split("=", limit = 2)
                    else -> null
                }?.let { parts ->
                    val wrong = parts.getOrNull(0)?.trim().orEmpty()
                    val right = parts.getOrNull(1)?.trim().orEmpty()
                    if (wrong.isBlank() || right.isBlank()) null else wrong to right
                }
            }
            .toList()
    }

    private fun commitCurrentTranscriptToPrevious() {
        val snapshot = GatewayRuntime.state().value
        val hadContent = snapshot.currentTranscript.trim().isNotBlank()
        appendTranscriptHistory(
            phrase = snapshot.currentTranscript,
            state = snapshot,
            immediate = true
        )
        GatewayRuntime.update {
            val current = it.currentTranscript.trim()
            if (current.isBlank()) {
                it
            } else {
                it.copy(
                    currentTranscript = "",
                    previousTranscript = current,
                    recentTranscripts = pushRecentTranscript(it.recentTranscripts, current),
                    statusText = if (it.listening) {
                        "Frase concluida. Aguardando nova fala."
                    } else {
                        it.statusText
                    }
                )
            }
        }
        if (hadContent) {
            lastTranscriptCommittedAtEpochMs = System.currentTimeMillis()
        }
    }

    private fun appendTranscriptHistory(
        phrase: String,
        state: GatewayUiState,
        immediate: Boolean = false
    ) {
        val normalizedPhrase = phrase.trim()
        if (normalizedPhrase.isBlank()) {
            return
        }

        runCatching {
            TranscriptHistoryLogger.append(
                context = this,
                entry = TranscriptHistoryEntry(
                    occurredAt = Instant.now(),
                    backend = state.transcriptionBackendLabel.trim(),
                    model = state.transcriptionModelLabel.trim(),
                    gender = state.lastGender?.trim()?.ifBlank { null },
                    emotion = state.lastEmotion?.trim()?.ifBlank { null },
                    sameSpeakerProbability = state.sameSpeakerProbability,
                    voiceLearningProgress = state.voiceLearningProgress,
                    phrase = if (state.multipleVoicesLikely) {
                        "[multi-voice] $normalizedPhrase"
                    } else {
                        normalizedPhrase
                    }
                )
            )
        }.onFailure { ex ->
            Log.w(TAG, "Falha ao registrar historico de transcricao", ex)
        }

        scheduleTranscriptDispatchToOpenClaw(
            phrase = normalizedPhrase,
            state = state,
            immediate = immediate
        )
    }

    private fun scheduleTranscriptDispatchToOpenClaw(
        phrase: String,
        state: GatewayUiState,
        immediate: Boolean = false
    ) {
        val normalizedPhrase = phrase.trim()
        if (normalizedPhrase.isBlank()) {
            return
        }

        // Bolha do usuario no chat POR FRASE finalizada, aqui na entrada do
        // agendador — nao no despacho: o despacho acumula varias frases na
        // janela de envio e juntava tudo num unico balao com atraso.
        if (!TranscriptTextPipeline.isNeutralMarkerTranscript(normalizedPhrase)) {
            GatewayRuntime.appendChatMessage(ChatRole.USER, normalizedPhrase)
        }

        val generation = pendingOpenClawDispatchGeneration.incrementAndGet()
        synchronized(pendingDispatchLock) {
            pendingOpenClawDispatchText = mergePendingDispatchText(
                existing = pendingOpenClawDispatchText,
                incoming = normalizedPhrase
            )
            pendingOpenClawDispatchState = state
        }
        updateOpenClawDispatchQueueCount()

        val executor = openClawExecutor ?: return
        executor.execute {
            if (!immediate) {
                try {
                    Thread.sleep(openClawAccumulationWindowMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }

            if (pendingOpenClawDispatchGeneration.get() != generation) {
                return@execute
            }

            val dispatchText: String
            val dispatchState: GatewayUiState
            synchronized(pendingDispatchLock) {
                dispatchText = pendingOpenClawDispatchText.trim()
                dispatchState = pendingOpenClawDispatchState ?: return@execute
                pendingOpenClawDispatchText = ""
                pendingOpenClawDispatchState = null
            }
            activeOpenClawDispatchStartedAtEpochMs = if (dispatchText.isBlank()) 0L else System.currentTimeMillis()
            updateOpenClawDispatchQueueCount()

            if (dispatchText.isBlank()) {
                return@execute
            }

            dispatchTranscriptToOpenClaw(
                phrase = dispatchText,
                state = dispatchState
            )
        }
    }

    private fun mergePendingDispatchText(
        existing: String,
        incoming: String
    ): String {
        val current = existing.trim()
        val fresh = incoming.trim()
        if (current.isBlank()) {
            return fresh
        }
        if (fresh.isBlank()) {
            return current
        }
        if (current.equals(fresh, ignoreCase = true)) {
            return current
        }
        return "$current $fresh"
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun runOpenClawHandshakeIfNeeded() {
        if (!selfTestExecuted.compareAndSet(false, true)) {
            return
        }
        val executor = openClawExecutor ?: return
        executor.execute {
            val store = settingsStore ?: GatewaySettingsStore(this)
            val settings = runCatching { store.load() }.getOrElse {
                Log.w(TAG, "Falha ao carregar configuracao para handshake OpenClaw", it)
                return@execute
            }
            if (
                settings.openClawGatewayUrl.isBlank() ||
                settings.openClawGatewayToken.isBlank() ||
                settings.openClawDeviceToken.isBlank() ||
                settings.openClawSessionKey.isBlank()
            ) {
                GatewayRuntime.update {
                    it.copy(openClawStatus = "OpenClaw desativado na configuracao.")
                }
                return@execute
            }

                GatewayRuntime.update {
                    it.copy(openClawStatus = "Validando websocket OpenClaw...")
                }
            try {
                val config = buildOpenClawConfig(settings)
                persistentOpenClawConnection?.connect(config)
                Log.i(TAG, "Handshake OpenClaw iniciado em modo persistente.")
            } catch (ex: Exception) {
                Log.e(TAG, "Falha no handshake OpenClaw", ex)
                GatewayRuntime.update {
                    it.copy(openClawStatus = "Falha OpenClaw: ${ex.message ?: ex.javaClass.simpleName}")
                }
            }
        }
    }

    private fun pushRecentTranscript(current: List<String>, phrase: String): List<String> {
        val normalized = phrase.trim()
        if (normalized.isBlank()) {
            return current
        }
        return buildList {
            add(normalized)
            current.forEach { existing ->
                val trimmed = existing.trim()
                if (trimmed.isNotBlank() && !trimmed.equals(normalized, ignoreCase = true)) {
                    add(trimmed)
                }
            }
        }.take(4)
    }

    private fun updateOpenClawDispatchQueueCount() {
        val hasBufferedDispatch = synchronized(pendingDispatchLock) {
            pendingOpenClawDispatchText.isNotBlank()
        }
        val queueSize = (if (hasBufferedDispatch) 1 else 0) +
            (if (activeOpenClawDispatchStartedAtEpochMs > 0L) 1 else 0)
        GatewayRuntime.update {
            it.copy(openClawDispatchQueueCount = queueSize.coerceAtMost(1))
        }
    }

    private fun setAssistantProcessing(active: Boolean, label: String = "") {
        assistantProcessingSinceMs = if (active) System.currentTimeMillis() else 0L
        GatewayRuntime.update {
            it.copy(
                assistantProcessing = active,
                assistantProcessingLabel = if (active) label.trim() else ""
            )
        }
    }

    private fun dispatchTranscriptToOpenClaw(
        phrase: String,
        state: GatewayUiState
    ) {
        val store = settingsStore ?: GatewaySettingsStore(this)
        val settings = runCatching { store.load() }.getOrElse {
            Log.w(TAG, "Falha ao carregar configuracao do OpenClaw", it)
            return
        }
        val decision = VoiceChannelSkill.evaluate(
            phrase = phrase,
            settings = settings,
            conversationUntilEpochMs = assistantConversationUntilEpochMs,
            lastDirectAddressEpochMs = lastDirectAddressToOpenClawEpochMs
        )
        if (decision.shouldResetConversationContext) {
            val now = System.currentTimeMillis()
            lastDirectAddressToOpenClawEpochMs = now
            assistantConversationUntilEpochMs =
                now + settings.voiceChannelFollowUpSeconds.coerceAtLeast(0) * 1000L
            assistantReplyInterruptedPending = false
            interruptedAssistantReplyPreview = ""
        } else if (decision.isDirectAddress) {
            lastDirectAddressToOpenClawEpochMs = System.currentTimeMillis()
        }
        if (
            settings.openClawGatewayUrl.isBlank() ||
            settings.openClawGatewayToken.isBlank() ||
            settings.openClawDeviceToken.isBlank() ||
            settings.openClawSessionKey.isBlank()
        ) {
            activeOpenClawDispatchStartedAtEpochMs = 0L
            updateOpenClawDispatchQueueCount()
            GatewayRuntime.update {
                it.copy(openClawStatus = "OpenClaw desativado na configuracao.")
            }
            return
        }

        GatewayRuntime.update {
            it.copy(
                openClawStatus = when {
                    TranscriptTextPipeline.isNeutralMarkerTranscript(phrase) -> "OpenClaw recebeu marcador neutro para decidir contexto."
                    TranscriptTextPipeline.shouldIgnoreAmbientTranscript(phrase) -> "OpenClaw recebeu frase ambiente para decidir contexto."
                    state.multipleVoicesLikely -> "OpenClaw recebeu trecho com sobreposicao provavel para decidir contexto."
                    decision.reason == "wake_term" -> "OpenClaw recebeu chamada explicita; contexto reiniciado."
                    decision.reason == "follow_up_window" -> "OpenClaw recebeu continuidade da conversa atual."
                    decision.reason == "idle_confirmation_window" -> "OpenClaw recebeu frase fora do contexto recente; pode pedir confirmacao."
                    decision.reason == "ambient_conversation" -> "OpenClaw recebeu frase ambiente para decidir contexto."
                    else -> "OpenClaw enviando frase final..."
                }
            )
        }

        openClawExecutor?.execute {
            try {
                val segmentId = persistentOpenClawConnection?.sendTranscript(
                    config = buildOpenClawConfig(
                        settings = settings,
                        state = state,
                        voiceDecision = decision,
                        transcript = phrase
                    ),
                    transcript = phrase
                )
                GatewayRuntime.update {
                    it.copy(
                        openClawStatus = "OpenClaw enviou frase final${segmentId?.let { " ($it)" }.orEmpty()}."
                    )
                }
                // Enviado: agente processando ate o reply chegar
                // (handleOpenClawReply limpa). Label = o pedido.
                setAssistantProcessing(true, phrase)
            } catch (ex: Exception) {
                Log.e(TAG, "Falha ao enviar frase para OpenClaw", ex)
                setAssistantProcessing(false)
                GatewayRuntime.update {
                    it.copy(
                        openClawStatus = "Falha OpenClaw: ${ex.message ?: ex.javaClass.simpleName}",
                        lastAssistantReplyNeedsAttention = false,
                        lastAssistantReplyTags = emptyList(),
                        lastAssistantReplyConfidence = null,
                        lastAssistantReplyOverlap = false
                    )
                }
            } finally {
                activeOpenClawDispatchStartedAtEpochMs = 0L
                updateOpenClawDispatchQueueCount()
            }
        }
    }

    // Conectores que, no FIM da transcricao pendente, indicam frase
    // inacabada (o usuario parou para pensar no meio do raciocinio). O
    // Whisper poe ponto final em quase tudo, entao o conector vale mesmo
    // com pontuacao depois ("...do tipo." continua inacabado).
    private val continuationTailWords = setOf(
        "que", "e", "ou", "mas", "porque", "se", "tipo", "entao", "então",
        "de", "da", "do", "das", "dos", "na", "no", "nas", "nos", "em",
        "para", "pra", "com", "sem", "por", "ao", "aos", "a", "o", "um",
        "uma", "ai", "aí", "dai", "daí", "ne", "né", "tambem", "também"
    )

    private fun transcriptLooksUnfinished(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.endsWith("...") || trimmed.last() in setOf(',', ';', ':', '-')) return true
        val lastWord = trimmed
            .split(Regex("\\s+"))
            .last()
            .lowercase()
            .trim('.', '!', '?', ',', ';', ':', '-')
        return lastWord in continuationTailWords
    }

    private fun buildOpenClawMetadata(
        state: GatewayUiState,
        settings: GatewaySettings,
        voiceDecision: VoiceChannelSkillDecision,
        transcript: String? = null
    ): JSONObject {
        val interruptedReplyContext = if (assistantReplyInterruptedPending) {
            val preview = interruptedAssistantReplyPreview.trim()
            assistantReplyInterruptedPending = false
            interruptedAssistantReplyPreview = ""
            preview
        } else {
            ""
        }
        return JSONObject().apply {
            put("origin", "android")
            put("deviceId", openClawGatewayClient.describeAndroidDevice())
            put("voiceChannelSkillEnabled", settings.voiceChannelSkillEnabled)
            put("reason", voiceDecision.reason)
            put("isDirectAddress", voiceDecision.isDirectAddress)
            put("contextResetRequested", voiceDecision.shouldResetConversationContext)
            put("forceVoiceReplyByDefault", voiceDecision.forceVoiceReplyByDefault)
            put("shouldAskForWakeConfirmation", voiceDecision.shouldAskForWakeConfirmation)
            put("multipleVoicesLikely", state.multipleVoicesLikely)
            transcript?.let {
                put("neutralTranscriptMarker", TranscriptTextPipeline.isNeutralMarkerTranscript(it))
                put("ambientTranscriptLikely", TranscriptTextPipeline.shouldIgnoreAmbientTranscript(it))
            }
            state.lastGender?.trim()?.takeIf { it.isNotBlank() }?.let { put("gender", it) }
            state.lastEmotion?.trim()?.takeIf { it.isNotBlank() }?.let { put("emotion", it) }
            state.sameSpeakerProbability?.let { put("sameSpeakerProbability", it) }
            // Verificacao de locutor por embedding (CAM++): score da ultima
            // fala aceita. O pre-agente do servidor usa este campo para
            // dispensar a confirmacao de wake quando o DONO verificado esta
            // falando (>= 0.5) — segmentos rejeitados nem chegam aqui.
            GatewayRuntime.speakerVoice().value.let { speaker ->
                if (speaker.enabled && speaker.lastScore != null) {
                    put("speakerVerifiedScore", speaker.lastScore)
                }
            }
            // Canal de voz REALMENTE ativo: a resposta sera falada (TTS
            // habilitado e pronto, fora de standby). Sinaliza a IA do outro
            // lado a responder de forma falavel e usar o campo details para
            // conteudo nao-pronunciavel.
            put("voiceModeActive", settings.assistantVoiceEnabled && textToSpeechReady && !standbyMode)
            // Atividade labial agregada da ultima fala (camera frontal):
            // o pre-agente cruza com speakerVerifiedScore — voz do dono COM
            // labios mexendo = dono presente falando; voz do dono SEM labios
            // = possivel TV/gravacao. Omitido sem camera/rosto (opcional).
            lastSegmentLipActivity?.let { lips ->
                if (System.currentTimeMillis() - lips.atEpochMs <= LIP_METADATA_MAX_AGE_MS) {
                    put("lipActivityScore", lips.score)
                    put("lipActivitySamples", lips.samples)
                }
            }
            SpeakerContinuityHistoryLogger.buildMetadataSummary(this@RoomAudioForegroundService)?.let {
                put("speakerContinuityHistory", it)
            }
            voiceDecision.matchedWakeTerm?.let { put("matchedWakeTerm", it) }
            voiceDecision.secondsSinceDirectAddress?.let { put("secondsSinceDirectAddress", it) }
            if (interruptedReplyContext.isNotBlank()) {
                put("interruptedAssistantReplyPreview", interruptedReplyContext)
            }
        }
    }

    private fun appendSpeakerContinuityHistory(entry: SpeakerContinuityHistoryEntry) {
        runCatching {
            SpeakerContinuityHistoryLogger.append(this, entry)
        }.onFailure { ex ->
            Log.w(TAG, "Falha ao registrar historico de continuidade vocal", ex)
        }
    }

    private fun appendSpectrumDiagnostics(
        rawRms: Double,
        adjustedRms: Double,
        noiseFloorRms: Double,
        dynamicContrast: Double,
        rmsVariance: Double,
        spectrumMotion: Double,
        stabilityScore: Double,
        ambientNoiseDetected: Boolean,
        ambientNoiseKind: String?,
        speechLikeRaw: Boolean,
        speechLikeEffective: Boolean,
        dynamicSpeechOverride: Boolean,
        shouldCompensateAmbientNoise: Boolean,
        shouldBlockAsAmbientNoise: Boolean,
        dynamicMicrophoneGain: Double,
        zeroCrossingRate: Double,
        peakNormalized: Double,
        spectrum: List<Float>
    ) {
        val tailWindow = spectrum.takeLast(SPECTRUM_DIAGNOSTIC_TAIL_SIZE).map { it.toDouble() }
        val entry = SpectrumDiagnosticsEntry(
            occurredAt = Instant.now(),
            rawRms = rawRms,
            adjustedRms = adjustedRms,
            noiseFloorRms = noiseFloorRms,
            dynamicContrast = dynamicContrast,
            rmsVariance = rmsVariance,
            spectrumMotion = spectrumMotion,
            stabilityScore = stabilityScore,
            ambientNoiseDetected = ambientNoiseDetected,
            ambientNoiseKind = ambientNoiseKind,
            speechLikeRaw = speechLikeRaw,
            speechLikeEffective = speechLikeEffective,
            dynamicSpeechOverride = dynamicSpeechOverride,
            shouldCompensateAmbientNoise = shouldCompensateAmbientNoise,
            shouldBlockAsAmbientNoise = shouldBlockAsAmbientNoise,
            dynamicMicrophoneGain = dynamicMicrophoneGain,
            zeroCrossingRate = zeroCrossingRate,
            peakNormalized = peakNormalized,
            spectrumTail = tailWindow
        )
        runCatching {
            SpectrumDiagnosticsLogger.append(this, entry)
        }.onFailure { ex ->
            Log.w(TAG, "Falha ao registrar diagnostico do espectro", ex)
        }
    }

    private fun handleOpenClawReply(reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply) {
        // Resposta chegou: encerra o balao de "processando".
        setAssistantProcessing(false)
        // Falha do agente no servidor: detalhe cru vem no campo "error" do
        // envelope. Vai para log e status — nunca vira bolha de chat e o TTS
        // nunca le o texto cru; o usuario ouve um aviso curto e amigavel.
        reply.errorText?.takeIf { it.isNotBlank() }?.let { error ->
            Log.e(TAG, "OpenClaw: falha do agente no servidor: $error")
            GatewayRuntime.update {
                it.copy(
                    openClawStatus = "OpenClaw: falha do agente no servidor.",
                    systemInfoMessage = "O agente falhou ao processar a fala (detalhe no log).",
                    systemInfoMessageUntilEpochMs = System.currentTimeMillis() + 8_000L,
                    lastAssistantReplyNeedsAttention = false,
                    lastAssistantReplyTags = reply.tags,
                    lastAssistantReplyConfidence = null,
                    lastAssistantReplyOverlap = false
                )
            }
            speakAssistantReply("Tive um problema ao processar. Pode tentar de novo?")
            return
        }
        val loadedSettings = runCatching {
            settingsStore?.load() ?: GatewaySettingsStore(this).load()
        }.getOrElse {
            Log.w(TAG, "Falha ao carregar configuracao do OpenClaw para reply", it)
            return
        }
        val patchResult = applyRemoteSettingsPatchIfNeeded(reply, loadedSettings)
        val settings = patchResult?.settings ?: loadedSettings
        val patchSummary = patchResult
            ?.appliedKeys
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        val currentState = GatewayRuntime.state().value
        val assistantReply = reply.replyText.trim()
        val spokenReply = reply.spokenReplyText.ifBlank { assistantReply }
        val displayReply = spokenReply.ifBlank { assistantReply }
        val requiresAttention = reply.needsAttention
        val blockingAnnouncement = when {
            requiresAttention -> buildBlockingAnnouncementMessage(reply)
            !reply.shouldSpeak && !reply.speakBlockReason.isNullOrBlank() -> reply.speakBlockReason
            else -> null
        }
        val systemInfoMessage = if (reply.isSystemInfo && assistantReply.isNotBlank()) assistantReply else null
        if (assistantReply.isNotBlank() || requiresAttention) {
            assistantConversationUntilEpochMs =
                System.currentTimeMillis() + settings.voiceChannelFollowUpSeconds.coerceAtLeast(0) * 1000L
        }
        GatewayRuntime.update {
            it.copy(
                openClawStatus = when {
                    assistantReply.isBlank() && requiresAttention -> {
                        "OpenClaw reteve o trecho para revisar contexto."
                    }

                    assistantReply.isBlank() -> {
                        "OpenClaw avaliou o trecho sem gerar resposta."
                    }

                    requiresAttention -> {
                        "OpenClaw respondeu com duvida de contexto."
                    }

                    else -> {
                        "OpenClaw respondeu (${reply.finalState})."
                    }
                } + patchSummary?.let { " Config Android atualizada: $it." }.orEmpty(),
                blockingAnnouncementMessage = blockingAnnouncement,
                lastAssistantReply = if (requiresAttention || reply.isSystemInfo) "" else displayReply.ifBlank { it.lastAssistantReply },
                lastAssistantReplyNeedsAttention = requiresAttention,
                lastAssistantReplyTags = reply.tags,
                lastAssistantReplyConfidence = reply.confidence,
                lastAssistantReplyOverlap = reply.overlap,
                systemInfoMessage = systemInfoMessage ?: it.systemInfoMessage,
                systemInfoMessageUntilEpochMs = if (systemInfoMessage != null) System.currentTimeMillis() + 8_000L else it.systemInfoMessageUntilEpochMs
            )
        }
        // Historico de conversa: resposta do assistente vira bolha no chat.
        // O details (conteudo visual-apenas) vai junto como painel expansivel;
        // nunca entra no texto falado (spokenReply).
        if (!reply.isSystemInfo && !requiresAttention && displayReply.isNotBlank()) {
            GatewayRuntime.appendChatMessage(ChatRole.ASSISTANT, displayReply, reply.detailsText)
        }
        // API injectConversation(speak=false) suprime a fala desta resposta.
        val speechSuppressedByApi = suppressNextReplySpeech
        if (speechSuppressedByApi) {
            suppressNextReplySpeech = false
        }
        if (reply.shouldSpeak && !reply.isSystemInfo && spokenReply.isNotBlank() && !speechSuppressedByApi) {
            speakAssistantReply(spokenReply)
        } else {
            Log.i(
                TAG,
                "Reply OpenClaw sem fala automatica. attention=$requiresAttention systemInfo=${reply.isSystemInfo} apiSuppressed=$speechSuppressedByApi tags=${reply.tags.joinToString(",")}"
            )
        }
        Log.i(
            TAG,
            "OpenClaw reply recebida: ${reply.rawReplyText.take(180)} | patch=${patchSummary ?: "nenhum"}"
        )
    }

    private fun buildBlockingAnnouncementMessage(
        reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply
    ): String {
        val assistantReply = reply.replyText.trim()
        if (assistantReply.isNotBlank()) {
            return assistantReply
        }

        return when {
            reply.tags.any { it.equals("uncertain_target", ignoreCase = true) } -> {
                "O OpenClaw nao conseguiu confirmar que essa fala era direcionada a ele. Diga o nome do assistente ou um apelido para destravar o fluxo."
            }

            reply.tags.any {
                it.equals("overlap_suspected", ignoreCase = true) ||
                    it.equals("overlap_confirmed", ignoreCase = true)
            } || reply.overlap -> {
                "Houve sobreposicao de vozes neste trecho e o OpenClaw reteve a resposta para evitar agir no contexto errado."
            }

            reply.confidence != null -> {
                "A confianca do trecho ficou baixa para o OpenClaw decidir o contexto com seguranca."
            }

            else -> {
                "O OpenClaw reteve este trecho para revisar o contexto antes de responder."
            }
        }
    }

    private fun applyRemoteSettingsPatchIfNeeded(
        reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply,
        currentSettings: GatewaySettings
    ): GatewaySettingsPatchResult? {
        val patch = reply.settingsPatch ?: return null
        return applyConfigPatchInternal(patch, currentSettings)
    }

    /**
     * Aplica um patch de configuracao (do reply do OpenClaw OU da API HTTP):
     * persiste e dispara os efeitos colaterais necessarios — refresh de TTS,
     * reconexao, restart de captura e restart da API. Ponto unico para
     * qualquer origem de mudanca remota de config.
     */
    private fun applyConfigPatchInternal(
        patch: JSONObject,
        currentSettings: GatewaySettings = loadCurrentSettings()
    ): GatewaySettingsPatchResult {
        val store = settingsStore ?: GatewaySettingsStore(this)
        // Aceita patch SECCIONADO (igual config.json: {general:{...}}), FLAT
        // ({cameraGestureEnabled:...}) ou misto: achata as secoes conhecidas
        // e preserva chaves planas de topo.
        val flat = com.sufficit.ai.gateway.config.flattenSectionedJson(patch)
        val keys = patch.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (patch.opt(k) !is JSONObject && !flat.has(k)) {
                flat.put(k, patch.opt(k))
            }
        }
        val result = currentSettings.applyWebSocketSettingsPatch(flat)
        if (result.appliedKeys.isEmpty()) {
            if (result.ignoredKeys.isNotEmpty()) {
                Log.w(TAG, "Patch ignorado: ${result.ignoredKeys.joinToString(",")}")
            }
            return result
        }

        store.save(result.settings)
        if (result.requiresTtsRefresh) {
            applyAssistantVoiceSettings(result.settings)
        }
        if (result.requiresReconnect) {
            refreshOpenClawConnection(result.settings)
        }
        if (result.requiresCaptureRestart && captureRunning.get()) {
            restartCaptureForRemoteSettings()
        }
        if (result.requiresApiRestart) {
            restartApiServer(result.settings)
        }

        if (result.ignoredKeys.isNotEmpty()) {
            Log.w(
                TAG,
                "Patch aplicado parcialmente. applied=${result.appliedKeys.joinToString(",")} ignored=${result.ignoredKeys.joinToString(",")}"
            )
        } else {
            Log.i(TAG, "Patch aplicado: ${result.appliedKeys.joinToString(",")}")
        }
        return result
    }

    private fun refreshOpenClawConnection(settings: GatewaySettings) {
        if (
            settings.openClawGatewayUrl.isBlank() ||
            settings.openClawGatewayToken.isBlank() ||
            settings.openClawDeviceToken.isBlank() ||
            settings.openClawSessionKey.isBlank()
        ) {
            persistentOpenClawConnection?.disconnect()
            Log.i(TAG, "Conexao OpenClaw encerrada apos patch remoto por configuracao incompleta.")
            return
        }

        runCatching {
            persistentOpenClawConnection?.connect(buildOpenClawConfig(settings))
        }.onFailure {
            Log.w(TAG, "Falha ao reconfigurar websocket OpenClaw apos patch remoto", it)
        }
    }

    private fun restartCaptureForRemoteSettings() {
        Log.i(TAG, "Reiniciando captura para aplicar configuracao remota do Android.")
        shutdownCapture()
        startCaptureIfNeeded()
    }

    // ------------------------------------------------------------------
    // API HTTP de controle (GatewayApiActions + lifecycle do servidor)
    // ------------------------------------------------------------------

    private fun startApiServerIfEnabled(settings: GatewaySettings) {
        if (!settings.apiEnabled) return
        if (apiServer != null) return
        apiServer = com.sufficit.ai.gateway.api.GatewayApiServer.startIfEnabled(
            enabled = settings.apiEnabled,
            token = settings.apiToken,
            port = settings.apiPort,
            bindAll = settings.apiBindAllInterfaces,
            tokenProvider = { loadCurrentSettings().apiToken },
            actions = this
        )
    }

    private fun stopApiServer() {
        runCatching { apiServer?.stop() }
        apiServer = null
    }

    private fun restartApiServer(settings: GatewaySettings) {
        // Adia o restart para a resposta HTTP do patch que mudou a config da
        // API conseguir sair antes do socket cair (mudanca de porta/token/
        // enabled chega pela propria API).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopApiServer()
            startApiServerIfEnabled(loadCurrentSettings())
        }, 250L)
    }

    // ---- GatewayApiActions ----

    override fun currentSettings(): GatewaySettings = loadCurrentSettings()

    override fun applyConfigPatch(patch: JSONObject): GatewaySettingsPatchResult =
        applyConfigPatchInternal(patch)

    override fun startListening() {
        standbyMode = false
        if (!captureRunning.get()) {
            startCaptureIfNeeded()
        }
        GatewayRuntime.setListening(active = true, statusText = "Escuta iniciada por API.")
    }

    override fun stopListening() {
        if (captureRunning.get() && isWakeWordStandbyAvailable()) {
            standby()
            return
        }
        stopRequested.set(true)
        shutdownCapture()
        GatewayRuntime.setListening(active = false, statusText = "Escuta parada por API.")
    }

    override fun standby() {
        if (!captureRunning.get()) return
        standbyMode = true
        GatewayRuntime.setListening(
            active = false,
            statusText = "Em espera (API). Diga a palavra de ativacao para retomar."
        )
        refreshNotification("Em espera | aguardando palavra de ativacao")
    }

    override fun wake() {
        standbyMode = false
        if (!captureRunning.get()) {
            startCaptureIfNeeded()
        }
        wakeDevice(loadCurrentSettings().screenHoldSeconds * 1000L)
        GatewayRuntime.setListening(active = true, statusText = "Retomado por API.")
    }

    override fun say(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        speakAssistantReply(trimmed)
    }

    override fun injectConversation(text: String, speak: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        suppressNextReplySpeech = !speak
        markDirectAddressNow()
        scheduleTranscriptDispatchToOpenClaw(
            phrase = trimmed,
            state = GatewayRuntime.state().value,
            immediate = true
        )
    }

    override fun interruptAssistant() {
        interruptAssistantSpeechByTouch()
    }

    override fun triggerGesture(gestureId: String) {
        // Reproduz o efeito do gesto da camera sem o reconhecedor. Atualiza o
        // estado continuo (overlay/rodape) e dispara a acao equivalente.
        GatewayRuntime.setGestureCommand(gestureId)
        when (gestureId) {
            GestureCommandIds.INDEX_UP -> {
                markDirectAddressNow()
                wake()
            }
            GestureCommandIds.FIST -> finalizeSegment()
            GestureCommandIds.OPEN_HAND -> interruptAssistant()
        }
    }

    override fun finalizeSegment() {
        finalizeSegmentRequested.set(true)
        commitAfterTranscriptionRequestedAt.set(System.currentTimeMillis())
    }

    override fun clearChat() {
        GatewayRuntime.clearChat()
    }

    private fun buildOpenClawConfig(
        settings: GatewaySettings,
        state: GatewayUiState? = null,
        voiceDecision: VoiceChannelSkillDecision? = null,
        transcript: String? = null
    ): OpenClawGatewayConfig {
        return OpenClawGatewayConfig(
            gatewayUrl = settings.openClawGatewayUrl,
            gatewayToken = settings.openClawGatewayToken,
            deviceToken = settings.openClawDeviceToken,
            sessionKey = settings.openClawSessionKey,
            userId = settings.openClawUserId,
            installationId = com.sufficit.ai.gateway.config.InstallationId.get(this),
            backend = state?.transcriptionBackendLabel,
            model = state?.transcriptionModelLabel,
            metadata = if (state != null && voiceDecision != null) {
                buildOpenClawMetadata(
                    state = state,
                    settings = settings,
                    voiceDecision = voiceDecision,
                    transcript = transcript
                )
            } else {
                null
            }
        )
    }

    private fun speakAssistantReply(replyText: String) {
        val normalized = sanitizeReplyForSpeech(replyText)
        if (normalized.isBlank()) {
            Log.i(TAG, "Resposta do OpenClaw suprimida na voz por conter conteudo pouco falavel ou tecnico demais.")
            return
        }
        val settings = loadCurrentSettings()
        if (!settings.assistantVoiceEnabled) {
            Log.i(TAG, "Resposta por voz desativada na configuracao.")
            return
        }
        val tts = textToSpeech
        if (tts == null || !textToSpeechReady) {
            Log.w(TAG, "Resposta do OpenClaw recebida sem TTS disponivel.")
            return
        }
        applyAssistantVoiceSettings(settings)
        val utteranceId = "openclaw-reply-${System.currentTimeMillis()}"
        val result = tts.speak(
            normalized,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Falha ao falar resposta do OpenClaw: result=$result")
        }
    }

    private fun sanitizeReplyForSpeech(replyText: String): String {
        val normalized = replyText
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("[*_~#>`]+"), " ")
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), " ")
            .lineSequence()
            .map { it.trim() }
            .filter { isVoiceFriendlyReplyLine(it) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) {
            return ""
        }

        return if (looksLikeCodeHeavySpeech(normalized)) {
            ""
        } else {
            normalized
        }
    }

    private fun isVoiceFriendlyReplyLine(line: String): Boolean {
        if (line.isBlank()) {
            return false
        }

        val normalized = line.trim()
        val lower = normalized.lowercase()
        val codeMarkers = listOf(
            "{", "}", "=>", "===", "</", "/>", "::", "();", "function ", "const ", "let ", "var ",
            "import ", "export ", "return ", "class ", "interface ", "public ", "private ", "protected ",
            "package ", "using ", "select ", "insert ", "update ", "delete from ", "curl ", "npm ", "gradlew",
            "json", "xml", "yaml", "base64"
        )
        if (codeMarkers.any { lower.contains(it) }) {
            return false
        }

        val symbolCount = normalized.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".,!?:;()-" }
        val letterCount = normalized.count { it.isLetter() }
        if (letterCount == 0) {
            return false
        }

        return symbolCount <= (normalized.length / 6)
    }

    private fun looksLikeCodeHeavySpeech(text: String): Boolean {
        val lower = text.lowercase()
        val suspiciousPatterns = listOf(
            "{", "}", "=>", "==", "<tag", "</", "[]", "()", "const ", "let ", "function ", "import ",
            "export ", "class ", "json", "xml", "base64", "http://", "https://", "/data/user/0/"
        )
        return suspiciousPatterns.count { lower.contains(it) } >= 3
    }

    private fun shouldSuppressAttentionPromptSpeech(
        text: String,
        requiresAttention: Boolean
    ): Boolean {
        if (!requiresAttention) {
            return false
        }
        val normalized = normalizeTranscriptForMatch(text)
        if (normalized.isBlank()) {
            return true
        }
        val confirmationMarkers = listOf(
            "e comigo",
            "é comigo",
            "fale meu nome",
            "fale o meu nome",
            "fale meu apelido",
            "fale o meu apelido",
            "chame meu nome",
            "chame pelo nome",
            "chame pelo apelido"
        )
        return confirmationMarkers.any { normalized.contains(normalizeTranscriptForMatch(it)) }
    }

    private fun interruptAssistantSpeechByTouch() {
        if (!assistantSpeaking && !GatewayRuntime.state().value.speakingBack) {
            return
        }
        val interruptedReply = GatewayRuntime.state().value.lastAssistantReply.trim()
        assistantReplyInterruptedPending = true
        interruptedAssistantReplyPreview = interruptedReply.take(220)
        assistantInterruptedByUser = true
        assistantSpeaking = false
        suppressMicrophoneUntilEpochMs = 0L
        textToSpeech?.stop()
        GatewayRuntime.update {
            it.copy(
                statusText = "Assistente interrompido por toque.",
                speakingBack = false,
                speechDetected = false
            )
        }
        refreshNotification("Assistente interrompido.")
        Log.i(TAG, "Assistente interrompido manualmente por toque.")
    }

    private fun normalizeTranscriptForMatch(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun loadCurrentSettings(): GatewaySettings {
        return runCatching {
            settingsStore?.load() ?: GatewaySettingsStore(this).load()
        }.getOrElse {
            Log.w(TAG, "Falha ao carregar configuracao atual do gateway", it)
            GatewaySettingsStore(this).load()
        }
    }

    private fun applyAssistantVoiceSettings(settings: GatewaySettings) {
        val tts = textToSpeech ?: return
        tts.setSpeechRate(settings.assistantSpeechRate.toFloat())
        tts.setPitch(settings.assistantPitch.toFloat())

        val targetVoice = resolveAssistantVoice(tts, settings.assistantVoiceStyle)
        if (targetVoice != null) {
            runCatching {
                tts.voice = targetVoice
                Log.i(
                    TAG,
                    "Voice TTS aplicada: ${targetVoice.name} | locale=${targetVoice.locale} | style=${settings.assistantVoiceStyle.persistedValue}"
                )
            }.onFailure {
                Log.w(TAG, "Falha ao aplicar voz TTS ${targetVoice.name}", it)
            }
        }
    }

    private fun resolveAssistantVoice(
        tts: TextToSpeech,
        style: AssistantVoiceStyle
    ): android.speech.tts.Voice? {
        val voices = tts.voices
            ?.filter { voice ->
                val locale = voice.locale ?: return@filter false
                locale.language.equals("pt", ignoreCase = true)
            }
            ?.sortedWith(
                compareByDescending<android.speech.tts.Voice> { voice ->
                    voice.locale?.country.equals("BR", ignoreCase = true)
                }.thenBy { it.name }
            )
            .orEmpty()
        if (voices.isEmpty()) {
            return null
        }
        if (style == AssistantVoiceStyle.SYSTEM) {
            return voices.firstOrNull()
        }

        val masculineHints = listOf("male", "masc", "homem", "man", "m1", "brazil-m")
        val feminineHints = listOf("female", "fem", "mulher", "woman", "f1", "brazil-f")
        val hints = if (style == AssistantVoiceStyle.MASCULINE) masculineHints else feminineHints

        val preferred = voices.firstOrNull { voice ->
            val signature = buildString {
                append(voice.name.lowercase(Locale.ROOT))
                append(' ')
                append(voice.locale?.displayName.orEmpty().lowercase(Locale.ROOT))
                append(' ')
                append(voice.features.joinToString(" ").lowercase(Locale.ROOT))
            }
            hints.any { hint -> signature.contains(hint) }
        }
        return preferred ?: voices.firstOrNull()
    }

    private fun String.splitWhitespace(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    private fun buildWavPcm16(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmBytes.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmBytes.size)
        }.array()

        return ByteArrayOutputStream().use { output ->
            output.write(header)
            output.write(pcmBytes)
            output.toByteArray()
        }
    }

    private fun translateGender(value: String?): String? {
        return when (value?.lowercase()) {
            "male" -> "masculino"
            "female" -> "feminino"
            "ambiguous" -> "ambiguo"
            else -> value
        }
    }

    private fun translateEmotion(value: String?): String? {
        return when (value?.lowercase()) {
            "neutral" -> "neutra"
            "happy" -> "feliz"
            "sad" -> "triste"
            "angry" -> "raiva"
            "calm" -> "calma"
            "energetic" -> "energica"
            else -> value
        }
    }

    companion object {
        private data class CaptureProfile(
            val speechHoldMs: Long,
            val maxSpeechSegmentMs: Long,
            val minTranscriptionMs: Long,
            val phraseBreakSilenceMs: Long
        )

        private const val TAG = "RoomAudioGateway"
        private const val CHANNEL_ID = "room-audio-gateway-v2"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_FINALIZE_SEGMENT = "com.sufficit.ai.gateway.action.FINALIZE_SEGMENT"
        private const val ACTION_SEND_TEXT = "com.sufficit.ai.gateway.action.SEND_TEXT"
        private const val ACTION_MARK_DIRECT_ADDRESS = "com.sufficit.ai.gateway.action.MARK_DIRECT_ADDRESS"
        private const val EXTRA_TEXT = "extra_text"

        // Janela minima de enderecamento direto apos um sinal explicito
        // (gesto/palavra/texto): cobre transcricao + acumulacao ate o
        // despacho avaliar a frase.
        private const val DIRECT_ADDRESS_MIN_WINDOW_SECS = 30
        private const val ACTION_START = "com.sufficit.ai.gateway.action.START"
        private const val ACTION_INTERRUPT_ASSISTANT = "com.sufficit.ai.gateway.action.INTERRUPT_ASSISTANT"
        private const val ACTION_STOP = "com.sufficit.ai.gateway.action.STOP"
        private const val ACTION_RELOAD_API = "com.sufficit.ai.gateway.action.RELOAD_API"
        private const val ACTION_RELOAD_CONFIG = "com.sufficit.ai.gateway.action.RELOAD_CONFIG"
        private const val ASSISTANT_SPEECH_GRACE_MS = 1_500L
        private const val OPENCLAW_UNCERTAIN_PREFIX = "[?]"
        private const val OPENCLAW_REASONING_HOLD_MS = 2200L
        private const val ASSISTANT_BARGE_IN_STARTUP_BLOCK_MS = 900L
        private const val SAMPLE_RATE_HZ = 16_000

        // Janela de gravacao da amostra da palavra de ativacao (2.2s a 16kHz).
        private const val WAKE_WORD_RECORD_SAMPLES = 35_200
        private const val WAKE_WORD_DIAGNOSTIC_LOG_INTERVAL_MS = 1_000L

        // Validade do gesto continuo (indicador mantido) desde o ultimo
        // quadro da camera que o confirmou.
        private const val GESTURE_HOLD_VALIDITY_MS = 900L

        // Tempo maximo que o "indicador mantido" pode segurar a gravacao
        // aberta; acima disso e tratado como falso positivo do reconhecedor.
        private const val INDEX_HOLD_MAX_MS = 20_000L

        // Validade do pedido de commit do punho (evita commits espurios).
        private const val COMMIT_REQUEST_TTL_MS = 15_000L

        // Commit automatico: tempo apos a ultima transcricao com texto, sem
        // fala ativa e sem fila, para despachar sozinho ao OpenClaw.
        private const val AUTO_COMMIT_AFTER_TRANSCRIPTION_MS = 3_500L

        // Frase pendente com cara de inacabada (vide transcriptLooksUnfinished):
        // janelas de commit esticadas — pausa para pensar nao despacha.
        private const val AUTO_COMMIT_UNFINISHED_TRANSCRIPTION_MS = 9_000L
        private const val UNFINISHED_SPEECH_SILENCE_MULTIPLIER = 4L

        // Verificacao de voz: segmentos curtos pontuam mais baixo mesmo
        // sendo o dono — limiar ganha desconto abaixo desta duracao.
        private const val SHORT_SEGMENT_FOR_SPEAKER_MS = 2_000L
        private const val SHORT_SEGMENT_THRESHOLD_DISCOUNT = 0.08

        // Faixa cinzenta da verificacao de voz: abaixo do limiar mas dentro
        // desta margem o segmento NAO e descartado — segue com o score no
        // metadata para o pre-agente do servidor decidir.
        private const val SPEAKER_GRAY_ZONE = 0.10

        // Pre-roll de audio prefixado em cada segmento novo: cobre a latencia
        // da deteccao de fala (VAD + minSpeechCandidateFrames) para o comeco
        // da frase nao ser cortado da transcricao. Whisper ignora bem o
        // pedaco de ambiente que vem junto.
        private const val PRE_ROLL_MS = 1_200L
        private const val PRE_ROLL_MAX_BYTES = (SAMPLE_RATE_HZ * 2L * PRE_ROLL_MS / 1000L).toInt()

        // Timeout do balao "processando" sem reply (websocket caido etc.).
        private const val ASSISTANT_PROCESSING_TIMEOUT_MS = 90_000L

        // AGC: alvo de pico normalizado do sinal pos-ganho. 0.70 deixa
        // headroom abaixo do joelho do soft-clip (0.85) — fala/musica nao
        // satura nem gruda o espectro no teto. Abaixo de MIN_PEAK_FOR_AGC o
        // sinal e silencio/quase: nao limita (usa ganho cheio p/ fala distante).
        private const val TARGET_PEAK_NORMALIZED = 0.70
        private const val MIN_PEAK_FOR_AGC = 0.02

        // Atividade labial: quadro do FaceMesh so vale como amostra do
        // segmento se for recente (camera viva publicando).
        private const val LIP_SAMPLE_FRESH_MS = 1_500L

        // Idade maxima do agregado labial para entrar no metadata: o despacho
        // acontece segundos apos o fim da fala; bem alem disso o agregado e
        // de outra conversa.
        private const val LIP_METADATA_MAX_AGE_MS = 60_000L

        // Tela acesa por utterance do assistente (renovado a cada uma):
        // janela para o usuario interromper por gesto, expira apos a fala.
        private const val ASSISTANT_SPEECH_SCREEN_HOLD_MS = 25_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 800L
        private const val TRANSCRIPTION_QUEUE_RECONCILE_INTERVAL_MS = 1_500L
        private const val DEFAULT_SPEECH_HOLD_MS = 250L
        private const val DEFAULT_MAX_SPEECH_SEGMENT_MS = 700L
        private const val DEFAULT_MIN_TRANSCRIPTION_MS = 180L
        private const val DEFAULT_PHRASE_BREAK_SILENCE_MS = 1_000L
        private val LOCAL_TRANSCRIPTION_TIMEOUT_MS = 180_000L
        private const val MAX_QUEUED_TRANSCRIPTION_AGE_MS = 25_000L
        private const val ACTIVE_TRANSCRIPTION_STALL_BACKLOG_CLEAR_MS = 30_000L
        private const val MAX_TRANSCRIPTION_QUEUE = 3
        private const val MAX_TRANSCRIPT_CHARS = 1_200
        private const val MAX_WORD_OVERLAP = 8
        private const val SPECTRUM_SIZE = 48

        // Espectro "mudo" exibido quando o microfone esta aberto mas o audio
        // nao esta sendo aproveitado (standby / assistente falando).
        private val FLAT_SPECTRUM: List<Float> = List(SPECTRUM_SIZE) { 0f }
        private const val SPECTRUM_GAIN = 18f
        private const val MIN_SPEECH_CANDIDATE_FRAMES = 3
        private const val REMOTE_MIN_SPEECH_CANDIDATE_FRAMES = 2
        private const val MIN_SPEECH_RMS = 0.010
        private const val NOISE_GATE_MULTIPLIER = 1.8
        private const val REMOTE_MIN_SPEECH_RMS = 0.008
        private const val REMOTE_NOISE_GATE_MULTIPLIER = 1.45
        private const val MIN_SPEECH_ZERO_CROSSING_RATE = 0.015
        private const val MAX_SPEECH_ZERO_CROSSING_RATE = 0.24
        private const val REMOTE_MIN_SPEECH_ZERO_CROSSING_RATE = 0.008
        private const val REMOTE_MAX_SPEECH_ZERO_CROSSING_RATE = 0.28
        private const val MAX_TRANSIENT_CREST_FACTOR = 5.8
        private const val MIN_SPEECH_PEAK_NORMALIZED = 0.035
        private const val REMOTE_MAX_TRANSIENT_CREST_FACTOR = 7.2
        private const val REMOTE_MIN_SPEECH_PEAK_NORMALIZED = 0.024
        private const val NOISE_STABILITY_WINDOW = 14
        private const val AMBIENT_SPECTRUM_DELTA_WINDOW = 12
        private const val AMBIENT_DYNAMIC_CONTRAST_MAX = 0.050  // Raised: 0.014 was too low and zeroed out the dynamic score for music/loud ambient
        private const val AMBIENT_RMS_VARIANCE_MAX = 0.22
        private const val AMBIENT_SPECTRUM_MOTION_MAX = 0.060
        private const val AMBIENT_STABILITY_SCORE_THRESHOLD = 0.66
        private const val AMBIENT_GAIN_STABILITY_THRESHOLD = 0.35  // Lowered: gain reduction should trigger earlier for stable ambient environments
        private const val AMBIENT_DETECTION_HOLD_FRAMES = 6
        private const val AMBIENT_DETECTION_RELEASE_FRAMES = 4
        private const val AMBIENT_SPEECH_LIKELY_PENALTY = 0.20
        private const val AMBIENT_SPEECH_OVERRIDE_DYNAMIC_CONTRAST = 0.050  // Raised: 0.018 was firing on all music/noise, blocking gain reduction
        private const val AMBIENT_SPEECH_OVERRIDE_SPECTRUM_MOTION = 0.090
        private const val AMBIENT_MUSIC_MIN_ZERO_CROSSING_RATE = 0.035
        private const val AMBIENT_MUSIC_MAX_ZERO_CROSSING_RATE = 0.180
        private const val AMBIENT_MUSIC_MIN_DYNAMIC_CONTRAST = 0.004
        private const val AMBIENT_NOISE_GAIN_FACTOR = 0.58
        private const val AMBIENT_NOISE_GAIN_STABILITY_REDUCTION = 0.22
        private const val AMBIENT_NOISE_MIN_GAIN = 0.55
        private const val AMBIENT_GAIN_SMOOTHING_FAST = 0.40
        private const val AMBIENT_GAIN_SMOOTHING_SLOW = 0.14
        private const val AMBIENT_GAIN_WARNING_DELTA = 0.08
        private const val MICROPHONE_GAIN_WARNING_HOLD_MS = 3_600L
        private const val SPECTRUM_DIAGNOSTIC_LOG_INTERVAL_MS = 400L
        private const val SPECTRUM_DIAGNOSTIC_TAIL_SIZE = 12
        private val COMMON_PORTUGUESE_CONNECTORS = setOf(
            "a", "o", "as", "os", "um", "uma", "uns", "umas",
            "de", "da", "do", "das", "dos",
            "e", "em", "no", "na", "nos", "nas",
            "para", "por", "com", "sem",
            "que", "eu", "voce", "voces", "tu",
            "ele", "ela", "eles", "elas",
            "meu", "minha", "seu", "sua",
            "isso", "isto", "essa", "esse",
            "vamos", "vai", "vou", "foi", "era"
        )

        fun start(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun interruptAssistant(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_INTERRUPT_ASSISTANT
            }
            context.startService(intent)
        }

        /**
         * Marca enderecamento direto ao assistente (gesto "vou falar"):
         * a proxima fala nao deve ser retida como conversa ambiente.
         */
        fun markDirectAddress(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_MARK_DIRECT_ADDRESS
            }
            context.startService(intent)
        }

        /** Recarrega a API HTTP apos mudanca de configuracao na UI. */
        fun reloadApi(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_RELOAD_API
            }
            runCatching { context.startService(intent) }
        }

        /** Reconecta o OpenClaw apos mudanca de identidade (userId) na UI. */
        fun reloadConfig(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_RELOAD_CONFIG
            }
            runCatching { context.startService(intent) }
        }

        /** Envia uma mensagem digitada do chat para o OpenClaw. */
        fun sendText(context: Context, text: String) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_SEND_TEXT
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        /**
         * Gesto de punho fechado: finaliza imediatamente o segmento de fala
         * em andamento e envia para transcricao (ver ACTION_FINALIZE_SEGMENT).
         */
        fun finalizeSegment(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_FINALIZE_SEGMENT
            }
            context.startService(intent)
        }
    }
}
