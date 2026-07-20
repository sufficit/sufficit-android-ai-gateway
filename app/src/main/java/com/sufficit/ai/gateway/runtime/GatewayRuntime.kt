package com.sufficit.ai.gateway.runtime

import com.sufficit.ai.gateway.vision.HandTrackingFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GatewayUiState(
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
    val screenAttentionUntilEpochMs: Long = 0L,
    // Agente processando um pedido: bolha provisoria no chat enquanto aguarda
    // a resposta. Label = o que esta sendo processado (o pedido do usuario).
    val assistantProcessing: Boolean = false,
    val assistantProcessingLabel: String = ""
)

/**
 * Bus de estado compartilhado entre o servico de audio/visao e a UI Compose.
 *
 * Continua sendo a UNICA API publica consumida pelo resto do app (todo
 * `GatewayRuntime.xxx(...)`) — internamente delega dashboard-adjacent
 * concerns (chat, cadastro de voz, palavra de ativacao, sinais continuos de
 * visao/gesto) para objetos dedicados em runtime/ (Phase 8 da migracao de
 * arquitetura). O core desta classe fica com o estado do dashboard
 * (GatewayUiState) e o gate de gesto/camera, que sao os mais acoplados aos
 * call sites de MainActivity/RoomAudioForegroundService e por isso ficam
 * onde ja estavam.
 */
object GatewayRuntime {
    private val state = MutableStateFlow(GatewayUiState())

    // Gate do gesto ABERTO por padrao: app com escuta ativa ouve de verdade.
    // O design antigo (gate fechado ate o primeiro gesto) mostrava espectro e
    // status "ouvindo" enquanto descartava toda fala — incoerente. "Parado de
    // verdade" agora e o standby (punho 5s / botao parar), que volta por
    // palavra de ativacao, indicador ou botao.
    private val cameraGestureGateFlow = MutableStateFlow(true)

    // Tela de configuracao aberta: o agente deve PARAR (nao despachar fala nem
    // falar), para nao se intrometer durante o cadastro de voz/palavra de
    // ativacao. O microfone segue ativo para capturar amostras.
    private val configScreenActiveFlow = MutableStateFlow(false)

    // Espectro do microfone: flow PROPRIO, fora de GatewayUiState de proposito.
    // Atualiza a ~2Hz enquanto ouvindo; se fosse mais um campo do state
    // principal, cada tick emitiria uma GatewayUiState inteira nova e
    // recomporia TUDO que le runtimeState (chat, status, etc) so por causa
    // do espectro — achado real de performance (scroll do chat travando).
    // So o Canvas do espectro (ListeningSpectrum) deve coletar isto.
    private val spectrumFlow = MutableStateFlow<List<Float>>(List(48) { 0f })

    fun configScreenActive(): StateFlow<Boolean> = configScreenActiveFlow.asStateFlow()

    fun setConfigScreenActive(active: Boolean) {
        configScreenActiveFlow.value = active
    }

    fun state(): StateFlow<GatewayUiState> = state.asStateFlow()
    fun cameraGestureGate(): StateFlow<Boolean> = cameraGestureGateFlow.asStateFlow()

    fun spectrum(): StateFlow<List<Float>> = spectrumFlow.asStateFlow()

    fun setSpectrum(value: List<Float>) {
        spectrumFlow.value = value
    }

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

    // --- Sinais continuos de visao/gesto (GatewayGestureSignalRuntime) ---

    fun handTracking(): StateFlow<HandTrackingFrame?> = GatewayGestureSignalRuntime.handTracking()

    fun setHandTrackingFrame(frame: HandTrackingFrame?) {
        GatewayGestureSignalRuntime.setHandTrackingFrame(frame)
    }

    fun screenEffect(): StateFlow<ScreenEffect?> = GatewayGestureSignalRuntime.screenEffect()

    fun triggerScreenEffect(label: String = "") {
        GatewayGestureSignalRuntime.triggerScreenEffect(label)
    }

    fun gestureCommand(): StateFlow<GestureCommand?> = GatewayGestureSignalRuntime.gestureCommand()

    fun setGestureCommand(gestureId: String?) {
        GatewayGestureSignalRuntime.setGestureCommand(gestureId)
    }

    fun lipActivity(): StateFlow<LipActivity?> = GatewayGestureSignalRuntime.lipActivity()

    fun setLipActivity(value: LipActivity?) {
        GatewayGestureSignalRuntime.setLipActivity(value)
    }

    fun handSkin(): StateFlow<String?> = GatewayGestureSignalRuntime.handSkin()

    fun setHandSkin(skinId: String) {
        GatewayGestureSignalRuntime.setHandSkin(skinId)
    }

    // --- Historico de conversa (GatewayChatRuntime) ---

    fun chatMessages(): StateFlow<List<ChatMessage>> = GatewayChatRuntime.chatMessages()

    fun attachChatPersistence(initial: List<ChatMessage>, persister: (List<ChatMessage>) -> Unit) {
        GatewayChatRuntime.attachChatPersistence(initial, persister)
    }

    fun clearChat() {
        GatewayChatRuntime.clearChat()
    }

    fun appendChatMessage(role: ChatRole, text: String, details: String? = null) {
        GatewayChatRuntime.appendChatMessage(role, text, details)
    }

    fun appendChatImage(role: ChatRole, caption: String, imagePath: String) {
        GatewayChatRuntime.appendChatImage(role, caption, imagePath)
    }

    // --- Cadastro de voz (GatewaySpeakerVoiceRuntime) ---

    fun speakerVoice(): StateFlow<SpeakerVoiceUiState> = GatewaySpeakerVoiceRuntime.speakerVoice()

    fun updateSpeakerVoice(transform: (SpeakerVoiceUiState) -> SpeakerVoiceUiState) {
        GatewaySpeakerVoiceRuntime.updateSpeakerVoice(transform)
    }

    fun requestSpeakerEnrollment(samples: Int) {
        GatewaySpeakerVoiceRuntime.requestSpeakerEnrollment(samples)
    }

    fun cancelSpeakerEnrollment() {
        GatewaySpeakerVoiceRuntime.cancelSpeakerEnrollment()
    }

    fun isSpeakerEnrollmentPending(): Boolean = GatewaySpeakerVoiceRuntime.isSpeakerEnrollmentPending()

    fun takeSpeakerEnrollSlot(): Int = GatewaySpeakerVoiceRuntime.takeSpeakerEnrollSlot()

    // --- Palavra de ativacao (GatewayWakeWordRuntime) ---

    fun wakeWord(): StateFlow<WakeWordUiState> = GatewayWakeWordRuntime.wakeWord()

    fun updateWakeWord(transform: (WakeWordUiState) -> WakeWordUiState) {
        GatewayWakeWordRuntime.updateWakeWord(transform)
    }

    fun wakeWordConfigVersion(): StateFlow<Int> = GatewayWakeWordRuntime.wakeWordConfigVersion()

    fun bumpWakeWordConfigVersion() {
        GatewayWakeWordRuntime.bumpWakeWordConfigVersion()
    }

    fun requestWakeWordRecording() {
        GatewayWakeWordRuntime.requestWakeWordRecording()
    }

    fun takeWakeWordRecordingRequest(): Boolean = GatewayWakeWordRuntime.takeWakeWordRecordingRequest()
}
