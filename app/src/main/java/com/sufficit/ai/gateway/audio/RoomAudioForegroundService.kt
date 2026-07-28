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
import android.os.Bundle
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
import com.sufficit.ai.gateway.audio.wake.WakeWordConfig
import com.sufficit.ai.gateway.audio.wake.WakeWordDetector
import com.sufficit.ai.gateway.audio.wake.WakeWordProfileConfig
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.audio.wake.WakeWordTemplateProfile
import com.sufficit.ai.gateway.audio.wake.WakeWordThresholdPolicy
import com.sufficit.ai.gateway.vision.GestureCommandIds
import com.sufficit.ai.gateway.vision.GestureCommandPolicy
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
import com.sufficit.ai.gateway.openclaw.OpenClawInternalEventClassifier
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayConfig
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayPersistentConnection
import com.sufficit.ai.gateway.mcp.SufficitMcpToolBridge
import com.sufficit.ai.gateway.runtime.ChatAgentActivityState
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.runtime.ChatAudioState
import com.sufficit.ai.gateway.runtime.ChatDeliveryState
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.runtime.GatewayUiState
import com.sufficit.ai.gateway.transcription.local.LocalSherpaOnnxEngine
import com.sufficit.ai.gateway.transcription.local.LocalWhisperEngine
import com.sufficit.ai.gateway.transcription.CompanionTranscriptionClient
import com.sufficit.ai.gateway.transcription.CompanionTranscriptionException
import com.sufficit.ai.gateway.transcription.ElevenLabsRealtimeClient
import com.sufficit.ai.gateway.transcription.WhisperApiClient
import com.sufficit.ai.gateway.transcription.WhisperConfigurationCheck
import com.sufficit.ai.gateway.transcription.WhisperHttpException
import com.sufficit.ai.gateway.transcription.checkWhisperConfiguration
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RoomAudioForegroundService : Service(), TextToSpeech.OnInitListener, com.sufficit.ai.gateway.api.GatewayApiActions {
    private data class RetainedTranscriptAudio(val messageId: Long)
    private data class PendingAssistantSpeechAudio(
        val messageId: Long,
        val file: File,
        val expiresAtEpochMs: Long
    )

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
    private val elevenLabsRealtimeClient = ElevenLabsRealtimeClient()
    private val companionTranscriptionClient by lazy { CompanionTranscriptionClient(this) }
    private val completedTranscriptAudio = ConcurrentLinkedQueue<RetainedTranscriptAudio>()
    private val openClawGatewayClient by lazy { OpenClawGatewayClient() }
    private val sufficitMcpToolBridge by lazy { SufficitMcpToolBridge(this) }
    private var persistentOpenClawConnection: OpenClawGatewayPersistentConnection? = null
    private val selfTestExecuted = AtomicBoolean(false)
    private val openClawHandshakeStarted = AtomicBoolean(false)
    @Volatile private var phraseCommitPending = false
    @Volatile private var phraseAdvanceReady = false
    @Volatile private var localWhisperEngine: LocalWhisperEngine? = null
    @Volatile private var localSherpaOnnxEngine: LocalSherpaOnnxEngine? = null
    @Volatile private var speakerContinuityState: SpeakerContinuityState? = null
    private var openClawExecutor: ExecutorService? = null
    private var clientToolExecutor: ExecutorService? = null
    private var wakeOnLanExecutor: ExecutorService? = null
    @Volatile private var textToSpeech: TextToSpeech? = null
    @Volatile private var textToSpeechReady = false
    private val pendingAssistantSpeechAudio = ConcurrentHashMap<String, PendingAssistantSpeechAudio>()
    @Volatile private var assistantSpeaking = false
    @Volatile private var assistantInterruptedByUser = false
    @Volatile private var assistantSpeechStartedAtEpochMs = 0L
    @Volatile private var assistantLeakBaselineRms = 0.0
    @Volatile private var assistantLeakBaselineSamples = 0
    @Volatile private var suppressMicrophoneUntilEpochMs = 0L

    // Em espera: captura ativa apenas para a palavra de ativacao;
    // pipeline de transcricao suspenso ate a palavra ser detectada.
    // PADRÃO: Em espera, aguardando wake word (autostart desabilitado).
    @Volatile private var standbyMode = true

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
    private var wakeWordProfilesById: Map<String, WakeWordProfileConfig> = emptyMap()
    private var wakeWordRecordBuffer: ShortArray? = null
    private var wakeWordRecordFill = 0
    private var wakeWordRecordProfileId: String? = null
    private var lastWakeWordDiagnosticLogAt = 0L
    @Volatile private var assistantConversationUntilEpochMs = 0L
    @Volatile private var assistantReplyInterruptedPending = false
    @Volatile private var interruptedAssistantReplyPreview = ""
    @Volatile private var lastDirectAddressToOpenClawEpochMs = 0L
    // A wake word abre uma sessao de conversa, nao apenas uma janela de
    // follow-up. Enquanto ela estiver ativa, cada lote enviado ao OpenClaw
    // leva um sinal verificavel de que a fala e dirigida ao agente. A sessao
    // termina exclusivamente em uma parada deliberada (botao/API/punho).
    @Volatile private var wakeWordSessionActive = false
    @Volatile private var activeWakeWordPhrase = ""
    private val pendingDispatchLock = Any()
    @Volatile private var pendingOpenClawDispatchText: String = ""
    @Volatile private var pendingOpenClawDispatchState: GatewayUiState? = null
    private val pendingWakeWordDispatch = WakeWordDispatchAccumulator()
    private val transcriptWakeWordLock = Any()
    private val currentTranscriptWakeWord = WakeWordDispatchAccumulator()
    private val pendingOpenClawDispatchGeneration = AtomicLong(0L)
    @Volatile private var activeTranscriptionStartedAtEpochMs = 0L
    @Volatile private var activeOpenClawDispatchStartedAtEpochMs = 0L

    // "Agente processando": de quando o pedido e enviado ao OpenClaw ate o
    // reply chegar (handleOpenClawReply). Timestamp para expirar caso o reply
    // nunca volte (websocket caido), evitando o balao preso.
    @Volatile private var assistantProcessingSinceMs = 0L
    @Volatile private var assistantActivityMessageId = 0L
    @Volatile private var lastQueueReconcileAtEpochMs = 0L

    @Volatile private var transcriptClearTimeoutSecs = GatewaySettingsStore.DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS
    @Volatile private var openClawAccumulationWindowMs: Long = GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS * 1000L
    @Volatile private var lastTranscriptCommittedAtEpochMs = 0L
    private var transcriptClearScheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OpenClawGatewayClient.appContext = applicationContext
        settingsStore = GatewaySettingsStore(this)
        // Historico de conversa persistido: carrega o salvo e passa a gravar a
        // cada mensagem (sobrevive a reinicio do app e a `install -r`).
        val chatStore = com.sufficit.ai.gateway.history.ChatHistoryStore(this)
        GatewayRuntime.attachChatPersistence(chatStore.load()) { messages ->
            chatStore.save(messages)
        }
        // `assistantProcessing` fica no runtime da UI, enquanto o pedido e o
        // socket pertencem a este Service. Se o Android recriou o servico, o
        // pedido anterior ja nao pode receber resposta: nunca ressuscitar um
        // cartao "Processando" sem dono.
        clearStaleAssistantProcessingAfterServiceRestart()
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
        clientToolExecutor = Executors.newSingleThreadExecutor()
        wakeOnLanExecutor = Executors.newSingleThreadExecutor()
        textToSpeech = TextToSpeech(applicationContext, this)
        Log.i(TAG, "Camera gesture gate at create: open=${GatewayRuntime.cameraGestureGate().value}")
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
                    failAssistantProcessing(reason)
                }

                override fun onReply(reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply) {
                    handleOpenClawReply(reply)
                }

                override fun onError(message: String, throwable: Throwable?) {
                    Log.e(TAG, message, throwable)
                    failAssistantProcessing(message)
                }
            }
        )
        refreshSufficitMcpToolsAndPreferences()
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
                refreshSufficitMcpToolsAndPreferences()
                restartApiServer(s)
                return START_STICKY
            }
            ACTION_RESUME_AMBIENT -> {
                // Nivel 2: captura ambiente/transcricao. O nivel 1 usa o
                // mesmo AudioRecord, mas permanece vivo quando este modo e
                // pausado pela digitacao, pelo punho ou pela API.
                standbyMode = false
                GatewayRuntime.setTextInputModeActive(false)
                startForeground(NOTIFICATION_ID, createNotification(lastNotificationText))
                startCaptureIfNeeded()
                GatewayRuntime.setListening(
                    active = captureRunning.get(),
                    statusText = if (captureRunning.get()) {
                        "Escuta ambiente retomada."
                    } else {
                        "Iniciando escuta ambiente..."
                    }
                )
                refreshNotification("Escuta ambiente ativa")
                return START_STICKY
            }
            ACTION_STOP -> {
                // "Parar" afeta somente o nivel 2. O monitor local de wake
                // word (nivel 1) nunca e encerrado por uma acao de interface.
                startForeground(NOTIFICATION_ID, createNotification(lastNotificationText))
                enterWakeWordStandby("Escuta ambiente pausada.")
                return START_STICKY
            }

            else -> {
                standbyMode = true  // PADRÃO: Em espera, aguardando wake word
                startForeground(NOTIFICATION_ID, createNotification(lastNotificationText))
                startCaptureIfNeeded()
                if (captureRunning.get()) {
                    GatewayRuntime.setListening(
                        active = false,  // Não ativo até wake word
                        statusText = "Em espera. Diga a palavra de ativação para começar."
                    )
                    GatewayRuntime.updateWakeWord {
                        it.copy(status = "Em espera, escutando pela palavra de ativação.")
                    }
                    refreshNotification("Em espera | aguardando palavra de ativação")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        failAssistantProcessing("Processamento interrompido: o servico de audio foi encerrado.")
        stopApiServer()
        releaseCaptureWakeLock()
        shutdownCapture()
        releaseLocalWhisperEngine()
        releaseLocalSherpaOnnxEngine()
        speakerVerifier?.close()
        speakerVerifier = null
        elevenLabsRealtimeClient.close()
        speakerContinuityState = null
        transcriptionExecutor?.shutdownNow()
        transcriptionExecutor = null
        openClawExecutor?.shutdownNow()
        openClawExecutor = null
        clientToolExecutor?.shutdownNow()
        clientToolExecutor = null
        wakeOnLanExecutor?.shutdownNow()
        wakeOnLanExecutor = null
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
        clearWakeWordConversation("servico encerrado")
        assistantReplyInterruptedPending = false
        interruptedAssistantReplyPreview = ""
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
                    // A sintese para arquivo tambem emite callbacks. Ela nao
                    // representa som no alto-falante e, portanto, nao pode
                    // suprimir o microfone nem acender a tela.
                    if (utteranceId != null && pendingAssistantSpeechAudio.containsKey(utteranceId)) return
                    assistantSpeaking = true
                    assistantInterruptedByUser = false
                    assistantSpeechStartedAtEpochMs = System.currentTimeMillis()
                    assistantLeakBaselineRms = 0.0
                    assistantLeakBaselineSamples = 0
                    suppressMicrophoneUntilEpochMs = System.currentTimeMillis() + ASSISTANT_SPEECH_GRACE_MS
                    GatewayRuntime.update {
                        it.copy(
                            statusText = "Assistente falando.",
                            speakingBack = true
                        )
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
                    utteranceId?.let { id ->
                        pendingAssistantSpeechAudio.remove(id)?.let { pending ->
                            // Um WAV com somente 44 bytes e apenas cabecalho:
                            // acontece quando uma fala QUEUE_FLUSH cancela a
                            // sintese pendente. Nunca exibir play para ele.
                            if (pending.file.isFile && pending.file.length() > 44L) {
                                GatewayRuntime.attachChatMessageAudio(
                                    id = pending.messageId,
                                    audioPath = pending.file.absolutePath,
                                    audioDurationMs = readAudioDurationMs(pending.file),
                                    audioExpiresAtEpochMs = pending.expiresAtEpochMs
                                )
                            } else {
                                pending.file.delete()
                                Log.w(TAG, "Sintese do audio do agente terminou sem arquivo: $id")
                            }
                            return
                        }
                    }
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
                        it.copy(
                            statusText = if (it.listening) "Escutando ambiente." else it.statusText,
                            speakingBack = false
                        )
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { id ->
                        pendingAssistantSpeechAudio.remove(id)?.let { pending ->
                            pending.file.delete()
                            Log.w(TAG, "Falha ao salvar audio do agente: errorCode=$errorCode")
                            return
                        }
                    }
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
                    GatewayRuntime.update { it.copy(speakingBack = false) }
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
        // Copia reutilizavel do sinal cru para o nivel 1. O ganho de
        // transcricao pode cair ao detectar musica/ruido estavel, mas isso
        // nunca deve reduzir o alcance da wake word permanente.
        val wakeWordBuffer = ShortArray(bufferSize / 2)
        val loadedSettings = settingsStore?.load() ?: GatewaySettingsStore(this).load()
        var settings = normalizeRuntimeSettings(loadedSettings)
        transcriptClearTimeoutSecs = settings.transcriptClearTimeoutSecs
        openClawAccumulationWindowMs = settings.openClawAccumulationWindowSecs * 1000L
        if (settings != loadedSettings) {
            runCatching { settingsStore?.save(settings) ?: GatewaySettingsStore(this).save(settings) }
        }
        val initialGateOpen = resolveCameraGestureGateOpen(settings)
        Log.i(TAG, "Capture loop gate state: enabled=${settings.cameraGestureEnabled}, open=$initialGateOpen")
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
        if (
            settings.transcriptionMode == TranscriptionMode.REMOTE &&
            settings.whisperUrl.contains("api.elevenlabs.io", ignoreCase = true) &&
            settings.whisperAuthToken.isNotBlank()
        ) {
            transcriptionExecutor?.execute {
                runCatching { elevenLabsRealtimeClient.warmUp(settings.whisperAuthToken) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Pre-conexao ElevenLabs falhou; nova tentativa sera feita no segmento.",
                            it
                        )
                    }
            }
        }
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
                active = !standbyMode,
                statusText = if (standbyMode) {
                    "Monitor local ativo. Aguardando palavra de ativacao."
                } else {
                    "Microfone ativo. Aguardando fala."
                }
            )
            GatewayRuntime.update {
                it.copy(
                    microphoneCaptureActive = true,
                    transcriptionBackendLabel = when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> "Remoto"
                        TranscriptionMode.LOCAL -> {
                            when (settings.localExecutionMode) {
                                LocalExecutionMode.CPU -> "Local CPU"
                                LocalExecutionMode.NNAPI -> "Local NNAPI"
                            }
                        }
                        TranscriptionMode.COMPANION -> "App no aparelho"
                    },
                    transcriptionModelLabel = when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> settings.remoteModel.trim()
                        TranscriptionMode.LOCAL -> File(settings.localModelPath).name.trim()
                        TranscriptionMode.COMPANION -> "sufficit-mobile-ai-models"
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
                    GatewayRuntime.failStaleAgentActivityMessages(
                        nowEpochMs = now,
                        timeoutMs = ASSISTANT_PROCESSING_TIMEOUT_MS,
                        reason = "Tempo esgotado aguardando resposta do agente."
                    )
                }
                // Expira o balao de "processando" se o reply nunca voltar
                // (ex.: websocket caiu) — evita o balao preso.
                if (assistantProcessingSinceMs > 0L && now - assistantProcessingSinceMs > ASSISTANT_PROCESSING_TIMEOUT_MS) {
                    failAssistantProcessing("Tempo esgotado aguardando resposta do OpenClaw.")
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
                val quietFrameForGain = AdaptiveMicrophoneGainPolicy.isQuietFrame(
                    inputRms = rawRms,
                    inputPeakNormalized = rawPeakNormalized
                )
                val stableBackgroundNeedsAttenuation =
                    AdaptiveMicrophoneGainPolicy.shouldAttenuateStableBackground(
                        environmentStable = environmentStableForGain ||
                            environmentLooksStable ||
                            ambientNoiseDetected,
                        noiseFloorRms = noiseFloorRms,
                        inputRms = rawRms,
                        inputPeakNormalized = rawPeakNormalized
                    )
                // Gain reduction path: uses environmentStableForGain (no speechLikeFrame penalty,
                // no dynamicSpeechOverride guard) so that acoustically-stable environments like music
                // always reduce the gain, even when contrast keeps dynamicSpeechOverride true.
                val shouldReduceGainForAmbient =
                    !speechActive &&
                        environmentStableForGain &&
                        stableBackgroundNeedsAttenuation
                val shouldCompensateAmbientNoise =
                    stableBackgroundNeedsAttenuation &&
                        (
                            shouldReduceGainForAmbient ||
                                (!speechActive && !dynamicSpeechOverride &&
                                    (environmentLooksStable || ambientNoiseDetected))
                            )
                var dynamicMicrophoneGain = if (settings.microphoneAutoSensitivityEnabled) {
                    AdaptiveMicrophoneGainPolicy.resolveTargetGain(
                        peakGain = settings.microphoneGain,
                        minGain = settings.ambientGainMinGain,
                        noiseFloorRms = noiseFloorRms,
                        inputRms = rawRms,
                        speechLikeFrame = speechLikeFrameRaw && !shouldCompensateAmbientNoise,
                        speechActive = speechActive && !shouldCompensateAmbientNoise,
                        inputPeakNormalized = rawPeakNormalized
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
                    if (
                        AdaptiveMicrophoneGainPolicy.shouldRaiseGainImmediately(
                            currentGain = smoothedDynamicGain,
                            targetGain = dynamicMicrophoneGain,
                            inputRms = rawRms,
                            inputPeakNormalized = rawPeakNormalized,
                            speechLikeFrame = speechLikeFrameRaw,
                            speechActive = speechActive
                        )
                    ) {
                        // Ataque imediato para fala e recuperacao imediata no
                        // primeiro bloco silencioso. Assim uma wake word
                        // distante nao comeca com o ganho herdado de um ruido
                        // anterior. A QUEDA continua suave para nao bombear.
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
                        val gainMode = when {
                            quietFrameForGain -> "quiet-boost"
                            shouldCompensateAmbientNoise -> "noise-reduction"
                            ambientNoiseDetected || environmentLooksStable -> "ambient"
                            speechLikeFrameRaw || speechActive -> "speech"
                            else -> "normal"
                        }
                        Log.i(
                            TAG,
                            "Auto gain update: gain=${"%.2f".format(dynamicMicrophoneGain)} " +
                                "mode=$gainMode raw=${"%.4f".format(rawRms)} " +
                                "floor=${"%.4f".format(noiseFloorRms)} " +
                                "peak=${"%.3f".format(rawPeakNormalized)} " +
                                "contrast=${"%.4f".format(dynamicContrast)} " +
                                "score=${"%.2f".format(stabilityScore)}"
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
                val wakeWordGain = if (settings.microphoneAutoSensitivityEnabled) {
                    AdaptiveMicrophoneGainPolicy.resolveWakeWordGain(
                        peakGain = settings.microphoneGain,
                        minGain = settings.ambientGainMinGain,
                        inputPeakNormalized = rawPeakNormalized
                    )
                } else {
                    settings.microphoneGain
                }
                System.arraycopy(buffer, 0, wakeWordBuffer, 0, readCount)
                applyMicrophoneGain(wakeWordBuffer, readCount, wakeWordGain)
                applyMicrophoneGain(buffer, readCount, dynamicMicrophoneGain)
                // Depuracao: grava TUDO que o mic captou (pos-ganho), antes
                // de qualquer supressao/descarte da segmentacao.
                // So ativo em modo desenvolvimento por privacidade.
                if (settings.development) {
                    audioDebugStore.appendRolling(buffer, readCount)
                }
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
                    // Microfone suprimido durante a fala do assistente:
                    // espectro zerado para nao parecer que ha captura.
                    GatewayRuntime.setSpectrum(FLAT_SPECTRUM)
                    GatewayRuntime.update {
                        it.copy(
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
                handleWakeWordAudio(
                    buffer = wakeWordBuffer,
                    readCount = readCount,
                    now = now,
                    settings = settings,
                    appliedGain = wakeWordGain
                )

                if (standbyMode) {
                    // Nivel 1 permanente: o microfone continua aberto apenas
                    // para o detector local. Mesmo sem template cadastrado,
                    // nunca deixamos o audio vazar para VAD/transcricao do
                    // nivel 2 enquanto a UI esta em modo texto/standby.
                    clearPreRoll()
                    if (speechActive) {
                        speechActive = false
                        speechBuffer.reset()
                        segmentPreRollBytes = 0
                    }
                    GatewayRuntime.setSpectrum(FLAT_SPECTRUM)
                    GatewayRuntime.update {
                        it.copy(
                            speechDetected = false,
                            listening = false,
                            statusText = if (wakeWordEnabled) {
                                "Monitor local ativo. Diga a palavra de ativacao para retomar."
                            } else {
                                "Escuta ambiente pausada. Cadastre a palavra de ativacao local."
                            }
                        )
                    }
                    if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                        lastNotificationAt = now
                        refreshNotification(
                            if (wakeWordEnabled) {
                                "Nivel 1 ativo | aguardando palavra de ativacao"
                            } else {
                                "Nivel 1 ativo | palavra de ativacao sem amostra"
                            }
                        )
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
                    // On-device like LOCAL (no network round trip to account for), even though
                    // it runs in another app's process rather than in-process.
                    TranscriptionMode.LOCAL, TranscriptionMode.COMPANION -> settings.minSpeechCandidateFrames
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
                            GatewayRuntime.setSpectrum(uiSpectrum.toList())
                            GatewayRuntime.update {
                                it.copy(
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
                    // ha speechHoldMs.
                    if (
                        speechActive &&
                        now - lastSpeechAt > captureProfile.speechHoldMs
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
                    // O indicador nao prolonga mais segmentos durante a
                    // escuta ambiente: nesse estado a fala ja esta liberada.
                    if (now - lastSpeechAt > captureProfile.speechHoldMs) {
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
                // (a resposta interrompia o usuario). O indicador nao segura
                // mais o commit com o nivel 2 ativo; serve apenas para retomar
                // a escuta quando ela esta parada.
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

                GatewayRuntime.setSpectrum(uiSpectrum.toList())
                GatewayRuntime.update {
                    it.copy(
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
            GatewayRuntime.setListening(
                active = false,
                statusText = if (hasPendingAssistantWork()) {
                    "Aguardando resposta do assistente."
                } else {
                    "Servico parado."
                }
            )
            GatewayRuntime.update { it.copy(microphoneCaptureActive = false) }
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
        // A transcrição termina de forma assíncrona e pode voltar somente
        // depois de um punho/botão ter encerrado a sessão ao vivo. Preserve a
        // origem no instante em que ESTE áudio foi fechado.
        val segmentAwakened = wakeWordSessionActive
        val segmentWakeWord = activeWakeWordPhrase.trim()
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
            val queuedWav = buildWavPcm16(pcmBytes, SAMPLE_RATE_HZ, 1, 16)
            val queuedFile = savePendingAudioCapture(queuedWav)
            val queuedMessageId = GatewayRuntime.appendChatAudioMessage(
                queuedFile.absolutePath,
                durationMs,
                System.currentTimeMillis() + TRANSCRIPT_AUDIO_RETENTION_MS
            )
            GatewayRuntime.updateChatAudioMessage(
                id = queuedMessageId,
                state = ChatAudioState.ERROR,
                error = "Fila de transcrição cheia; trecho não processado"
            )
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
        // So ativo em modo desenvolvimento por privacidade.
        val debugSegmentName = if (settings.development) {
            audioDebugStore.saveSegment(
                wavBytes = wavBytes,
                durationMs = durationMs,
                preRollPrefixBytes = preRollPrefixBytes
            )
        } else {
            null
        }

        // Entra no histórico no instante em que o bloco é enfileirado. Assim,
        // vários trechos aparecem em sequência mesmo se o primeiro ainda
        // estiver ocupando o motor de transcrição.
        val pendingAudioFile = savePendingAudioCapture(wavBytes)
        val audioMessageId = GatewayRuntime.appendChatAudioMessage(
            audioPath = pendingAudioFile.absolutePath,
            audioDurationMs = durationMs,
            audioExpiresAtEpochMs = System.currentTimeMillis() + TRANSCRIPT_AUDIO_RETENTION_MS
        )

        executor.execute(
            QueuedTranscriptionTask(
                enqueuedAtEpochMs = System.currentTimeMillis(),
                onDropped = {
                    GatewayRuntime.updateChatAudioMessage(
                        id = audioMessageId,
                        state = ChatAudioState.ERROR,
                        error = "Tempo de espera excedido na fila"
                    )
                }
            ) {
                updateQueueCount()
                // Verificacao de locutor ("so a minha voz"): roda aqui no
                // executor de transcricao (custo de CPU fora da captura).
                // Retorna false quando o segmento foi consumido pelo cadastro
                // de voz ou rejeitado por nao ser a voz do usuario.
                if (!evaluateSpeakerVoiceGate(pcmBytes, preRollPrefixBytes)) {
                    GatewayRuntime.updateChatAudioMessage(
                        id = audioMessageId,
                        state = ChatAudioState.ERROR,
                        error = "Trecho rejeitado pela verificação de voz"
                    )
                    updateQueueCount()
                    return@QueuedTranscriptionTask
                }
                GatewayRuntime.update {
                    it.copy(
                        transcribing = true,
                        // O marcador representa a tentativa atual. Um erro
                        // antigo não deve continuar vermelho enquanto um novo
                        // trecho já está sendo processado com o modelo ativo.
                        lastError = null,
                        statusText = when (settings.transcriptionMode) {
                            TranscriptionMode.REMOTE -> "Enviando trecho para transcricao..."
                            TranscriptionMode.LOCAL -> {
                                when (settings.localExecutionMode) {
                                    LocalExecutionMode.CPU -> "Transcrevendo localmente na CPU..."
                                    LocalExecutionMode.NNAPI -> "Transcrevendo localmente via NNAPI..."
                                }
                            }
                            TranscriptionMode.COMPANION -> "Transcrevendo via app no aparelho..."
                        }
                    )
                }
                requestScreenAttentionUiOnly(settings)
                refreshNotification(
                    when (settings.transcriptionMode) {
                        TranscriptionMode.REMOTE -> "Enviando trecho para transcricao..."
                        TranscriptionMode.LOCAL -> {
                            when (settings.localExecutionMode) {
                                LocalExecutionMode.CPU -> "Transcrevendo localmente na CPU..."
                                LocalExecutionMode.NNAPI -> "Transcrevendo localmente via NNAPI..."
                            }
                        }
                        TranscriptionMode.COMPANION -> "Transcrevendo via app no aparelho..."
                    }
                )

                try {
                val rawResult = when (settings.transcriptionMode) {
                    TranscriptionMode.REMOTE -> {
                        val configCheck = checkWhisperConfiguration(settings.whisperUrl, settings.whisperAuthToken)
                        if (configCheck is WhisperConfigurationCheck.Blocked) {
                            throw IllegalStateException(configCheck.reason)
                        }

                        if (settings.whisperUrl.contains("api.elevenlabs.io", ignoreCase = true)) {
                            elevenLabsRealtimeClient.transcribe(
                                pcmBytes = pcmBytes,
                                apiKey = settings.whisperAuthToken,
                                previousText = buildTranscriptionPreviousText(
                                    settings.transcriptionContextMessageCount
                                ),
                                onPartialTranscript = { partial ->
                                    GatewayRuntime.updateChatAudioMessage(
                                        id = audioMessageId,
                                        text = partial,
                                        state = ChatAudioState.TRANSCRIBING
                                    )
                                }
                            )
                        } else {
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
                    }

                    TranscriptionMode.LOCAL -> {
                        transcribeLocalWithTimeout(settings, pcmBytes)
                    }

                    TranscriptionMode.COMPANION -> {
                        // Throws CompanionTranscriptionException on failure — handled as a
                        // dedicated, non-fatal branch in the catch block below (isNotReady vs.
                        // other IPC failures), same treatment as WhisperHttpException for REMOTE.
                        companionTranscriptionClient.transcribe(
                            wavBytes = wavBytes,
                            languageHint = "pt"
                        )
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
                    GatewayRuntime.updateChatAudioMessage(
                        id = audioMessageId,
                        text = correctedText,
                        state = ChatAudioState.TRANSCRIBED
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
                GatewayRuntime.updateChatAudioMessage(
                    id = audioMessageId,
                    text = correctedText.takeIf { it.isNotBlank() },
                    state = if (correctedText.isBlank()) ChatAudioState.ERROR else ChatAudioState.TRANSCRIBED,
                    error = if (correctedText.isBlank()) "Nenhum texto reconhecido" else null
                )
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

                // Este update pode fechar a frase anterior e chamar
                // appendTranscriptHistory sincronicamente. Coloque a bolha
                // deste segmento na fila antes, para o agendador atualiza-la
                // como SENDING em vez de criar uma segunda bolha de texto.
                if (correctedText.isNotBlank() && !neutralTranscriptMarker) {
                    completedTranscriptAudio.add(RetainedTranscriptAudio(audioMessageId))
                }
                GatewayRuntime.update {
                    val currentState = it
                    // Cada segmento de audio tem no maximo ~2 s. O texto do
                    // segmento seguinte quase sempre traz palavras novas, mas
                    // isso NAO e uma nova fala: e apenas a continuacao da
                    // mesma pessoa. Separar por novidade lexical fazia o
                    // OpenClaw receber apenas o ultimo fragmento (por exemplo
                    // "Wake on LAN") e ignorar o pedido incompleto. Uma nova
                    // janela so e aberta depois da pausa humana ja confirmada
                    // pelo capturador (phraseAdvanceReady).
                    val shouldAdvanceWindow = phraseAdvanceReady
                    if (shouldAdvanceWindow) {
                        val previousWakeContext = takeCurrentTranscriptWakeWord()
                        appendTranscriptHistory(
                            phrase = currentState.currentTranscript,
                            state = currentState,
                            wakeWordContext = previousWakeContext
                        )
                    }
                    if (correctedText.isNotBlank()) {
                        includeCurrentTranscriptWakeWord(
                            awakened = segmentAwakened,
                            wakeWord = segmentWakeWord
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
                // Finalizacao explicita de segmento: despacho deterministico.
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
                requestScreenAttentionUiOnly(settings)
                refreshNotification("Transcricao recebida.")
            } catch (ex: Exception) {
                if (ex is CancellationException || ex is InterruptedException || stopRequested.get()) {
                    Log.i(TAG, "Transcricao cancelada durante parada do servico.")
                    GatewayRuntime.updateChatAudioMessage(
                        id = audioMessageId,
                        state = ChatAudioState.ERROR,
                        error = "Transcrição interrompida"
                    )
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
                GatewayRuntime.updateChatAudioMessage(
                    id = audioMessageId,
                    state = ChatAudioState.ERROR,
                    error = msg.ifBlank { "Falha na transcrição" }.take(120)
                )
                if (ex is WhisperHttpException && ex.isAuthFailure) {
                    // Token invalido/ausente: distinto de indisponibilidade transitoria
                    // (ver o ramo generico HTTP 4xx/5xx abaixo) para nao confundir o
                    // usuario com "servidor fora do ar" quando o problema e o token.
                    GatewayRuntime.update {
                        it.copy(
                            transcribing = false,
                            lastError = msg.take(120),
                            statusText = "Whisper: token invalido ou ausente (HTTP ${ex.statusCode})."
                        )
                    }
                    refreshNotification("Whisper: token invalido.")
                } else if (ex is CompanionTranscriptionException) {
                    // App no aparelho ausente/sem modelo ativo/timeout de IPC: transitorio,
                    // igual ao ramo HTTP 4xx/5xx abaixo — nao derruba a escuta.
                    val statusText = if (ex.isNotReady) {
                        "App no aparelho sem modelo de transcricao ativo."
                    } else {
                        "App no aparelho indisponivel: ${msg.take(80)}"
                    }
                    GatewayRuntime.update {
                        it.copy(transcribing = false, lastError = msg.take(120), statusText = statusText)
                    }
                    refreshNotification(statusText)
                } else if (ex is IllegalStateException && (msg.contains("HTTP 4") || msg.contains("HTTP 5"))) {
                    // HTTP 4xx/5xx are transient (e.g. 429 capacity, 503 unavailable) — don't kill the service
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
                // A bolha e seu WAV permanecem no histórico mesmo em timeout
                // ou erro. A limpeza acontece somente após seis horas.
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

    private fun savePendingAudioCapture(wavBytes: ByteArray): File {
        val directory = File(filesDir, "transcription-audio")
        directory.mkdirs()
        val expiration = System.currentTimeMillis() - TRANSCRIPT_AUDIO_RETENTION_MS
        directory.listFiles()?.filter { it.lastModified() < expiration }?.forEach { it.delete() }
        return File.createTempFile("segment-", ".wav", directory).also { it.writeBytes(wavBytes) }
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

    private fun resolveCameraGestureGateOpen(settings: GatewaySettings): Boolean {
        return if (settings.cameraGestureEnabled) {
            GatewayRuntime.cameraGestureGate().value
        } else {
            true
        }
    }

    private fun isCameraGestureGateBlocking(settings: GatewaySettings): Boolean {
        return settings.cameraGestureEnabled && !resolveCameraGestureGateOpen(settings)
    }

    private fun updateCameraGestureGateStatus(settings: GatewaySettings) {
        val gateOpen = resolveCameraGestureGateOpen(settings)
        if (!settings.cameraGestureEnabled) {
            return
        }
        if (!GatewayRuntime.cameraGestureInteractionActive().value) {
            GatewayRuntime.setCameraGestureStatus("Gestos pausados fora do chat.")
            return
        }
        if (GatewayRuntime.state().value.textInputModeActive) {
            GatewayRuntime.setCameraGestureStatus("Gestos pausados durante a digitacao.")
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
            settings.transcriptionMode == TranscriptionMode.COMPANION -> CaptureProfile(
                speechHoldMs = COMPANION_SPEECH_HOLD_MS,
                maxSpeechSegmentMs = COMPANION_MAX_SPEECH_SEGMENT_MS,
                minTranscriptionMs = COMPANION_MIN_TRANSCRIPTION_MS,
                phraseBreakSilenceMs = COMPANION_PHRASE_BREAK_SILENCE_MS
            )
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

        val configuredProfile = if (!settings.development) {
            baseProfile
        } else {
            baseProfile.copy(
            speechHoldMs = (settings.debugSpeechHoldMs?.takeIf { it > 0 } ?: baseProfile.speechHoldMs.toInt()).toLong(),
            maxSpeechSegmentMs = (settings.debugMaxSpeechSegmentMs?.takeIf { it > 0 } ?: baseProfile.maxSpeechSegmentMs.toInt()).toLong(),
            minTranscriptionMs = (settings.debugMinTranscriptionMs?.takeIf { it > 0 } ?: baseProfile.minTranscriptionMs.toInt()).toLong(),
            phraseBreakSilenceMs = (settings.debugPhraseBreakSilenceMs?.takeIf { it > 0 } ?: baseProfile.phraseBreakSilenceMs.toInt()).toLong()
        )
        }
        return if (settings.transcriptionMode == TranscriptionMode.COMPANION) {
            configuredProfile.copy(
                speechHoldMs = maxOf(configuredProfile.speechHoldMs, COMPANION_SPEECH_HOLD_MS),
                maxSpeechSegmentMs = maxOf(configuredProfile.maxSpeechSegmentMs, COMPANION_MAX_SPEECH_SEGMENT_MS),
                phraseBreakSilenceMs = maxOf(
                    configuredProfile.phraseBreakSilenceMs,
                    COMPANION_PHRASE_BREAK_SILENCE_MS
                )
            )
        } else {
            configuredProfile
        }
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
        GatewayRuntime.update { it.copy(microphoneCaptureActive = false) }
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
                queuedTask.markDropped()
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
        private val onDropped: () -> Unit,
        private val block: () -> Unit
    ) : Runnable {
        fun markDropped() = onDropped()

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

    private fun buildTranscriptionPreviousText(messageCount: Int): String {
        if (messageCount <= 0) return ""
        val context = GatewayRuntime.chatMessages().value
            .asSequence()
            .filter { message ->
                message.role != ChatRole.SYSTEM &&
                    message.text.isNotBlank() &&
                    message.audioState != ChatAudioState.ERROR &&
                    message.audioState != ChatAudioState.TRANSCRIBING
            }
            .toList()
            .takeLast(messageCount.coerceIn(1, MAX_TRANSCRIPTION_CONTEXT_MESSAGES))
            .joinToString("\n") { message ->
                val speaker = if (message.role == ChatRole.ASSISTANT) "Assistente" else "Usuario"
                "$speaker: ${message.text.trim()}"
            }
        return context.takeLast(MAX_TRANSCRIPTION_CONTEXT_CHARS)
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

    /**
     * Uma wake word local e um comando de entrada no dialogo. Diferente da
     * janela curta aberta por um vocativo transcrito, essa sessao persiste
     * enquanto o usuario mantem a escuta de nivel 2 ativa. O metadado segue
     * em TODOS os lotes para o pre-agente nao reclassificar a fala posterior
     * como conversa ambiente por faltar o termo no texto.
     */
    private fun beginWakeWordConversation(phraseLabel: String) {
        wakeWordSessionActive = true
        activeWakeWordPhrase = phraseLabel.trim()
        markDirectAddressNow()
        Log.i(TAG, "Sessao de wake word iniciada: '${activeWakeWordPhrase.ifBlank { "(sem rotulo)" }}'.")
    }

    private fun includeCurrentTranscriptWakeWord(awakened: Boolean, wakeWord: String) {
        synchronized(transcriptWakeWordLock) {
            currentTranscriptWakeWord.include(awakened, wakeWord)
        }
    }

    private fun takeCurrentTranscriptWakeWord(): WakeWordDispatchSnapshot =
        synchronized(transcriptWakeWordLock) {
            currentTranscriptWakeWord.takeAndReset()
        }

    /** Encerra a sessao explicita e tambem elimina a janela curta residual. */
    private fun clearWakeWordConversation(reason: String) {
        val wasActive = wakeWordSessionActive
        wakeWordSessionActive = false
        activeWakeWordPhrase = ""
        assistantConversationUntilEpochMs = 0L
        lastDirectAddressToOpenClawEpochMs = 0L
        if (wasActive) {
            Log.i(TAG, "Sessao de wake word encerrada: $reason")
        }
    }

    /**
     * Coloca apenas a escuta ambiente (nivel 2) em espera. A captura fisica e
     * o PARTIAL_WAKE_LOCK continuam ativos para o detector local (nivel 1).
     */
    private fun enterWakeWordStandby(reason: String) {
        // Parada manual, API e punho fechado chegam por este unico caminho.
        // A proxima fala somente volta ao agente apos uma nova wake word,
        // gesto de enderecamento ou mensagem digitada.
        clearWakeWordConversation(reason)
        standbyMode = true
        if (!captureRunning.get()) {
            startCaptureIfNeeded()
        }
        GatewayRuntime.setSpectrum(FLAT_SPECTRUM)
        GatewayRuntime.setListening(
            active = false,
            statusText = "$reason Monitor local de wake word ativo."
        )
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        val config = store.loadConfig()
        val summaries = store.profileSummaries()
        val sampleCount = summaries.sumOf { it.sampleCount }
        GatewayRuntime.updateWakeWord {
            it.copy(
                enabled = config.enabled,
                sampleCount = sampleCount,
                profileCount = summaries.size,
                readyProfileCount = summaries.count { summary -> summary.ready },
                status = when {
                    !config.enabled -> "Monitor local ativo; wake words desabilitadas na configuracao."
                    summaries.isEmpty() -> "Monitor local ativo; cadastre uma wake word no Wake Lab."
                    !wakeWordEnabled -> "Monitor local ativo; complete tres gravacoes por wake word."
                    else -> {
                        val readyCount = summaries.count { summary -> summary.ready }
                        val noun = if (readyCount == 1) "chamada" else "chamadas"
                        "Monitor local ativo, aguardando $readyCount $noun."
                    }
                }
            )
        }
        refreshNotification(
            if (wakeWordEnabled) {
                "Nivel 1 ativo | aguardando wake words"
            } else {
                "Nivel 1 ativo | wake words requerem configuracao"
            }
        )
    }

    /**
     * Para apenas a captura quando ha um pedido em voo. O usuario deixa de
     * ser ouvido imediatamente, mas a conexao de saida permanece viva para
     * a resposta que ja foi solicitada nao desaparecer no meio do caminho.
     */
    private fun stopCaptureWhileAwaitingAssistant() {
        stopRequested.set(true)
        shutdownCapture()
        lastNotificationText = "Aguardando resposta do assistente."
        GatewayRuntime.setListening(active = false, statusText = lastNotificationText)
        GatewayRuntime.update {
            it.copy(openClawStatus = "OpenClaw processando a ultima mensagem enviada.")
        }
        refreshNotification(lastNotificationText)
        Log.i(TAG, "Captura parada; mantendo canal OpenClaw para resposta pendente.")
    }

    private fun hasPendingAssistantWork(): Boolean {
        if (GatewayRuntime.state().value.assistantProcessing || activeOpenClawDispatchStartedAtEpochMs > 0L) {
            return true
        }
        return synchronized(pendingDispatchLock) {
            pendingOpenClawDispatchText.isNotBlank()
        }
    }

    private fun syncWakeWordConfig() {
        val version = GatewayRuntime.wakeWordConfigVersion().value
        if (version == wakeWordConfigVersionSeen) {
            return
        }
        wakeWordConfigVersionSeen = version
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        var config = store.loadConfig()
        val samplesByProfile = config.profiles.associate { profile ->
            profile.id to store.loadSamples(profile.id)
        }
        fun detectorInputs(): List<WakeWordTemplateProfile> =
            config.profiles.filter { it.enabled }.map { profile ->
                WakeWordTemplateProfile(
                    profileId = profile.id,
                    samples = samplesByProfile[profile.id].orEmpty(),
                    threshold = profile.threshold
                )
            }

        var validTemplates = wakeWordDetector.configure(detectorInputs())
        var thresholdsChanged = false
        val updatedProfiles = config.profiles.map { profile ->
            if (!profile.enabled || !profile.autoThreshold) return@map profile
            val suggestion = wakeWordDetector.suggestedThreshold(profile.id) ?: return@map profile
            val calibratedThreshold = WakeWordThresholdPolicy.resolveAutomaticUpdate(
                currentThreshold = profile.threshold,
                suggestedThreshold = suggestion,
                automatic = profile.autoThreshold
            ) ?: return@map profile
            thresholdsChanged = true
            Log.i(
                TAG,
                "Wake word '${profile.phraseLabel}' limiar automatico: " +
                    "${"%.2f".format(profile.threshold)} -> ${"%.2f".format(calibratedThreshold)} " +
                    "(sugerido=${"%.2f".format(suggestion)})"
            )
            profile.copy(threshold = calibratedThreshold)
        }
        if (thresholdsChanged) {
            config = config.copy(profiles = updatedProfiles)
            store.saveConfig(config)
            validTemplates = wakeWordDetector.configure(
                config.profiles.filter { it.enabled }.map { profile ->
                    WakeWordTemplateProfile(
                        profileId = profile.id,
                        samples = samplesByProfile[profile.id].orEmpty(),
                        threshold = profile.threshold
                    )
                }
            )
            GatewayRuntime.bumpWakeWordConfigVersion()
        }
        wakeWordProfilesById = config.profiles.associateBy { it.id }
        val readyProfiles = config.profiles.filter { profile ->
            profile.enabled && (validTemplates[profile.id] ?: 0) >= WakeWordStore.REQUIRED_SAMPLES
        }
        val totalSamples = samplesByProfile.values.sumOf { it.size }
        wakeWordEnabled = config.enabled && readyProfiles.isNotEmpty()
        GatewayRuntime.updateWakeWord {
            it.copy(
                enabled = config.enabled,
                threshold = readyProfiles.firstOrNull()?.threshold ?: WakeWordConfig.DEFAULT_THRESHOLD,
                sampleCount = totalSamples,
                profileCount = config.profiles.size,
                readyProfileCount = readyProfiles.size,
                status = when {
                    !config.enabled -> "Wake words desativadas."
                    config.profiles.isEmpty() -> "Cadastre uma wake word no Wake Lab."
                    totalSamples == 0 -> "Grave tres vezes cada wake word."
                    readyProfiles.isEmpty() -> "Treinamento incompleto: sao necessarias tres gravacoes validas por chamada."
                    else -> "Escutando ${readyProfiles.size} wake word(s) com ${readyProfiles.sumOf { validTemplates[it.id] ?: 0 }} chaves validas."
                }
            )
        }
        Log.i(
            TAG,
            "Wake word config: enabled=${config.enabled} profiles=${config.profiles.size} " +
                "ready=${readyProfiles.size} samples=$totalSamples validTemplates=$validTemplates"
        )
    }

    private fun handleWakeWordAudio(
        buffer: ShortArray,
        readCount: Int,
        now: Long,
        settings: GatewaySettings,
        appliedGain: Double
    ) {
        syncWakeWordConfig()

        val requestedProfileId = GatewayRuntime.takeWakeWordRecordingRequest()
        if (requestedProfileId != null) {
            val requestedProfile = storeProfile(requestedProfileId)
            if (requestedProfile == null) {
                GatewayRuntime.updateWakeWord {
                    it.copy(recording = false, recordingProfileId = null, status = "Wake word nao encontrada.")
                }
                return
            }
            wakeWordRecordBuffer = ShortArray(WAKE_WORD_RECORD_SAMPLES)
            wakeWordRecordFill = 0
            wakeWordRecordProfileId = requestedProfileId
            GatewayRuntime.updateWakeWord {
                it.copy(
                    recording = true,
                    recordingProfileId = requestedProfileId,
                    status = "Gravando '${requestedProfile.phraseLabel}'... fale agora."
                )
            }
        }
        val recordBuffer = wakeWordRecordBuffer
        if (recordBuffer != null) {
            val toCopy = minOf(readCount, recordBuffer.size - wakeWordRecordFill)
            System.arraycopy(buffer, 0, recordBuffer, wakeWordRecordFill, toCopy)
            wakeWordRecordFill += toCopy
            if (wakeWordRecordFill >= recordBuffer.size) {
                wakeWordRecordBuffer = null
                val profileId = wakeWordRecordProfileId
                wakeWordRecordProfileId = null
                finishWakeWordRecording(profileId, recordBuffer)
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
                    "rms=${"%.4f".format(chunkRms)} gain=${"%.2f".format(appliedGain)} " +
                    "matched=${result.matched}"
            )
        }
        if (result.matched) {
            val matchedProfileId = result.matchedProfileId ?: return
            val matchedProfile = wakeWordProfilesById[matchedProfileId] ?: return
            val phraseLabel = matchedProfile.phraseLabel
            // A palavra de ativacao serve para ACORDAR/retomar:
            //  - standby (escuta parada): retoma o microfone;
            //  - gate do gesto fechado: abre o microfone;
            //  - TELA APAGADA: acende a tela (acordar o aparelho), mesmo com
            //    a escuta ja ativa — e o caso "chuchu" com o telefone dormindo.
            // Somente com a escuta ativa E a tela acesa o "chuchu" no meio da
            // conversa e ignorado (nao interfere no papo em andamento).
            val gateBlocked = isCameraGestureGateBlocking(settings)
            val screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false
            if (!standbyMode && !gateBlocked && !screenOff) {
                Log.i(
                    TAG,
                    "Wake word '$phraseLabel' ignorada: escuta ativa com tela acesa " +
                        "(dist=${"%.2f".format(result.distance)})."
                )
                return
            }
            Log.i(TAG, "Wake word '$phraseLabel' detectada (dist=${"%.2f".format(result.distance)}).")
            GatewayRuntime.updateWakeWord {
                it.copy(
                    lastMatchAtEpochMs = now,
                    lastMatchedProfileId = matchedProfileId,
                    lastMatchedPhraseLabel = phraseLabel,
                    status = "'$phraseLabel' detectada! Abrindo microfone."
                )
            }
            // A wake word tem precedencia sobre a digitacao: sai do modo
            // texto, reabilita os gestos e restaura o espectro do nivel 2.
            GatewayRuntime.setTextInputModeActive(false)
            if (standbyMode) {
                standbyMode = false
                GatewayRuntime.setListening(
                    active = true,
                    statusText = "Palavra detectada. Microfone retomado."
                )
                refreshNotification("Palavra detectada. Microfone retomado.")
            }
            // Chamar pela palavra de ativacao abre uma sessao dirigida ao
            // assistente. A fala seguinte (e as demais) nao precisa repetir
            // o termo ate o usuario parar a escuta ou fechar o punho.
            beginWakeWordConversation(phraseLabel)
            GatewayRuntime.setCameraGestureGateOpen(true)
            GatewayRuntime.setCameraGestureStatus("Palavra de ativacao detectada. Abrindo microfone.")
            // Traz o app ao primeiro plano tambem quando a tela ja esta acesa
            // mas outra Activity esta visivel. Com a tela apagada, o caminho
            // full-screen abaixo complementa esta tentativa.
            com.sufficit.ai.gateway.MainActivity.requestWakeScreen(this)
            if (screenOff) {
                // Summon explicito com a tela apagada: acende sempre, mesmo em
                // ScreenMode.ALWAYS_OFF (que requestScreenAttention ignoraria).
                val holdMs = (settings.screenHoldSeconds.coerceAtLeast(5)) * 1000L
                GatewayRuntime.requestScreenAttention(holdMs)
                wakeDevice(holdMs)
            } else {
                requestScreenAttention(settings)
            }
        }
    }

    private fun storeProfile(profileId: String): WakeWordProfileConfig? {
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        return store.loadConfig().profiles.firstOrNull { it.id == profileId }
    }

    private fun finishWakeWordRecording(profileId: String?, samples: ShortArray) {
        val store = wakeWordStore ?: WakeWordStore(this).also { wakeWordStore = it }
        val profile = profileId?.let(::storeProfile)
        val valid = profile != null && wakeWordDetector.isValidSample(samples)
        val saved = profile?.takeIf { valid }?.let { store.saveSample(it.id, samples) } ?: false
        val sampleCount = profile?.let { store.sampleCount(it.id) } ?: 0
        GatewayRuntime.updateWakeWord {
            it.copy(
                recording = false,
                recordingProfileId = null,
                status = when {
                    profile == null -> "Wake word nao encontrada; tente novamente."
                    !valid -> "Nao consegui isolar a chamada. Fale novamente em ambiente mais silencioso."
                    saved -> "Chave $sampleCount/${WakeWordStore.REQUIRED_SAMPLES} de '${profile.phraseLabel}' gravada."
                    else -> "Falha ao salvar a gravacao; tente novamente."
                }
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

    /**
     * Atencao de tela SEM acender o display fisico: so atualiza o flow de UI
     * (mantem a tela acesa enquanto o app esta em primeiro plano). Usado em
     * eventos de baixa prioridade — transcricao em curso/recebida — para que
     * ruido ambiente NAO fique acendendo a tela do aparelho a toa.
     */
    private fun requestScreenAttentionUiOnly(settings: GatewaySettings) {
        if (settings.screenMode == com.sufficit.ai.gateway.config.ScreenMode.ALWAYS_OFF) return
        GatewayRuntime.requestScreenAttention(settings.screenHoldSeconds * 1000L)
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

        // So acende/traz a tela quando ela esta REALMENTE apagada. Com a tela
        // ja ligada nao ha nada a acordar — postar a notificacao full-screen e
        // trazer a Activity ao topo a cada atencao do assistente gerava spam de
        // push (sonoro) e roubava o foco do app.
        if (!powerManager.isInteractive) {
            // Caminho confiavel a partir de um servico em segundo plano: uma
            // notificacao com FULL-SCREEN INTENT. O proprio sistema lanca a
            // Activity (acende a tela, mostra sobre o bloqueio), contornando as
            // restricoes de Background Activity Launch do Android 12+. A
            // MainActivity tem setTurnScreenOn/setShowWhenLocked. Canal SILENCIOSO.
            wakeScreenViaFullScreenIntent()
            // Tentativa direta tambem (best-effort quando isento de BAL).
            com.sufficit.ai.gateway.MainActivity.requestWakeScreen(this)
        }

        // Wake lock parcial curto so para garantir CPU enquanto a Activity sobe
        // e o pipeline de captura/TTS reage (nao acende a tela por si).
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:screen-attention"
        )
        wakeLock.acquire(holdMs.coerceIn(1_000L, 10_000L))
    }

    /**
     * Posta uma notificacao com full-screen intent para a MainActivity num
     * canal de IMPORTANCE_HIGH. Com a tela apagada/bloqueada, o sistema lanca a
     * Activity em tela cheia (acende a tela). Cancelada logo apos para nao
     * deixar uma notificacao persistente — o efeito de acender ja ocorreu.
     */
    private fun wakeScreenViaFullScreenIntent() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal antigo era IMPORTANCE_HIGH COM som -> push sonoro a cada
            // acordar. Remove e recria SILENCIOSO (o som de canal nao pode ser
            // alterado em um canal ja existente, por isso o id v2). A
            // importancia segue HIGH para o full-screen intent poder acender a
            // tela, mas sem som nem vibracao.
            runCatching { manager.deleteNotificationChannel("room-audio-gateway-wake") }
            val channel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "OpenClaw acordar tela",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Acende a tela quando o aparelho esta apagado e o assistente e chamado."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_WAKE_SCREEN, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OpenClaw")
            .setContentText("Palavra de ativacao detectada")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()

        manager.notify(WAKE_NOTIFICATION_ID, notification)
        // Remove a notificacao logo em seguida: o full-screen intent ja acendeu
        // a tela; nao queremos um banner persistente alem do servico.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { manager.cancel(WAKE_NOTIFICATION_ID) }
        }, 3_000L)
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
            TranscriptionMode.LOCAL, TranscriptionMode.COMPANION -> 0.015
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
        val wakeWordContext = if (hadContent) {
            takeCurrentTranscriptWakeWord()
        } else {
            null
        }
        appendTranscriptHistory(
            phrase = snapshot.currentTranscript,
            state = snapshot,
            immediate = true,
            wakeWordContext = wakeWordContext
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
        immediate: Boolean = false,
        wakeWordContext: WakeWordDispatchSnapshot? = null
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
            immediate = immediate,
            wakeWordContext = wakeWordContext
        )
    }

    private fun scheduleTranscriptDispatchToOpenClaw(
        phrase: String,
        state: GatewayUiState,
        immediate: Boolean = false,
        wakeWordContext: WakeWordDispatchSnapshot? = null
    ) {
        val normalizedPhrase = phrase.trim()
        if (normalizedPhrase.isBlank()) {
            return
        }

        // Tela de configuracao aberta: o agente nao deve se intrometer (ex.:
        // durante o cadastro de voz). Nao despacha nem cria bolha de usuario.
        if (GatewayRuntime.configScreenActive().value) {
            Log.i(TAG, "Despacho ao OpenClaw suprimido: tela de configuracao ativa.")
            return
        }

        // Bolha do usuario no chat POR FRASE finalizada, aqui na entrada do
        // agendador — nao no despacho: o despacho acumula varias frases na
        // janela de envio e juntava tudo num unico balao com atraso.
        if (!TranscriptTextPipeline.isNeutralMarkerTranscript(normalizedPhrase)) {
            var updatedAudio = false
            while (true) {
                val audio = completedTranscriptAudio.poll() ?: break
                GatewayRuntime.updateChatAudioMessage(
                    id = audio.messageId,
                    state = ChatAudioState.SENDING
                )
                updatedAudio = true
            }
            // O servico pode ser recriado entre transcrever e enviar. Nesse
            // caso a fila acima e perdida, mas a bolha TRANSCRIBED ficou
            // persistida; recupere-a pelo texto do turno para o ✓✓ continuar
            // fiel ao envio real, sem criar uma bolha duplicada.
            val recoveredPersistedAudio =
                GatewayRuntime.markRecentTranscribedUserAudioAsSending(normalizedPhrase)
            if (
                !updatedAudio &&
                    !recoveredPersistedAudio &&
                    !GatewayRuntime.hasRecentUserAudioCovering(normalizedPhrase)
            ) {
                GatewayRuntime.appendChatMessage(ChatRole.USER, normalizedPhrase)
            }
        }

        val generation = pendingOpenClawDispatchGeneration.incrementAndGet()
        // A parada manual pode acontecer logo depois da fala e antes do fim
        // da janela de acumulacao. Preserve no lote a wake word que estava
        // ativa quando o trecho entrou na fila; limpar a sessao deve afetar
        // apenas falas futuras, nunca reclassificar este comando como ambiente.
        val phraseAwakened = wakeWordContext?.awakened ?: wakeWordSessionActive
        val phraseWakeWord = when {
            !phraseAwakened -> ""
            wakeWordContext != null -> wakeWordContext.wakeWord.trim()
            else -> activeWakeWordPhrase.trim()
        }
        Log.i(
            TAG,
            "Contexto wake preservado no lote: awakened=$phraseAwakened " +
                "wakeWord=${phraseWakeWord.ifBlank { "(nenhuma)" }}"
        )
        val pendingLabel = synchronized(pendingDispatchLock) {
            pendingOpenClawDispatchText = mergePendingDispatchText(
                existing = pendingOpenClawDispatchText,
                incoming = normalizedPhrase
            )
            pendingOpenClawDispatchState = state
            pendingWakeWordDispatch.include(phraseAwakened, phraseWakeWord)
            pendingOpenClawDispatchText
        }
        // Feedback persistente antes de qualquer espera, avaliação local ou
        // acesso à rede. A mesma bolha é movida para depois de cada novo
        // trecho do lote; assim a mensagem do usuário nunca fica sozinha.
        assistantActivityMessageId = GatewayRuntime.upsertAgentActivityMessage(
            existingId = assistantActivityMessageId,
            dispatchedText = pendingLabel,
            state = ChatAgentActivityState.QUEUED,
            statusText = "Preparando seu pedido para o agente…"
        )
        // Mantém também o estado leve usado pelo cabeçalho/notificação.
        setAssistantProcessing(true, pendingLabel)
        updateOpenClawDispatchQueueCount()

        val executor = openClawExecutor ?: run {
            failAssistantProcessing("Fila do OpenClaw indisponivel.")
            return
        }
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
            val dispatchAwakened: Boolean
            val dispatchWakeWord: String
            synchronized(pendingDispatchLock) {
                dispatchText = pendingOpenClawDispatchText.trim()
                dispatchState = pendingOpenClawDispatchState ?: return@execute
                val wakeWordSnapshot = pendingWakeWordDispatch.takeAndReset()
                dispatchAwakened = wakeWordSnapshot.awakened
                dispatchWakeWord = wakeWordSnapshot.wakeWord
                pendingOpenClawDispatchText = ""
                pendingOpenClawDispatchState = null
            }
            activeOpenClawDispatchStartedAtEpochMs = if (dispatchText.isBlank()) 0L else System.currentTimeMillis()
            updateOpenClawDispatchQueueCount()

            if (dispatchText.isBlank()) {
                failAssistantProcessing("O turno ficou vazio antes do envio.")
                return@execute
            }

            assistantActivityMessageId = GatewayRuntime.upsertAgentActivityMessage(
                existingId = assistantActivityMessageId,
                dispatchedText = dispatchText,
                state = ChatAgentActivityState.PROCESSING,
                statusText = "Processando seu pedido…"
            )
            dispatchTranscriptToOpenClaw(
                phrase = dispatchText,
                state = dispatchState,
                awakened = dispatchAwakened,
                wakeWord = dispatchWakeWord
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
        if (!openClawHandshakeStarted.compareAndSet(false, true)) {
            return
        }
        val executor = openClawExecutor ?: return
        executor.execute {
            Log.i(TAG, "Iniciando handshake OpenClaw.")
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
                Log.w(
                    TAG,
                    "Handshake OpenClaw ignorado: " +
                        "url=${settings.openClawGatewayUrl.isNotBlank()}, " +
                        "gatewayToken=${settings.openClawGatewayToken.isNotBlank()}, " +
                        "deviceToken=${settings.openClawDeviceToken.isNotBlank()}, " +
                        "sessionKey=${settings.openClawSessionKey.isNotBlank()}."
                )
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
        val startedAt = if (active) System.currentTimeMillis() else 0L
        assistantProcessingSinceMs = startedAt
        GatewayRuntime.update {
            it.copy(
                assistantProcessing = active,
                assistantProcessingLabel = if (active) label.trim() else "",
                assistantProcessingStartedAtEpochMs = startedAt
            )
        }
    }

    private fun clearStaleAssistantProcessingAfterServiceRestart() {
        val state = GatewayRuntime.state().value
        if (!state.assistantProcessing) return
        val handoffAgeMs = System.currentTimeMillis() - state.assistantProcessingStartedAtEpochMs
        if (
            state.assistantProcessingStartedAtEpochMs > 0L &&
                handoffAgeMs in 0..ASSISTANT_PROCESSING_HANDOFF_GRACE_MS
        ) {
            assistantProcessingSinceMs = state.assistantProcessingStartedAtEpochMs
            Log.i(TAG, "Bolha pendente adotada pelo servico apos ${handoffAgeMs}ms.")
            return
        }
        failAssistantProcessing("Processamento interrompido: o servico anterior foi reiniciado.")
    }

    /** Fecha a bolha provisoria e deixa uma causa visivel, em vez de apagar
     * silenciosamente o pedido ou deixa-lo em "Processando" para sempre. */
    private fun failAssistantProcessing(reason: String) {
        val activityId = assistantActivityMessageId
        val activityFailed = activityId > 0L &&
            GatewayRuntime.failAgentActivityMessage(activityId, reason)
        if (activityFailed) {
            assistantActivityMessageId = 0L
        }
        val wasProcessing = GatewayRuntime.state().value.assistantProcessing ||
            assistantProcessingSinceMs > 0L ||
            activityFailed
        assistantProcessingSinceMs = 0L
        activeOpenClawDispatchStartedAtEpochMs = 0L
        updateOpenClawDispatchQueueCount()
        if (!wasProcessing) {
            GatewayRuntime.update { it.copy(openClawStatus = reason) }
            return
        }
        setAssistantProcessing(false)
        GatewayRuntime.update {
            it.copy(
                openClawStatus = reason,
                systemInfoMessage = "Nao foi possivel receber a resposta do agente. Tente enviar novamente.",
                systemInfoMessageUntilEpochMs = System.currentTimeMillis() + OPENCLAW_FAILURE_NOTICE_MS,
                lastAssistantReplyNeedsAttention = false,
                lastAssistantReplyTags = emptyList(),
                lastAssistantReplyConfidence = null,
                lastAssistantReplyOverlap = false
            )
        }
    }

    private fun dispatchTranscriptToOpenClaw(
        phrase: String,
        state: GatewayUiState,
        awakened: Boolean,
        wakeWord: String
    ) {
        // Despacho ja agendado mas tela de configuracao abriu nesse meio tempo:
        // cancela para o agente nao se intrometer.
        if (GatewayRuntime.configScreenActive().value) {
            Log.i(TAG, "Despacho em voo cancelado: tela de configuracao ativa.")
            failAssistantProcessing(
                "O envio foi cancelado porque uma tela de configuração foi aberta."
            )
            return
        }
        val store = settingsStore ?: GatewaySettingsStore(this)
        val settings = runCatching { store.load() }.getOrElse {
            Log.w(TAG, "Falha ao carregar configuracao do OpenClaw", it)
            failAssistantProcessing("Falha ao carregar configuracao do OpenClaw.")
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
            failAssistantProcessing("OpenClaw desativado na configuracao.")
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

        val executor = openClawExecutor ?: run {
            failAssistantProcessing("Fila do OpenClaw indisponivel.")
            return
        }
        executor.execute {
            try {
                val segmentId = persistentOpenClawConnection?.sendTranscript(
                    config = buildOpenClawConfig(
                        settings = settings,
                        state = state,
                        voiceDecision = decision,
                        transcript = phrase,
                        awakened = awakened,
                        wakeWord = wakeWord
                    ),
                    transcript = phrase
                )
                GatewayRuntime.update {
                    it.copy(
                        openClawStatus = "OpenClaw enviou frase final${segmentId?.let { " ($it)" }.orEmpty()}."
                    )
                }
                // A bolha ja existe desde a entrada no agendador; permanece
                // ate handleOpenClawReply concluir este turno.
            } catch (ex: Exception) {
                Log.e(TAG, "Falha ao enviar frase para OpenClaw", ex)
                failAssistantProcessing("Falha OpenClaw: ${ex.message ?: ex.javaClass.simpleName}")
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
        transcript: String? = null,
        awakenedOverride: Boolean? = null,
        wakeWordOverride: String? = null
    ): JSONObject {
        val awakened = awakenedOverride ?: wakeWordSessionActive
        val wakeWordPhrase = when (awakenedOverride) {
            true -> wakeWordOverride.orEmpty().trim()
            false -> ""
            null -> activeWakeWordPhrase.trim()
        }
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
            // Contrato deliberadamente minimo para a wake word: a chamada
            // reconhecida e se a escuta atual foi acordada por ela. Nada de
            // flags paralelas de sessao ou origem de endereco.
            put("awakened", awakened)
            put("wakeWord", if (awakened && wakeWordPhrase.isNotBlank()) wakeWordPhrase else JSONObject.NULL)
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
            // Catalogo de ferramentas que o agente pode acionar no aparelho:
            // ele escolhe emitindo {"tool":"<name>",...} no array "actions" da
            // resposta. Executadas pela conexao de saida — sem rede de entrada.
            put("availableTools", buildAgentToolCatalog())
            voiceDecision.matchedWakeTerm?.let { put("matchedWakeTerm", it) }
            voiceDecision.secondsSinceDirectAddress?.let { put("secondsSinceDirectAddress", it) }
            if (interruptedReplyContext.isNotBlank()) {
                put("interruptedAssistantReplyPreview", interruptedReplyContext)
            }
        }
    }

    /**
     * Lista as ferramentas que o agente pode acionar emitindo "actions" na
     * resposta. Enviada em todo metadata para o agente saber o que existe.
     */
    private fun buildAgentToolCatalog(): org.json.JSONArray {
        fun tool(name: String, desc: String, args: JSONObject? = null) = JSONObject().apply {
            put("tool", name)
            put("description", desc)
            if (args != null) put("args", args)
        }
        return org.json.JSONArray().apply {
            put(tool("photo", "Tira uma foto com a camera e mostra no chat como sua. Acorde a tela antes (tool wake).",
                JSONObject().put("camera", "front|back (padrao front)").put("label", "legenda opcional")))
            put(tool("screenshot", "Captura a tela do app e mostra no chat.",
                JSONObject().put("label", "legenda opcional")))
            put(tool("wake", "Acorda/acende a tela e retoma a escuta."))
            put(tool("effect", "Dispara um flash visual + som de aviso.",
                JSONObject().put("label", "texto do aviso")))
            put(tool("say", "Fala um texto pelo TTS.", JSONObject().put("text", "o que falar")))
            put(tool("listen", "Inicia/retoma a escuta do microfone."))
            put(tool("standby", "Coloca em espera (so palavra de ativacao reabre)."))
            put(tool("interrupt", "Interrompe a fala do assistente em andamento."))
            put(tool("config", "Edita configuracoes do app.", JSONObject().put("patch", "{chave:valor}")))
            put(tool("wakeonlan", "Liga um computador na mesma rede por Wake-on-LAN. Despacha o Magic Packet por broadcast da sub-rede, broadcast limitado, unicast do ultimo IP e portas UDP 9/7; monitora por ate 30 segundos e distingue pacote despachado de equipamento confirmado.",
                JSONObject()
                    .put("mac", "MAC do computador, ex.: AA:BB:CC:DD:EE:FF")
                    .put("name", "nome amigavel opcional do dispositivo")
                    .put("ip", "ultimo IPv4 opcional; tambem recebe Magic Packet unicast")
                    .put("broadcast", "IPv4 de broadcast opcional; padrao detecta a rede Wi-Fi")
                    .put("port", "porta UDP preferida opcional; portas 9 e 7 tambem sao cobertas")
                    .put("repeat", "repeticoes opcionais de 1 a 5, padrao 3")
                    .put("waitSeconds", "monitoramento entre 5 e 60 segundos; padrao 30")))
            put(tool("discover_wol_devices", "Descobre MACs/IPs Wake-on-LAN na rede local sem senha de roteador. Consulta automaticamente o companion Sufficit na LAN e preserva os MACs aprendidos.",
                JSONObject().put("probe", "true para consulta ativa e companion local (padrao true)")))
            put(tool("verify_wol_devices", "Envia Wake-on-LAN a todos os MACs cadastrados (ou aos MACs informados), monitora a inicializacao e verifica quais ficaram acessiveis. Use para identificar novos dispositivos antes de nomea-los.",
                JSONObject().put("macs", "lista opcional de MACs; vazio testa todos")
                    .put("waitSeconds", "monitoramento entre 5 e 60 segundos; padrao 30")))
            put(tool("name_wol_device", "Da um nome a um MAC aprendido e salva a preferencia na memoria Sufficit autenticada.",
                JSONObject()
                    .put("mac", "MAC ja presente no inventario")
                    .put("name", "nome escolhido pelo usuario")))
            put(tool("clearChat", "Limpa o historico de conversa exibido."))
            sufficitMcpToolBridge.appendClientToolCatalog(this)
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

    private fun recordOpenClawDeliveryAudit(
        reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply,
        displayReply: String
    ): Long {
        val evaluatedTranscript = reply.transcript?.trim().orEmpty()
        if (evaluatedTranscript.isBlank()) return 0L

        val action = reply.preAgentAction?.lowercase()
        val (state, reason) = when {
            !reply.errorText.isNullOrBlank() ->
                ChatDeliveryState.FAILED to "agent_error"

            action == "discard" || action == "ignore" ->
                ChatDeliveryState.IGNORED to (reply.preAgentReason ?: action)

            action == "hold" || action == "review" || reply.needsAttention ->
                ChatDeliveryState.HELD_FOR_REVIEW to (reply.preAgentReason ?: action ?: "needs_attention")

            reply.actions.isNotEmpty() ->
                ChatDeliveryState.ACTION_EXECUTED to (reply.preAgentReason ?: "device_action")

            displayReply.isNotBlank() ->
                ChatDeliveryState.AGENT_REPLIED to (reply.preAgentReason ?: "agent_replied")

            else ->
                ChatDeliveryState.NO_AGENT_REPLY to (reply.preAgentReason ?: "final_agent_empty")
        }
        val decisionText = when (state) {
            ChatDeliveryState.IGNORED -> displayReply.trim().ifBlank {
                "O pré-agente decidiu ignorar este turno."
            }
            ChatDeliveryState.HELD_FOR_REVIEW -> displayReply.trim().ifBlank {
                "O pré-agente reteve o turno para confirmação de contexto."
            }
            ChatDeliveryState.AGENT_REPLIED ->
                "O conjunto foi aceito e encaminhado ao agente final."
            ChatDeliveryState.ACTION_EXECUTED ->
                "O conjunto foi aceito e gerou uma ação no aparelho."
            ChatDeliveryState.NO_AGENT_REPLY ->
                "O conjunto foi encaminhado, mas o agente não gerou uma resposta."
            ChatDeliveryState.FAILED ->
                "Não consegui concluir o processamento deste conjunto."
            else -> "A decisão do turno foi registrada."
        }
        return GatewayRuntime.appendDeliveryAuditMessage(
            dispatchedText = evaluatedTranscript,
            state = state,
            reason = reason,
            tags = reply.tags,
            decisionText = decisionText
        )
    }

    private fun isPreAgentDecisionOnly(
        reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply
    ): Boolean {
        if (reply.shouldForwardToFinalAgent != false) return false
        return reply.preAgentAction?.trim()?.lowercase() in setOf(
            "discard", "ignore", "hold", "review"
        )
    }

    private fun handleOpenClawReply(reply: com.sufficit.ai.gateway.openclaw.OpenClawGatewayReply) {
        var replyActivityId = GatewayRuntime.findAgentActivityMessageId(
            reply.transcript.orEmpty()
        ).takeIf { it > 0L } ?: assistantActivityMessageId
        // Resposta chegou: encerra o balao de "processando".
        setAssistantProcessing(false)
        // Falha do agente no servidor: detalhe cru vem no campo "error" do
        // envelope. Vai para log e status — nunca vira bolha de chat e o TTS
        // nunca le o texto cru; o usuario ouve um aviso curto e amigavel.
        reply.errorText?.takeIf { it.isNotBlank() }?.let { error ->
            val auditMessageId = recordOpenClawDeliveryAudit(reply, displayReply = "")
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
            if (auditMessageId > 0L) {
                finishAgentActivity(replyActivityId)
            } else {
                failAgentActivity(
                    replyActivityId,
                    "O agente remoto falhou ao processar o pedido."
                )
            }
            return
        }
        val loadedSettings = runCatching {
            settingsStore?.load() ?: GatewaySettingsStore(this).load()
        }.getOrElse {
            Log.w(TAG, "Falha ao carregar configuracao do OpenClaw para reply", it)
            failAgentActivity(
                replyActivityId,
                "A resposta chegou, mas não consegui carregar a configuração local."
            )
            return
        }
        val patchResult = applyRemoteSettingsPatchIfNeeded(reply, loadedSettings)
        val settings = patchResult?.settings ?: loadedSettings
        val patchSummary = patchResult
            ?.appliedKeys
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        val currentState = GatewayRuntime.state().value
        val internalEvent = reply.internalEvent
        val assistantReply = reply.replyText.trim()
        val spokenReply = reply.spokenReplyText.ifBlank { assistantReply }
        val displayReply = spokenReply.ifBlank { assistantReply }
        // Manutencao de contexto e um evento do motor, nao uma decisao sobre
        // a fala do usuario. Nao a transforma em auditoria de entrega nem em
        // resposta do assistente.
        val auditMessageId = if (internalEvent == null) {
            recordOpenClawDeliveryAudit(reply, displayReply)
        } else {
            0L
        }
        val requiresAttention = reply.needsAttention
        val blockingAnnouncement = when {
            requiresAttention -> buildBlockingAnnouncementMessage(reply)
            !reply.shouldSpeak && !reply.speakBlockReason.isNullOrBlank() -> reply.speakBlockReason
            else -> null
        }
        val systemInfoMessage = when {
            internalEvent != null -> internalEvent.systemMessage
            reply.isSystemInfo && assistantReply.isNotBlank() -> assistantReply
            else -> null
        }
        if ((assistantReply.isNotBlank() || requiresAttention) && internalEvent == null) {
            assistantConversationUntilEpochMs =
                System.currentTimeMillis() + settings.voiceChannelFollowUpSeconds.coerceAtLeast(0) * 1000L
        }
        GatewayRuntime.update {
            it.copy(
                openClawStatus = when {
                    internalEvent != null -> {
                        "OpenClaw atualizou o contexto interno."
                    }

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
                lastAssistantReply = if (requiresAttention || reply.isSystemInfo || internalEvent != null) "" else displayReply.ifBlank { it.lastAssistantReply },
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
        val systemMessageId = internalEvent?.let { event ->
            GatewayRuntime.appendChatMessage(ChatRole.SYSTEM, event.systemMessage)
        } ?: 0L
        val assistantMessageId = if (
            !reply.isSystemInfo &&
                internalEvent == null &&
                !requiresAttention &&
                !isPreAgentDecisionOnly(reply) &&
                displayReply.isNotBlank()
        ) {
            GatewayRuntime.appendChatMessage(ChatRole.ASSISTANT, displayReply, reply.detailsText)
        } else {
            0L
        }
        // API injectConversation(speak=false) suprime a fala desta resposta.
        val speechSuppressedByApi = suppressNextReplySpeech
        if (speechSuppressedByApi) {
            suppressNextReplySpeech = false
        }
        if (reply.shouldSpeak && !reply.isSystemInfo && internalEvent == null && spokenReply.isNotBlank() && !speechSuppressedByApi) {
            speakAssistantReply(spokenReply)
        } else {
            Log.i(
                TAG,
                "Reply OpenClaw sem fala automatica. attention=$requiresAttention systemInfo=${reply.isSystemInfo} apiSuppressed=$speechSuppressedByApi tags=${reply.tags.joinToString(",")}"
            )
        }
        // A fala usa QUEUE_FLUSH. Sintetizar depois dela evita que esse flush
        // cancele o arquivo persistido e deixe apenas o cabecalho WAV.
        if (assistantMessageId > 0L && internalEvent == null && spokenReply.isNotBlank()) {
            persistAssistantReplyAudio(spokenReply, assistantMessageId, settings)
        }
        // Ferramentas escolhidas pelo agente (campo "actions"): executadas no
        // aparelho pela conexao de saida — sem rede de entrada. Apos a fala
        // para nao competir com o TTS.
        if (reply.actions.isNotEmpty() && replyActivityId > 0L) {
            replyActivityId = GatewayRuntime.upsertAgentActivityMessage(
                existingId = replyActivityId,
                dispatchedText = reply.transcript.orEmpty(),
                state = ChatAgentActivityState.EXECUTING_ACTION,
                statusText = "Executando a ação solicitada…"
            )
        }
        executeAgentActions(reply.actions)
        if (auditMessageId > 0L || systemMessageId > 0L || assistantMessageId > 0L || reply.actions.isNotEmpty()) {
            finishAgentActivity(replyActivityId)
        } else {
            failAgentActivity(
                replyActivityId,
                "O agente respondeu sem texto, decisão ou ação executável."
            )
        }
        Log.i(
            TAG,
            "OpenClaw reply recebida: ${reply.rawReplyText.take(180)} | patch=${patchSummary ?: "nenhum"} | actions=${reply.actions.size}"
        )
    }

    /** A resposta definitiva já está no histórico; remove só agora o provisório. */
    private fun finishAgentActivity(activityId: Long = assistantActivityMessageId) {
        if (assistantActivityMessageId == activityId) {
            assistantActivityMessageId = 0L
        }
        if (activityId > 0L) {
            GatewayRuntime.removeAgentActivityMessage(activityId)
        }
    }

    private fun failAgentActivity(activityId: Long, reason: String) {
        if (activityId > 0L && GatewayRuntime.failAgentActivityMessage(activityId, reason)) {
            if (assistantActivityMessageId == activityId) {
                assistantActivityMessageId = 0L
            }
            return
        }
        failAssistantProcessing(reason)
    }

    /**
     * Executa os comandos de ferramenta do agente. Ponto unico — a mesma
     * superficie GatewayApiActions usada pela UI/gestos/HTTP. O agente escolhe
     * a tool emitindo {"tool":"<nome>", ...args} no campo "actions" do envelope.
     */
    private fun executeAgentActions(actions: List<JSONObject>) {
        if (actions.isEmpty()) return
        for (action in actions) {
            val tool = action.optString("tool").trim()
                .ifBlank { action.optString("name").trim() }
                .ifBlank { action.optString("type").trim() }
            if (tool.isBlank()) continue
            if (sufficitMcpToolBridge.isMcpClientTool(tool)) {
                executeSufficitMcpClientTool(action, tool)
                continue
            }
            val label = action.optString("label").trim()
            runCatching {
                when (tool.lowercase()) {
                    "screenshot", "print" -> {
                        // Captura e publica no chat como imagem do agente.
                        val bytes = screenshot(label.ifBlank { "Captura de tela" })
                        if (bytes != null) {
                            val file = java.io.File(
                                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir,
                                "agent-screenshot-${System.currentTimeMillis()}.png"
                            )
                            runCatching { file.writeBytes(bytes) }
                                .onSuccess {
                                    GatewayRuntime.appendChatImage(
                                        ChatRole.ASSISTANT,
                                        label.ifBlank { "Captura de tela" },
                                        file.absolutePath
                                    )
                                }
                        } else {
                            GatewayRuntime.appendChatMessage(
                                ChatRole.SYSTEM,
                                "Nao consegui capturar a tela (app em segundo plano)."
                            )
                        }
                    }
                    "photo", "camera", "takephoto", "take_photo" -> {
                        // camera: "front" (padrao) | "back"/"rear"/"traseira".
                        val cam = action.optString("camera").trim()
                            .ifBlank { action.optString("lens").trim() }
                            .lowercase()
                        val useBack = cam == "back" || cam == "rear" || cam == "traseira" ||
                            action.optBoolean("back", false)
                        takePhoto(useBack, label)
                    }
                    "wake" -> wake()
                    "effect", "flash" -> playEffect(label.ifBlank { "Aviso" })
                    "say", "speak" -> {
                        val text = action.optString("text").trim().ifBlank { label }
                        if (text.isNotBlank()) say(text)
                    }
                    "listen", "startlistening", "start_listening" -> startListening()
                    "stoplistening", "stop_listening" -> stopListening()
                    "standby" -> standby()
                    "interrupt" -> interruptAssistant()
                    "finalize", "finalizesegment" -> finalizeSegment()
                    "clearchat", "clear_chat" -> clearChat()
                    "gesture" -> {
                        val g = action.optString("gestureId").trim()
                            .ifBlank { action.optString("gesture").trim() }
                        if (g.isNotBlank()) triggerGesture(g)
                    }
                    "config", "editconfig", "edit_config", "settings" -> {
                        val patch = action.optJSONObject("patch")
                            ?: action.optJSONObject("settings")
                            ?: action
                        applyConfigPatch(patch)
                    }
                    "discover_wol_devices", "discover_wakeonlan", "discover_wake_on_lan", "wol_discover", "discoverwol" -> {
                        val activeProbe = when {
                            action.has("probe") -> action.optBoolean("probe", true)
                            action.has("activeProbe") -> action.optBoolean("activeProbe", true)
                            else -> true
                        }
                        discoverWakeOnLanDevices(activeProbe)
                    }
                    "verify_wol_devices", "verify_wakeonlan", "verify_wake_on_lan", "wol_verify" -> {
                        val macs = buildList {
                            val array = action.optJSONArray("macs")
                            if (array != null) {
                                for (index in 0 until array.length()) {
                                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                            action.optString("mac").trim().takeIf { it.isNotBlank() }?.let(::add)
                        }
                        verifyWakeOnLanDevices(macs, action.optInt("waitSeconds", 30))
                    }
                    "name_wol_device", "name_wakeonlan", "remember_wol_device" -> {
                        val mac = action.optString("mac").trim()
                            .ifBlank { action.optString("macAddress").trim() }
                        val name = action.optString("name").trim()
                            .ifBlank { action.optString("deviceName").trim() }
                        nameWakeOnLanDevice(mac, name)
                    }
                    "wakeonlan", "wake_on_lan", "wol" -> {
                        val macAddress = action.optString("mac").trim()
                            .ifBlank { action.optString("macAddress").trim() }
                            .ifBlank { action.optString("targetMac").trim() }
                        if (macAddress.isBlank()) {
                            throw IllegalArgumentException("A tool Wake-on-LAN exige o MAC do computador.")
                        }
                        val broadcastAddress = action.optString("broadcast").trim()
                            .ifBlank { action.optString("broadcastAddress").trim() }
                        val targetIpAddress = action.optString("ip").trim()
                            .ifBlank { action.optString("ipAddress").trim() }
                            .ifBlank { action.optString("targetIp").trim() }
                        val port = action.optInt(
                            "port",
                            com.sufficit.ai.gateway.network.WakeOnLanTool.DEFAULT_PORT
                        )
                        val repeat = if (action.has("repeat")) {
                            action.optInt(
                                "repeat",
                                com.sufficit.ai.gateway.network.WakeOnLanTool.DEFAULT_REPEAT
                            )
                        } else {
                            // Aceita a forma natural que modelos costumam gerar.
                            action.optInt(
                                "repetitions",
                                com.sufficit.ai.gateway.network.WakeOnLanTool.DEFAULT_REPEAT
                            )
                        }
                        val targetName = action.optString("name").trim()
                            .ifBlank { action.optString("deviceName").trim() }
                            .ifBlank { action.optString("label").trim() }
                        val callId = action.optString("callId").trim()
                            .ifBlank { action.optString("call_id").trim() }
                            .ifBlank { UUID.randomUUID().toString() }
                        executeWakeOnLanWithVerification(
                            callId = callId,
                            tool = tool.lowercase(),
                            macAddress = macAddress,
                            targetName = targetName,
                            targetIpAddress = targetIpAddress,
                            broadcastAddress = broadcastAddress,
                            port = port,
                            repeat = repeat,
                            waitSeconds = action.optInt(
                                "waitSeconds",
                                com.sufficit.ai.gateway.network.WakeOnLanVerificationTool.DEFAULT_WAIT_SECONDS
                            )
                        )
                    }
                    else -> Log.w(TAG, "Tool de agente desconhecida ignorada: $tool")
                }
            }.onFailure { ex ->
                Log.w(TAG, "Falha ao executar tool de agente '$tool'", ex)
                GatewayRuntime.appendChatMessage(
                    ChatRole.SYSTEM,
                    "Falha ao executar ${tool.lowercase()}: ${ex.message ?: "erro desconhecido"}"
                )
            }
        }
    }

    private fun refreshSufficitMcpToolsAndPreferences() {
        val executor = clientToolExecutor ?: return
        executor.execute {
            runCatching {
                runBlocking {
                    val tools = sufficitMcpToolBridge.refreshTools()
                    Log.i(TAG, "MCPs autenticados: ${tools.size} ferramenta(s) descoberta(s).")
                    val namedDevices = com.sufficit.ai.gateway.network.WakeOnLanInventoryService(
                        this@RoomAudioForegroundService
                    ).knownDevices().filter { !it.name.isNullOrBlank() }
                    namedDevices.forEach { device ->
                        sufficitMcpToolBridge.saveWakeOnLanPreference(device)
                    }
                    if (namedDevices.isNotEmpty()) {
                        Log.i(TAG, "Memoria Sufficit sincronizada: ${namedDevices.size} dispositivo(s) Wake-on-LAN.")
                    }
                }
            }.onFailure { error ->
                // Login ausente/expirado nao derruba captura, chat ou WOL
                // local. O catalogo MCP simplesmente fica indisponivel ate a
                // identidade ser recarregada.
                runCatching {
                    runBlocking { sufficitMcpToolBridge.reset() }
                }
                Log.w(TAG, "MCP Sufficit indisponivel: ${error.message}")
            }
        }
    }

    private fun executeSufficitMcpClientTool(action: JSONObject, tool: String) {
        val executor = clientToolExecutor ?: run {
            failAssistantProcessing("Fila de ferramentas cliente indisponivel.")
            return
        }
        val callId = action.optString("callId").trim()
            .ifBlank { action.optString("call_id").trim() }
            .ifBlank { UUID.randomUUID().toString() }
        val mcpToolName = sufficitMcpToolBridge.displayNameForClientTool(tool)
        setAssistantProcessing(true, "Consultando $mcpToolName")
        executor.execute {
            val outcome = runCatching {
                runBlocking {
                    sufficitMcpToolBridge.executeClientToolAction(action)
                }
            }
            val resultText = outcome.getOrNull()?.text.orEmpty()
            val errorText = outcome.exceptionOrNull()?.message.orEmpty()
            val queued = persistentOpenClawConnection?.sendClientToolResult(
                callId = callId,
                tool = tool,
                result = resultText,
                error = errorText
            ) == true
            if (!queued) {
                failAssistantProcessing(
                    errorText.ifBlank { "Nao foi possivel devolver o resultado da ferramenta ao agente." }
                )
            }
            outcome.exceptionOrNull()?.let {
                Log.w(TAG, "Falha na client tool MCP '$tool'", it)
            }
        }
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
        GatewayRuntime.setTextInputModeActive(false)
        if (!captureRunning.get()) {
            startCaptureIfNeeded()
        }
        GatewayRuntime.setListening(active = true, statusText = "Escuta iniciada por API.")
    }

    override fun stopListening() {
        enterWakeWordStandby("Escuta ambiente pausada pela API.")
    }

    override fun standby() {
        enterWakeWordStandby("Modo de espera solicitado pela API.")
    }

    override fun wake() {
        standbyMode = false
        GatewayRuntime.setTextInputModeActive(false)
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
        // Reproduz o efeito do gesto da camera sem o reconhecedor, inclusive
        // a politica por estado. Assim a API nao consegue publicar um gesto
        // que a camera ignoraria na mesma situacao.
        val runtimeState = GatewayRuntime.state().value
        val acceptedGestureId = GestureCommandPolicy.filter(
            gestureId = gestureId,
            listening = runtimeState.listening,
            assistantSpeaking = runtimeState.speakingBack,
            textInputModeActive = runtimeState.textInputModeActive,
            interactionActive = GatewayRuntime.cameraGestureInteractionActive().value
        )
        if (acceptedGestureId == null) {
            GatewayRuntime.setGestureCommand(null)
            Log.i(
                TAG,
                "Gesto simulado ignorado pelo estado atual: id=$gestureId " +
                    "listening=${runtimeState.listening} " +
                    "speaking=${runtimeState.speakingBack} " +
                    "textMode=${runtimeState.textInputModeActive}"
            )
            return
        }
        GatewayRuntime.setGestureCommand(acceptedGestureId)
        when (acceptedGestureId) {
            GestureCommandIds.INDEX_UP -> {
                markDirectAddressNow()
                wake()
            }
            GestureCommandIds.FIST -> stopListening()
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

    override fun screenshot(label: String): ByteArray? {
        // Captura PRIMEIRO (tela limpa), depois o efeito visual/sonoro de
        // feedback — o flash nao sai no PNG.
        val file = com.sufficit.ai.gateway.MainActivity.captureWindowToFile()
        playEffect(label)
        return file?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    override fun takePhoto(useBackCamera: Boolean, label: String) {
        val recognizer = com.sufficit.ai.gateway.vision.MediaPipeCameraGestureRecognizer.active?.get()
        val lensLabel = if (useBackCamera) "camera traseira" else "camera frontal"
        val caption = label.trim().ifBlank { "Foto ($lensLabel)" }
        if (recognizer == null) {
            // Camera nao esta ativa (gestos desligados ou app em segundo plano):
            // tenta acordar a tela e avisa no chat. O agente pode chamar a tool
            // wake e tentar de novo.
            wakeDevice(loadCurrentSettings().screenHoldSeconds * 1000L)
            GatewayRuntime.appendChatMessage(
                ChatRole.SYSTEM,
                "Nao consegui tirar a foto: a camera nao esta ativa. Acorde a tela e tente de novo."
            )
            return
        }
        val output = java.io.File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: filesDir,
            "agent-photo-${System.currentTimeMillis()}.jpg"
        )
        recognizer.capturePhoto(useBackCamera, output) { ok ->
            if (ok) {
                playEffect(caption)
                // Assa a rotacao nos PIXELS (gira o JPEG e zera o EXIF). EXIF
                // sozinho nao basta: visualizadores e a VISAO do agente costumam
                // ignorar a tag, e a foto sai "deitada". Em background.
                Thread {
                    runCatching { bakePhotoOrientation(output) }
                    GatewayRuntime.appendChatImage(ChatRole.ASSISTANT, caption, output.absolutePath)
                }.start()
            } else {
                GatewayRuntime.appendChatMessage(
                    ChatRole.SYSTEM,
                    "Falha ao tirar a foto com a $lensLabel."
                )
            }
        }
    }

    override fun wakeOnLan(
        macAddress: String,
        broadcastAddress: String,
        port: Int,
        repeat: Int,
        targetIpAddress: String,
        includeCompatibilityRoutes: Boolean
    ): com.sufficit.ai.gateway.network.WakeOnLanResult {
        val result = com.sufficit.ai.gateway.network.WakeOnLanTool.send(
            macAddress = macAddress,
            broadcastAddress = broadcastAddress,
            port = port,
            repeat = repeat,
            targetIpAddress = targetIpAddress,
            includeCompatibilityRoutes = includeCompatibilityRoutes
        )
        GatewayRuntime.appendChatMessage(
            ChatRole.SYSTEM,
            "Wake-on-LAN despachado para ${result.macAddress}: ${result.packetsSent} Magic Packets UDP. " +
                wakeOnLanDeliverySummary(result.deliveries)
        )
        Log.i(
            TAG,
            "Wake-on-LAN despachado para ${result.macAddress}: " +
                "rotas=${result.deliveries.joinToString { it.description }} pacotes=${result.packetsSent}"
        )
        return result
    }

    private fun executeWakeOnLanWithVerification(
        callId: String,
        tool: String,
        macAddress: String,
        targetName: String,
        targetIpAddress: String,
        broadcastAddress: String,
        port: Int,
        repeat: Int,
        waitSeconds: Int
    ) {
        val executor = wakeOnLanExecutor ?: throw IllegalStateException(
            "Fila de verificacao Wake-on-LAN indisponivel."
        )
        val safeWait = waitSeconds.coerceIn(5, 60)
        val displayTarget = targetName.ifBlank { macAddress }
        val statusMessageId = GatewayRuntime.appendChatMessage(
            ChatRole.SYSTEM,
            "Wake-on-LAN · $displayTarget: enviando e verificando a rede por ate ${safeWait}s."
        )
        executor.execute {
            val outcome = runCatching {
                com.sufficit.ai.gateway.network.WakeOnLanInventoryService(
                    this@RoomAudioForegroundService
                ).wakeAndVerify(
                    macAddress = macAddress,
                    ipAddress = targetIpAddress.takeIf { it.isNotBlank() },
                    broadcastAddress = broadcastAddress.takeIf { it.isNotBlank() },
                    port = port,
                    repeat = repeat,
                    waitSeconds = safeWait,
                    name = targetName.takeIf { it.isNotBlank() }
                )
            }
            outcome.onSuccess { result ->
                val target = result.targets.single()
                val elapsedSeconds = (target.elapsedMs / 100L).toDouble() / 10.0
                val ipSuffix = target.ipAddress.takeIf { it.isNotBlank() }?.let { " em $it" }.orEmpty()
                val methodSuffix = target.verificationMethod?.takeIf { it.isNotBlank() }
                    ?.let { " via ${wakeOnLanVerificationMethodLabel(it)}" }
                    .orEmpty()
                val deliverySuffix = wakeOnLanDeliverySummary(result.deliveryAttempts)
                val finalText = when (target.status) {
                    com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.ALREADY_REACHABLE ->
                        "Wake-on-LAN · $displayTarget: o dispositivo ja estava acessivel$ipSuffix antes do envio; " +
                            "o Android ainda despachou ${result.packetsSent} pacote(s) de diagnostico.$deliverySuffix"
                    com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.CONFIRMED_ONLINE ->
                        "Wake-on-LAN · $displayTarget: ligou e respondeu$ipSuffix após ${elapsedSeconds}s$methodSuffix.$deliverySuffix"
                    com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.NOT_RESPONDING ->
                        "Wake-on-LAN · $displayTarget: ${result.packetsSent} pacotes despachados pelo Android, mas o dispositivo nao respondeu em ${safeWait}s.$deliverySuffix A entrega na NIC desligada nao pode ser confirmada pela rede; voce pode pedir para tentar novamente."
                    com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.UNVERIFIABLE ->
                        "Wake-on-LAN · $displayTarget: ${result.packetsSent} pacotes despachados pelo Android, mas o MAC nao apareceu na rede em ${safeWait}s e nao ha IP conhecido para testar.$deliverySuffix Nao consegui confirmar se ligou; voce pode pedir para tentar novamente."
                    com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.SEND_FAILED ->
                        "Wake-on-LAN · $displayTarget: falha ao enviar os pacotes. ${target.error.orEmpty()} Voce pode pedir para tentar novamente."
                }
                GatewayRuntime.updateChatMessageText(statusMessageId, finalText)
                Log.i(
                    TAG,
                    "Wake-on-LAN monitorado: alvo=$displayTarget mac=${target.macAddress} " +
                        "status=${target.status} ip=${target.ipAddress.ifBlank { "desconhecido" }} " +
                        "tentativas=${target.checkAttempts} elapsedMs=${target.elapsedMs}"
                )
                syncNamedWakeOnLanDevicesToSufficit(wakeOnLanKnownDevices())

                val resultPayload = JSONObject()
                    .put("status", target.status.name.lowercase())
                    .put("target", displayTarget)
                    .put("mac", target.macAddress)
                    .put("ip", target.ipAddress.takeIf { it.isNotBlank() })
                    .put("packetsSent", result.packetsSent)
                    .put("deliveryAttempts", org.json.JSONArray().apply {
                        result.deliveryAttempts.forEach { delivery ->
                            put(JSONObject()
                                .put("destination", delivery.destination)
                                .put("port", delivery.port)
                                .put("mode", delivery.mode.name.lowercase())
                            )
                        }
                    })
                    .put("waitSeconds", safeWait)
                    .put("elapsedMs", target.elapsedMs)
                    .put("checkAttempts", target.checkAttempts)
                    .put("verificationMethod", target.verificationMethod)
                    .put("message", finalText)
                    .put(
                        "retrySuggested",
                        target.status == com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.NOT_RESPONDING ||
                            target.status == com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.UNVERIFIABLE ||
                            target.status == com.sufficit.ai.gateway.network.WakeOnLanVerificationStatus.SEND_FAILED
                    )
                val queued = persistentOpenClawConnection?.sendClientToolResult(
                    callId = callId,
                    tool = tool,
                    result = resultPayload.toString(),
                    error = target.error.orEmpty()
                ) == true
                if (!queued) {
                    Log.w(TAG, "Resultado Wake-on-LAN ficou somente local: OpenClaw desconectado.")
                }
            }.onFailure { error ->
                val finalText = "Wake-on-LAN · $displayTarget: falha ao executar ou verificar. " +
                    "${error.message ?: "erro desconhecido"}. Voce pode pedir para tentar novamente."
                GatewayRuntime.updateChatMessageText(statusMessageId, finalText)
                persistentOpenClawConnection?.sendClientToolResult(
                    callId = callId,
                    tool = tool,
                    result = "",
                    error = error.message ?: "falha ao executar ou verificar Wake-on-LAN"
                )
                Log.w(TAG, "Falha no Wake-on-LAN monitorado para $displayTarget", error)
            }
        }
    }

    private fun wakeOnLanVerificationMethodLabel(method: String): String = when (method) {
        "arp" -> "ARP"
        "netbios" -> "NetBIOS"
        "sufficit_lan_companion" -> "companion Sufficit"
        "icmp_or_tcp" -> "ping/porta de rede"
        else -> method
    }

    private fun wakeOnLanDeliverySummary(
        deliveries: List<com.sufficit.ai.gateway.network.WakeOnLanDeliveryAttempt>
    ): String {
        if (deliveries.isEmpty()) return ""
        val routes = deliveries.joinToString("; ") { it.description }
        return " Rotas: $routes."
    }

    override fun discoverWakeOnLanDevices(
        activeProbe: Boolean
    ): com.sufficit.ai.gateway.network.WakeOnLanDiscoveryResult {
        val result = com.sufficit.ai.gateway.network.WakeOnLanInventoryService(this).discover(activeProbe)
        val devices = result.devices
        val detail = if (devices.isEmpty()) {
            "nenhum MAC foi encontrado nesta consulta"
        } else {
            devices.take(8).joinToString { device ->
                val label = device.discoveredName?.takeIf { it.isNotBlank() } ?: device.ipAddress
                "$label (${device.macAddress})"
            } +
                if (devices.size > 8) " e mais ${devices.size - 8}" else ""
        }
        GatewayRuntime.appendChatMessage(
            ChatRole.SYSTEM,
            "Descoberta Wake-on-LAN: ${devices.size} MAC(s) aprendido(s) nesta consulta; " +
                "${result.knownDevices.size} mantido(s) no inventario: $detail. " +
                "A compatibilidade da BIOS/NIC ainda precisa ser confirmada por teste WOL."
        )
        Log.i(
            TAG,
            "Descoberta Wake-on-LAN: dispositivos=${devices.size} redes=${result.networks.size} " +
                "sonda=${result.probeExecuted} hosts=${result.scannedHostCount}"
        )
        syncNamedWakeOnLanDevicesToSufficit(result.knownDevices)
        return result
    }

    override fun wakeOnLanKnownDevices(): List<com.sufficit.ai.gateway.network.WakeOnLanKnownDevice> =
        com.sufficit.ai.gateway.network.WakeOnLanInventoryService(this).knownDevices()

    override fun nameWakeOnLanDevice(
        macAddress: String,
        name: String
    ): com.sufficit.ai.gateway.network.WakeOnLanKnownDevice {
        val saved = com.sufficit.ai.gateway.network.WakeOnLanInventoryService(this)
            .nameDevice(macAddress, name)
        syncNamedWakeOnLanDevicesToSufficit(listOf(saved))
        GatewayRuntime.appendChatMessage(
            ChatRole.SYSTEM,
            "Dispositivo ${saved.macAddress} salvo como ${saved.name}; sincronizando com a memoria Sufficit."
        )
        return saved
    }

    override fun verifyWakeOnLanDevices(
        macAddresses: List<String>,
        waitSeconds: Int
    ): com.sufficit.ai.gateway.network.WakeOnLanVerificationResult {
        val result = com.sufficit.ai.gateway.network.WakeOnLanInventoryService(this)
            .verify(macAddresses, waitSeconds)
        val reachable = result.targets.count { it.reachableAfterWake == true }
        GatewayRuntime.appendChatMessage(
            ChatRole.SYSTEM,
            "Teste Wake-on-LAN: ${result.targets.size} alvo(s), ${result.packetsSent} pacote(s), " +
                "$reachable acessivel(is) apos monitoramento de ate ${result.waitSeconds}s."
        )
        syncNamedWakeOnLanDevicesToSufficit(wakeOnLanKnownDevices())
        return result
    }

    private fun syncNamedWakeOnLanDevicesToSufficit(
        devices: List<com.sufficit.ai.gateway.network.WakeOnLanKnownDevice>
    ) {
        val named = devices.filter { !it.name.isNullOrBlank() }
        if (named.isEmpty()) return
        val executor = clientToolExecutor ?: return
        executor.execute {
            named.forEach { device ->
                runCatching {
                    runBlocking {
                        sufficitMcpToolBridge.saveWakeOnLanPreference(device)
                    }
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Falha ao sincronizar ${device.macAddress} com a memoria Sufficit: ${error.message}"
                    )
                }
            }
        }
    }

    /**
     * Reescreve o JPEG com os pixels JA rotacionados conforme a orientacao EXIF
     * e zera a tag (orientation=normal). Garante a foto em pe em qualquer
     * visualizador e na visao do agente, que ignoram EXIF.
     */
    private fun bakePhotoOrientation(file: java.io.File) {
        val path = file.absolutePath
        val exif = android.media.ExifInterface(path)
        val orientation = exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return // ORIENTATION_NORMAL/undefined: nada a fazer
        }
        val src = android.graphics.BitmapFactory.decodeFile(path) ?: return
        val rotated = android.graphics.Bitmap.createBitmap(
            src, 0, 0, src.width, src.height, matrix, true
        )
        java.io.FileOutputStream(file).use { out ->
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (rotated !== src) src.recycle()
        // Tag zerada: pixels ja estao corretos.
        runCatching {
            val e2 = android.media.ExifInterface(path)
            e2.setAttribute(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL.toString()
            )
            e2.saveAttributes()
        }
    }

    override fun playEffect(label: String) {
        GatewayRuntime.triggerScreenEffect(label)
        // Som curto de "obturador" via ToneGenerator (sem asset).
        runCatching {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION, 90
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
            }, 300)
        }
    }

    private fun buildOpenClawConfig(
        settings: GatewaySettings,
        state: GatewayUiState? = null,
        voiceDecision: VoiceChannelSkillDecision? = null,
        transcript: String? = null,
        awakened: Boolean? = null,
        wakeWord: String? = null
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
                    transcript = transcript,
                    awakenedOverride = awakened,
                    wakeWordOverride = wakeWord
                )
            } else {
                null
            }
        )
    }

    private fun speakAssistantReply(replyText: String) {
        // Tela de configuracao aberta: agente em silencio (ex.: cadastro de voz).
        if (GatewayRuntime.configScreenActive().value) {
            Log.i(TAG, "Fala do assistente suprimida: tela de configuracao ativa.")
            return
        }
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
            return
        }
        // `onStart` e assincrono. Atualizar o estado ja no aceite da fala
        // elimina a janela em que a mao aberta chega antes do callback e a
        // interrupcao era descartada apesar de o TTS ja estar em fila.
        assistantSpeaking = true
        assistantInterruptedByUser = false
        assistantSpeechStartedAtEpochMs = System.currentTimeMillis()
        suppressMicrophoneUntilEpochMs = System.currentTimeMillis() + ASSISTANT_SPEECH_GRACE_MS
        GatewayRuntime.update {
            it.copy(statusText = "Assistente falando.", speakingBack = true)
        }
        wakeScreenForAssistantSpeech()
    }

    /**
     * Guarda a mesma locucao TTS da resposta do agente para o botao de play
     * da bolha. Mantem a retencao de seis horas usada nos trechos do usuario,
     * sem depender de o usuario deixar a fala automatica habilitada.
     */
    private fun persistAssistantReplyAudio(replyText: String, messageId: Long, settings: GatewaySettings) {
        val normalized = sanitizeReplyForSpeech(replyText)
        val tts = textToSpeech
        if (normalized.isBlank() || tts == null || !textToSpeechReady) return

        val directory = File(filesDir, "assistant-reply-audio").also { it.mkdirs() }
        val expiration = System.currentTimeMillis() - TRANSCRIPT_AUDIO_RETENTION_MS
        directory.listFiles()?.filter { it.lastModified() < expiration }?.forEach { it.delete() }
        val output = runCatching { File.createTempFile("reply-", ".wav", directory) }.getOrElse {
            Log.w(TAG, "Nao foi possivel criar arquivo de audio do agente", it)
            return
        }
        val utteranceId = "openclaw-audio-${System.nanoTime()}"
        pendingAssistantSpeechAudio[utteranceId] = PendingAssistantSpeechAudio(
            messageId = messageId,
            file = output,
            expiresAtEpochMs = System.currentTimeMillis() + TRANSCRIPT_AUDIO_RETENTION_MS
        )
        applyAssistantVoiceSettings(settings)
        val result = tts.synthesizeToFile(normalized, Bundle(), output, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            pendingAssistantSpeechAudio.remove(utteranceId)
            output.delete()
            Log.w(TAG, "Falha ao iniciar sintese persistida do agente: result=$result")
        }
    }

    private fun readAudioDurationMs(file: File): Long = runCatching {
        android.media.MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        }
    }.getOrDefault(0L)

    private fun sanitizeReplyForSpeech(replyText: String): String {
        if (OpenClawInternalEventClassifier.detect(replyText) != null) {
            Log.i(TAG, "Evento interno de contexto bloqueado antes do TTS.")
            return ""
        }
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
        val runtimeState = GatewayRuntime.state().value
        val tts = textToSpeech
        val engineSpeaking = runCatching { tts?.isSpeaking == true }.getOrDefault(false)
        val hadActiveSpeech = assistantSpeaking || runtimeState.speakingBack || engineSpeaking

        // Sempre pede ao motor para parar. As flags servem para feedback e
        // persistencia, mas nao podem impedir o corte se um callback de TTS
        // chegou atrasado ou se a UI foi recomposta durante a resposta.
        if (!hadActiveSpeech) {
            tts?.stop()
            Log.i(TAG, "Comando de interrupcao recebido sem fala marcada no runtime.")
            return
        }

        val interruptedReply = runtimeState.lastAssistantReply.trim()
        assistantReplyInterruptedPending = true
        interruptedAssistantReplyPreview = interruptedReply.take(220)
        assistantInterruptedByUser = true
        assistantSpeaking = false
        suppressMicrophoneUntilEpochMs = 0L
        tts?.stop()
        GatewayRuntime.update {
            it.copy(
                statusText = "Assistente interrompido.",
                speakingBack = false,
                speechDetected = false
            )
        }
        refreshNotification("Assistente interrompido.")
        Log.i(TAG, "Assistente interrompido por gesto ou toque.")
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
        private const val TRANSCRIPT_AUDIO_RETENTION_MS = 6L * 60L * 60L * 1_000L
        private const val CHANNEL_ID = "room-audio-gateway-v2"
        private const val WAKE_CHANNEL_ID = "room-audio-gateway-wake-v2"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_NOTIFICATION_ID = 1002
        private const val ACTION_FINALIZE_SEGMENT = "com.sufficit.ai.gateway.action.FINALIZE_SEGMENT"
        private const val ACTION_SEND_TEXT = "com.sufficit.ai.gateway.action.SEND_TEXT"
        private const val ACTION_MARK_DIRECT_ADDRESS = "com.sufficit.ai.gateway.action.MARK_DIRECT_ADDRESS"
        private const val EXTRA_TEXT = "extra_text"

        // Janela minima de enderecamento direto apos um sinal explicito
        // (gesto/palavra/texto): cobre transcricao + acumulacao ate o
        // despacho avaliar a frase.
        private const val DIRECT_ADDRESS_MIN_WINDOW_SECS = 30
        private const val ACTION_START = "com.sufficit.ai.gateway.action.START"
        private const val ACTION_RESUME_AMBIENT = "com.sufficit.ai.gateway.action.RESUME_AMBIENT"
        private const val ACTION_INTERRUPT_ASSISTANT = "com.sufficit.ai.gateway.action.INTERRUPT_ASSISTANT"
        private const val ACTION_STOP = "com.sufficit.ai.gateway.action.STOP"
        private const val ACTION_RELOAD_API = "com.sufficit.ai.gateway.action.RELOAD_API"
        private const val ACTION_RELOAD_CONFIG = "com.sufficit.ai.gateway.action.RELOAD_CONFIG"
        private const val ASSISTANT_SPEECH_GRACE_MS = 1_500L
        private const val OPENCLAW_UNCERTAIN_PREFIX = "[?]"
        private const val OPENCLAW_REASONING_HOLD_MS = 2200L
        private const val ASSISTANT_BARGE_IN_STARTUP_BLOCK_MS = 900L
        private const val SAMPLE_RATE_HZ = 16_000
        private const val PCM_16_BIT_BYTES = 2
        private const val MAX_TRANSCRIPTION_CONTEXT_MESSAGES = 50
        private const val MAX_TRANSCRIPTION_CONTEXT_CHARS = 4_000

        // Janela de gravacao da amostra da palavra de ativacao (2.2s a 16kHz).
        private const val WAKE_WORD_RECORD_SAMPLES = 35_200
        private const val WAKE_WORD_DIAGNOSTIC_LOG_INTERVAL_MS = 1_000L

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
        private const val ASSISTANT_PROCESSING_HANDOFF_GRACE_MS = 10_000L
        private const val OPENCLAW_FAILURE_NOTICE_MS = 8_000L

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
        private const val COMPANION_SPEECH_HOLD_MS = 900L
        private const val COMPANION_MAX_SPEECH_SEGMENT_MS = 8_000L
        private const val COMPANION_MIN_TRANSCRIPTION_MS = 500L
        private const val COMPANION_PHRASE_BREAK_SILENCE_MS = 1_800L
        private const val MAX_QUEUED_TRANSCRIPTION_AGE_MS = 600_000L
        private const val ACTIVE_TRANSCRIPTION_STALL_BACKLOG_CLEAR_MS = 210_000L
        private const val MAX_TRANSCRIPTION_QUEUE = 12
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

        /** Retoma explicitamente a escuta ambiente (nivel 2). */
        fun resumeAmbientListening(context: Context) {
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_RESUME_AMBIENT
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
            val normalized = text.trim()
            if (normalized.isBlank()) return
            // O feedback nasce no mesmo toque, antes que o Android precise
            // criar/recriar o servico e antes de qualquer acesso a rede.
            GatewayRuntime.beginAssistantProcessing(normalized)
            val intent = Intent(context, RoomAudioForegroundService::class.java).apply {
                action = ACTION_SEND_TEXT
                putExtra(EXTRA_TEXT, normalized)
            }
            try {
                context.startService(intent)
            } catch (error: Exception) {
                GatewayRuntime.update {
                    it.copy(
                        assistantProcessing = false,
                        assistantProcessingLabel = "",
                        assistantProcessingStartedAtEpochMs = 0L,
                        openClawStatus = "Falha ao iniciar envio: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
                throw error
            }
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
