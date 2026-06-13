package com.sufficit.ai.gateway.runtime

import com.sufficit.ai.gateway.vision.HandTrackingFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GatewayUiState(
    val spectrum: List<Float> = List(48) { 0f },
    val listening: Boolean = false,
    val speechDetected: Boolean = false,
    val transcribing: Boolean = false,
    val speakingBack: Boolean = false,
    val transcriptionQueueCount: Int = 0,
    val openClawDispatchQueueCount: Int = 0,
    val transcriptionBackendLabel: String = "Remoto",
    val transcriptionModelLabel: String = "",
    val statusText: String = "Pronto para iniciar.",
    val cameraGestureStatus: String = "Gesto por camera desativado.",
    val gestureDebugActive: Boolean = false,
    val gestureDebugPreviewAvailable: Boolean = false,
    val gestureDebugDetectedLabel: String? = null,
    val gestureDebugMatched: Boolean = false,
    val gestureDebugUpdatedAtEpochMs: Long = 0L,
    val gestureDebugReason: String = "Aguardando analise da camera.",
    val gestureDebugHandedness: String? = null,
    val gestureDebugLandmarkCount: Int = 0,
    val gestureDebugIndexExtended: Boolean = false,
    val gestureDebugMiddleFolded: Boolean = false,
    val gestureDebugRingFolded: Boolean = false,
    val gestureDebugPinkyFolded: Boolean = false,
    val gestureDebugThumbFolded: Boolean = false,
    val currentTranscript: String = "",
    val previousTranscript: String = "",
    val recentTranscripts: List<String> = emptyList(),
    val lastError: String? = null,
    val openClawStatus: String = "OpenClaw aguardando frase final.",
    val blockingAnnouncementMessage: String? = null,
    val lastAssistantReply: String = "",
    val lastAssistantReplyNeedsAttention: Boolean = false,
    val lastAssistantReplyTags: List<String> = emptyList(),
    val lastAssistantReplyConfidence: Double? = null,
    val lastAssistantReplyOverlap: Boolean = false,
    val systemInfoMessage: String? = null,
    val systemInfoMessageUntilEpochMs: Long = 0L,
    val lastGender: String? = null,
    val lastEmotion: String? = null,
    val sameSpeakerProbability: Double? = null,
    val voiceLearningProgress: Double? = null,
    val multipleVoicesLikely: Boolean = false,
    val currentMicrophoneGain: Double? = null,
    val estimatedNoiseFloorRms: Double? = null,
    val ambientNoiseDetected: Boolean = false,
    val ambientNoiseKind: String? = null,
    val ambientNoiseScore: Double? = null,
    val microphoneGainAdjustedUntilEpochMs: Long = 0L,
    val microphoneGainAdjustedMessage: String? = null,
    val screenAttentionUntilEpochMs: Long = 0L
)

/** Papel de uma mensagem no historico de conversa do dashboard. */
enum class ChatRole { USER, ASSISTANT }

/** Mensagem do historico de conversa (estilo WhatsApp/Telegram). */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val atEpochMs: Long
)

/**
 * Estado da verificacao de voz do usuario ("so a minha voz").
 * Fluxo: baixar modelo -> cadastrar falas (enrollment) -> ativar -> cada
 * segmento de fala so segue para transcricao se a similaridade com o perfil
 * cadastrado passar do limiar.
 */
data class SpeakerVoiceUiState(
    val enabled: Boolean = false,
    val modelReady: Boolean = false,
    val sampleCount: Int = 0,
    val enrollRemaining: Int = 0,
    val lastScore: Double? = null,
    val threshold: Double = 0.55,
    val downloadProgressPercent: Int? = null,
    val status: String = "Perfil de voz nao configurado."
)

data class WakeWordUiState(
    val enabled: Boolean = true,
    val sampleCount: Int = 0,
    val recording: Boolean = false,
    val status: String = "Grave ao menos uma amostra da palavra.",
    val lastDistance: Double? = null,
    val lastMatchAtEpochMs: Long = 0L,
    val threshold: Double = 0.18
)

object GatewayRuntime {
    private val state = MutableStateFlow(GatewayUiState())

    // Gate do gesto ABERTO por padrao: app com escuta ativa ouve de verdade.
    // O design antigo (gate fechado ate o primeiro gesto) mostrava espectro e
    // status "ouvindo" enquanto descartava toda fala — incoerente. "Parado de
    // verdade" agora e o standby (punho 5s / botao parar), que volta por
    // palavra de ativacao, indicador ou botao.
    private val cameraGestureGateFlow = MutableStateFlow(true)
    private val handTrackingFlow = MutableStateFlow<HandTrackingFrame?>(null)
    private val wakeWordFlow = MutableStateFlow(WakeWordUiState())
    private val wakeWordConfigVersionFlow = MutableStateFlow(0)
    private val wakeWordRecordingRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun state(): StateFlow<GatewayUiState> = state.asStateFlow()
    fun cameraGestureGate(): StateFlow<Boolean> = cameraGestureGateFlow.asStateFlow()

