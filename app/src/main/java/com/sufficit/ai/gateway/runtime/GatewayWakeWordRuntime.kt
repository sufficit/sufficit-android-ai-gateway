package com.sufficit.ai.gateway.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class WakeWordUiState(
    val enabled: Boolean = true,
    val sampleCount: Int = 0,
    val recording: Boolean = false,
    val status: String = "Grave ao menos uma amostra da palavra.",
    val lastDistance: Double? = null,
    val lastMatchAtEpochMs: Long = 0L,
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
    private val wakeWordRecordingRequested = AtomicBoolean(false)

    fun wakeWord(): StateFlow<WakeWordUiState> = wakeWordFlow.asStateFlow()

    fun updateWakeWord(transform: (WakeWordUiState) -> WakeWordUiState) {
        wakeWordFlow.value = transform(wakeWordFlow.value)
    }

    fun wakeWordConfigVersion(): StateFlow<Int> = wakeWordConfigVersionFlow.asStateFlow()

    fun bumpWakeWordConfigVersion() {
        wakeWordConfigVersionFlow.value += 1
    }

    fun requestWakeWordRecording() {
        wakeWordRecordingRequested.set(true)
    }

    fun takeWakeWordRecordingRequest(): Boolean = wakeWordRecordingRequested.getAndSet(false)
}
