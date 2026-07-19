package com.sufficit.ai.gateway.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewaySpeakerVoiceRuntime {
    private val speakerVoiceFlow = MutableStateFlow(SpeakerVoiceUiState())

    // Falas restantes do cadastro de voz. O servico de audio consome um slot
    // por segmento de fala finalizado: a fala vira amostra do perfil e NAO e
    // enviada para transcricao/OpenClaw.
    private val speakerEnrollRemaining = AtomicInteger(0)

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
}