    // Flow separado do GatewayUiState: landmarks chegam a ~30fps e nao devem
    // forcar copia/recomposicao do estado geral da UI.
    fun handTracking(): StateFlow<HandTrackingFrame?> = handTrackingFlow.asStateFlow()

    fun setHandTrackingFrame(frame: HandTrackingFrame?) {
        handTrackingFlow.value = frame
    }

    // Gesto de comando ativo no momento (id de GestureCommandIds + instante).
    // Alimentado pelo reconhecedor a cada quadro estavel; consumido por:
    //  - RoomAudioForegroundService: "indicador mantido" segura a gravacao
    //    aberta ignorando o corte por silencio;
    //  - GestureCommandFooter: linha colorida no rodape da tela.
    data class GestureCommand(
        val gestureId: String,
        /** Ultimo quadro que confirmou o gesto. */
        val atEpochMs: Long,
        /** Inicio da pose continua atual (para limites de "gesto mantido"). */
        val sinceEpochMs: Long
    )

    private val gestureCommandFlow = MutableStateFlow<GestureCommand?>(null)

    fun gestureCommand(): StateFlow<GestureCommand?> = gestureCommandFlow.asStateFlow()

    fun setGestureCommand(gestureId: String?) {
        val now = System.currentTimeMillis()
        gestureCommandFlow.value = gestureId?.let { id ->
            val previous = gestureCommandFlow.value
            GestureCommand(
                gestureId = id,
                atEpochMs = now,
                // Pose mantida preserva o inicio; gesto novo zera.
                sinceEpochMs = if (previous?.gestureId == id) previous.sinceEpochMs else now
            )
        }
    }

    // Atividade labial detectada pela camera frontal (MediaPipe FaceMesh).
    // Alimentada por quadro pelo reconhecedor de visao; consumida pelo
    // RoomAudioForegroundService durante segmentos de fala para correlacionar
    // "boca mexendo" com o audio do microfone (anti-TV/anti-gravacao no
    // pre-agente do servidor). null = camera parada ou visao indisponivel.
    data class LipActivity(
        /** 0..1: variacao da abertura labial na janela recente (falando ~> 0.25). */
        val score: Double,
        /** Rostos no quadro (0 = sem rosto; score so vale com rosto presente). */
        val faceCount: Int,
        /** Quadro que produziu a medida (frescor: descartar se antigo). */
        val atEpochMs: Long
    )

    private val lipActivityFlow = MutableStateFlow<LipActivity?>(null)

    fun lipActivity(): StateFlow<LipActivity?> = lipActivityFlow.asStateFlow()

    fun setLipActivity(value: LipActivity?) {
        lipActivityFlow.value = value
    }

    private val handSkinFlow = MutableStateFlow<String?>(null)

    // Skin do overlay de maos por id (string para nao acoplar o runtime ao enum de UI).
    fun handSkin(): StateFlow<String?> = handSkinFlow.asStateFlow()

    fun setHandSkin(skinId: String) {
        handSkinFlow.value = skinId
    }

    // Historico de conversa exibido no dashboard (mais recente no FINAL da
    // lista). Em memoria, limitado a CHAT_HISTORY_LIMIT mensagens.
    private val chatFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val chatMessageIdSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private const val CHAT_HISTORY_LIMIT = 200

    fun chatMessages(): StateFlow<List<ChatMessage>> = chatFlow.asStateFlow()

    fun clearChat() {
        chatFlow.value = emptyList()
    }

    fun appendChatMessage(role: ChatRole, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val message = ChatMessage(
            id = chatMessageIdSeq.incrementAndGet(),
            role = role,
            text = trimmed,
            atEpochMs = System.currentTimeMillis()
        )
        chatFlow.value = (chatFlow.value + message).takeLast(CHAT_HISTORY_LIMIT)
    }

    private val speakerVoiceFlow = MutableStateFlow(SpeakerVoiceUiState())

    // Falas restantes do cadastro de voz. O servico de audio consome um slot
    // por segmento de fala finalizado: a fala vira amostra do perfil e NAO e
    // enviada para transcricao/OpenClaw.
    private val speakerEnrollRemaining = java.util.concurrent.atomic.AtomicInteger(0)

    fun speakerVoice(): StateFlow<SpeakerVoiceUiState> = speakerVoiceFlow.asStateFlow()

    fun updateSpeakerVoice(transform: (SpeakerVoiceUiState) -> SpeakerVoiceUiState) {
        speakerVoiceFlow.value = transform(speakerVoiceFlow.value)
    }

    fun requestSpeakerEnrollment(samples: Int) {
        speakerEnrollRemaining.set(samples)
        updateSpeakerVoice {
            it.copy(
                enrollRemaining = samples,
                status = "Cadastro: fale uma frase de 3-5s e faca uma pausa; repita $samples vez(es)."
            )
        }
    }

    fun cancelSpeakerEnrollment() {
        speakerEnrollRemaining.set(0)
        updateSpeakerVoice { it.copy(enrollRemaining = 0) }
    }

