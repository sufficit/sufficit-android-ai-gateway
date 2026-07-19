package com.sufficit.ai.gateway.runtime

import com.sufficit.ai.gateway.vision.HandTrackingFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Efeito visual de "flash" (ex.: ao tirar um screenshot por API): a UI
 * observa o timestamp e dispara um clarao branco que esvanece. O som e
 * tocado pelo servico (tem AudioManager). Label opcional aparece junto.
 */
data class ScreenEffect(val atEpochMs: Long, val label: String)

/**
 * Gesto de comando ativo no momento (id de GestureCommandIds + instante).
 * Alimentado pelo reconhecedor a cada quadro estavel; consumido por:
 *  - RoomAudioForegroundService: "indicador mantido" segura a gravacao
 *    aberta ignorando o corte por silencio;
 *  - GestureCommandFooter: linha colorida no rodape da tela.
 */
data class GestureCommand(
    val gestureId: String,
    /** Ultimo quadro que confirmou o gesto. */
    val atEpochMs: Long,
    /** Inicio da pose continua atual (para limites de "gesto mantido"). */
    val sinceEpochMs: Long
)

/**
 * Atividade labial detectada pela camera frontal (MediaPipe FaceMesh).
 * Alimentada por quadro pelo reconhecedor de visao; consumida pelo
 * RoomAudioForegroundService durante segmentos de fala para correlacionar
 * "boca mexendo" com o audio do microfone (anti-TV/anti-gravacao no
 * pre-agente do servidor). null = camera parada ou visao indisponivel.
 */
data class LipActivity(
    /** 0..1: variacao da abertura labial na janela recente (falando ~> 0.25). */
    val score: Double,
    /** Rostos no quadro (0 = sem rosto; score so vale com rosto presente). */
    val faceCount: Int,
    /** Quadro que produziu a medida (frescor: descartar se antigo). */
    val atEpochMs: Long
)

/**
 * Sinais continuos de visao/gesto (hand tracking overlay, flash de tela,
 * gesto de comando ativo, atividade labial, skin do overlay de maos).
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewayGestureSignalRuntime {
    // Flow separado do GatewayUiState: landmarks chegam a ~30fps e nao devem
    // forcar copia/recomposicao do estado geral da UI.
    private val handTrackingFlow = MutableStateFlow<HandTrackingFrame?>(null)
    private val screenEffectFlow = MutableStateFlow<ScreenEffect?>(null)
    private val gestureCommandFlow = MutableStateFlow<GestureCommand?>(null)
    private val lipActivityFlow = MutableStateFlow<LipActivity?>(null)

    // Skin do overlay de maos por id (string para nao acoplar o runtime ao enum de UI).
    private val handSkinFlow = MutableStateFlow<String?>(null)

    fun handTracking(): StateFlow<HandTrackingFrame?> = handTrackingFlow.asStateFlow()

    fun setHandTrackingFrame(frame: HandTrackingFrame?) {
        handTrackingFlow.value = frame
    }

    fun screenEffect(): StateFlow<ScreenEffect?> = screenEffectFlow.asStateFlow()

    fun triggerScreenEffect(label: String = "") {
        screenEffectFlow.value = ScreenEffect(System.currentTimeMillis(), label.trim())
    }

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

    fun lipActivity(): StateFlow<LipActivity?> = lipActivityFlow.asStateFlow()

    fun setLipActivity(value: LipActivity?) {
        lipActivityFlow.value = value
    }

    fun handSkin(): StateFlow<String?> = handSkinFlow.asStateFlow()

    fun setHandSkin(skinId: String) {
        handSkinFlow.value = skinId
    }
}
