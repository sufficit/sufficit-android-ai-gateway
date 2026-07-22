package com.sufficit.ai.gateway.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class WakeWordUiState(
    val enabled: Boolean = true,
    val sampleCount: Int = 0,
    val profileCount: Int = 0,
    val readyProfileCount: Int = 0,
    val recording: Boolean = false,
    val recordingProfileId: String? = null,
    val status: String = "Cadastre uma wake word no Wake Lab.",
    val lastDistance: Double? = null,
    val lastMatchAtEpochMs: Long = 0L,
    val lastMatchedProfileId: String? = null,
    val lastMatchedPhraseLabel: String? = null,
    val threshold: Double = 0.18
)

/**
 * Extraido de GatewayRuntime (Phase 8): GatewayRuntime continua sendo a
 * unica API publica (delega para ca), este objeto e um detalhe interno.
 */
internal object GatewayWakeWordRuntime {
    private val wakeWordFlow = MutableStateFlow(WakeWordUiState())

    // Incrementado quando templates/config mudam em disco; o servico de
    // audio observa e recarrega o detector.
    private val wakeWordConfigVersionFlow = MutableStateFlow(0)
    private val wakeWordRecordingRequested = AtomicReference<String?>(null)

    fun wakeWord(): StateFlow<WakeWordUiState> = wakeWordFlow.asStateFlow()

    fun updateWakeWord(transform: (WakeWordUiState) -> WakeWordUiState) {
        wakeWordFlow.value = transform(wakeWordFlow.value)
    }

    fun wakeWordConfigVersion(): StateFlow<Int> = wakeWordConfigVersionFlow.asStateFlow()

    fun bumpWakeWordConfigVersion() {
        wakeWordConfigVersionFlow.value += 1
    }

    fun requestWakeWordRecording(profileId: String) {
        wakeWordRecordingRequested.set(profileId)
    }

    fun takeWakeWordRecordingRequest(): String? = wakeWordRecordingRequested.getAndSet(null)
}