    /** True enquanto ha falas de cadastro de voz pendentes. */
    fun isSpeakerEnrollmentPending(): Boolean = speakerEnrollRemaining.get() > 0

    /**
     * Consome um slot de cadastro (retorna quantos restavam ANTES do
     * consumo; 0 = nenhum cadastro pendente). Atomico: cada segmento de
     * fala consome no maximo um slot.
     */
    fun takeSpeakerEnrollSlot(): Int {
        while (true) {
            val current = speakerEnrollRemaining.get()
            if (current <= 0) return 0
            if (speakerEnrollRemaining.compareAndSet(current, current - 1)) {
                return current
            }
        }
    }

    fun wakeWord(): StateFlow<WakeWordUiState> = wakeWordFlow.asStateFlow()

    fun updateWakeWord(transform: (WakeWordUiState) -> WakeWordUiState) {
        wakeWordFlow.value = transform(wakeWordFlow.value)
    }

    // Incrementado quando templates/config mudam em disco; o servico de
    // audio observa e recarrega o detector.
    fun wakeWordConfigVersion(): StateFlow<Int> = wakeWordConfigVersionFlow.asStateFlow()

    fun bumpWakeWordConfigVersion() {
        wakeWordConfigVersionFlow.value += 1
    }

    fun requestWakeWordRecording() {
        wakeWordRecordingRequested.set(true)
    }

    fun takeWakeWordRecordingRequest(): Boolean = wakeWordRecordingRequested.getAndSet(false)

    fun update(transform: (GatewayUiState) -> GatewayUiState) {
        state.value = transform(state.value)
    }

    fun setCameraGestureStatus(statusText: String) {
        update { it.copy(cameraGestureStatus = statusText) }
    }

    fun setCameraGestureGateOpen(open: Boolean) {
        cameraGestureGateFlow.value = open
    }

    fun setGestureDebugState(
        detectedLabel: String?,
        matched: Boolean,
        reason: String,
        handedness: String? = null,
        landmarkCount: Int = 0,
        indexExtended: Boolean = false,
        middleFolded: Boolean = false,
        ringFolded: Boolean = false,
        pinkyFolded: Boolean = false,
        thumbFolded: Boolean = false,
        active: Boolean = false
    ) {
        update {
            it.copy(
                gestureDebugDetectedLabel = detectedLabel,
                gestureDebugMatched = matched,
                gestureDebugUpdatedAtEpochMs = System.currentTimeMillis(),
                gestureDebugReason = reason,
                gestureDebugHandedness = handedness,
                gestureDebugLandmarkCount = landmarkCount,
                gestureDebugIndexExtended = indexExtended,
                gestureDebugMiddleFolded = middleFolded,
                gestureDebugRingFolded = ringFolded,
                gestureDebugPinkyFolded = pinkyFolded,
                gestureDebugThumbFolded = thumbFolded,
                gestureDebugActive = active
            )
        }
    }

    fun setListening(active: Boolean, statusText: String = state.value.statusText) {
        update {
            it.copy(
                listening = active,
                speechDetected = if (active) it.speechDetected else false,
                transcribing = if (active) it.transcribing else false,
                speakingBack = if (active) it.speakingBack else false,
                transcriptionQueueCount = if (active) it.transcriptionQueueCount else 0,
                openClawDispatchQueueCount = if (active) it.openClawDispatchQueueCount else 0,
                ambientNoiseDetected = if (active) it.ambientNoiseDetected else false,
                ambientNoiseKind = if (active) it.ambientNoiseKind else null,
                ambientNoiseScore = if (active) it.ambientNoiseScore else null,
                microphoneGainAdjustedUntilEpochMs = if (active) it.microphoneGainAdjustedUntilEpochMs else 0L,
                microphoneGainAdjustedMessage = if (active) it.microphoneGainAdjustedMessage else null,
                statusText = statusText
            )
        }
    }

    fun clearError(statusText: String = state.value.statusText) {
        update {
            it.copy(
                lastError = null,
                statusText = statusText
            )
        }
    }

    fun setError(statusText: String, details: String) {
        update {
            it.copy(
                listening = false,
                speechDetected = false,
                transcribing = false,
                speakingBack = false,
                transcriptionQueueCount = 0,
                openClawDispatchQueueCount = 0,
                ambientNoiseDetected = false,
                ambientNoiseKind = null,
                ambientNoiseScore = null,
                microphoneGainAdjustedUntilEpochMs = 0L,
                microphoneGainAdjustedMessage = null,
                statusText = statusText,
                lastError = details
            )
        }
    }

    fun setGestureDebugActive(active: Boolean) {
        update { it.copy(gestureDebugActive = active) }
    }

    fun setGestureDebugPreviewAvailable(available: Boolean) {
        update { it.copy(gestureDebugPreviewAvailable = available) }
    }

    fun requestScreenAttention(holdMillis: Long) {
        val until = System.currentTimeMillis() + holdMillis.coerceAtLeast(0L)
        update { it.copy(screenAttentionUntilEpochMs = maxOf(it.screenAttentionUntilEpochMs, until)) }
    }
}
