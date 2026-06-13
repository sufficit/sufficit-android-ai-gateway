package com.sufficit.ai.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.config.AssistantVoiceStyle
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.ScreenMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ConfigAssistantVoiceSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("Voz do Assistente", "Texto para voz nas respostas do OpenClaw", onBack) {
        item {
            ConfigSection(title = "Sintese de voz") {
                SettingToggleRow(
                    title = "Responder em voz",
                    supportingText = "Mantem o texto no log e tambem fala a resposta no Android.",
                    checked = state.assistantVoiceEnabled,
                    onCheckedChange = actions.onAssistantVoiceEnabledChange
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    assistantVoiceOptions().forEach { option ->
                        OutlinedButton(
                            onClick = { actions.onAssistantVoiceStyleChange(option.persistedValue) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(option.label)
                        }
                    }
                }
                MetadataChip(
                    "Voz",
                    assistantVoiceOptions().firstOrNull { it.persistedValue == state.assistantVoiceStyle }?.label ?: "Sistema"
                )
                SliderSettingRow(
                    title = "Velocidade da fala",
                    value = state.assistantSpeechRateInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.6f, 1.8f)
                        ?: GatewaySettingsStore.DEFAULT_ASSISTANT_SPEECH_RATE.toFloat(),
                    valueText = String.format(
                        Locale.US,
                        "%.2fx",
                        state.assistantSpeechRateInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.6f, 1.8f)
                            ?: GatewaySettingsStore.DEFAULT_ASSISTANT_SPEECH_RATE.toFloat()
                    ),
                    valueRange = 0.6f..1.8f,
                    steps = 23,
                    supportingText = "Ajusta o ritmo da fala sintetizada.",
                    onValueChange = { actions.onAssistantSpeechRateChange(String.format(Locale.US, "%.2f", it)) }
                )
                SliderSettingRow(
                    title = "Tom da voz",
                    value = state.assistantPitchInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.7f, 1.4f)
                        ?: GatewaySettingsStore.DEFAULT_ASSISTANT_PITCH.toFloat(),
                    valueText = String.format(
                        Locale.US,
                        "%.2fx",
                        state.assistantPitchInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.7f, 1.4f)
                            ?: GatewaySettingsStore.DEFAULT_ASSISTANT_PITCH.toFloat()
                    ),
                    valueRange = 0.7f..1.4f,
                    steps = 13,
                    supportingText = "Caracteristica leve da voz sintetizada. Depende tambem do motor TTS do aparelho.",
                    onValueChange = { actions.onAssistantPitchChange(String.format(Locale.US, "%.2f", it)) }
                )
            }
        }
    }
}

@Composable
fun ConfigScreenSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("Tela", "Wake e permanencia da tela", onBack) {
        item {
            ConfigSection(title = "Comportamento") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScreenMode.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { actions.onScreenModeChange(option.persistedValue) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when (option) {
                                    ScreenMode.ALWAYS_ON -> "Sempre ligado"
                                    ScreenMode.ALWAYS_OFF -> "Sempre desligado"
                                    ScreenMode.ACTIVITY -> "Activity"
                                }
                            )
                        }
                    }
                }
                MetadataChip(
                    "Modo atual",
                    when (ScreenMode.fromPersistedValue(state.screenMode)) {
                        ScreenMode.ALWAYS_ON -> "Sempre ligado"
                        ScreenMode.ALWAYS_OFF -> "Sempre desligado"
                        ScreenMode.ACTIVITY -> "Activity"
                    }
                )
                SliderSettingRow(
                    title = "Tempo de tela acesa",
                    value = state.screenHoldSecondsInput.toFloatOrNull()?.coerceIn(1f, 30f)
                        ?: GatewaySettingsStore.DEFAULT_SCREEN_HOLD_SECONDS.toFloat(),
                    valueText = "${state.screenHoldSecondsInput.toIntOrNull()?.coerceIn(1, 30) ?: GatewaySettingsStore.DEFAULT_SCREEN_HOLD_SECONDS}s",
                    valueRange = 1f..30f,
                    steps = 28,
                    supportingText = "Tempo padrao apos detectar voz, aguardar resposta ou falar de volta.",
                    onValueChange = { actions.onScreenHoldSecondsChange(it.roundToInt().toString()) }
                )
            }
        }
        item {
            ConfigSection(title = "Card de transcricao") {
                val clearTimeout = state.transcriptClearTimeoutSecsInput.toIntOrNull()
                    ?.coerceIn(0, 300) ?: GatewaySettingsStore.DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS
                SliderSettingRow(
                    title = "Limpar transcricoes apos",
                    value = clearTimeout.toFloat(),
                    valueText = if (clearTimeout == 0) "Nunca" else "${clearTimeout}s",
                    valueRange = 0f..300f,
                    steps = 29,
                    supportingText = "Tempo sem nova fala ate as transcricoes desaparecerem do card. 0 = desabilitado.",
                    onValueChange = { actions.onTranscriptClearTimeoutSecsChange(it.roundToInt().toString()) }
                )
            }
        }
    }
}

@Composable
fun ConfigHistorySectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("Historico Local", "Frases, metadados e exportacao", onBack) {
        item {
            ConfigSection(title = "Arquivo") {
                MetadataChip("Frases registradas", state.historySnapshot.entryCount.toString())
                MetadataChip("Arquivo", state.historySnapshot.file.name)
                MetadataChip("Tamanho", formatBytes(state.historySnapshot.sizeBytes))
                if (state.historySnapshot.lastModifiedEpochMs > 0L) {
                    MetadataChip("Ultima atualizacao", formatHistoryTimestamp(state.historySnapshot.lastModifiedEpochMs))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = actions.onExportHistory,
                        enabled = state.historySnapshot.entryCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exportar CSV")
                    }
                    OutlinedButton(
                        onClick = actions.onClearHistory,
                        enabled = state.historySnapshot.entryCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Limpar")
                    }
                }
                if (state.historyActionStatus.isNotBlank()) {
                    Text(
                        text = state.historyActionStatus,
                        color = ConfigTheme.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigDebugSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("Depuracao", "Ajustes finos para segmentacao e diagnostico", onBack) {
        item {
            ConfigSection(title = "Captura") {
                OutlinedButton(
                    onClick = actions.onOpenGestureDebug,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abrir tela de depuracao da camera")
                }
                MetadataChip("Camera", state.cameraGestureStatus)
                OptionalSliderSettingRow(
                    title = "Speech Hold (ms)",
                    rawValue = state.debugSpeechHoldMsInput,
                    effectiveValue = state.debugSpeechHoldMsInput.toFloatOrNull()?.coerceIn(100f, 1200f) ?: 380f,
                    valueRange = 100f..1200f,
                    steps = 21,
                    supportingText = "Quanto tempo de silencio manter antes de fechar o segmento.",
                    onValueChange = { actions.onDebugSpeechHoldMsChange(it.roundToInt().toString()) },
                    onReset = { actions.onDebugSpeechHoldMsChange("") }
                )
                OptionalSliderSettingRow(
                    title = "Segmento Maximo (ms)",
                    rawValue = state.debugMaxSpeechSegmentMsInput,
                    effectiveValue = state.debugMaxSpeechSegmentMsInput.toFloatOrNull()?.coerceIn(300f, 5000f) ?: 1500f,
                    valueRange = 300f..5000f,
                    steps = 46,
                    supportingText = "Limite tecnico de cada lote enviado para transcricao.",
                    onValueChange = { actions.onDebugMaxSpeechSegmentMsChange(it.roundToInt().toString()) },
                    onReset = { actions.onDebugMaxSpeechSegmentMsChange("") }
                )
                OptionalSliderSettingRow(
                    title = "Segmento Minimo (ms)",
                    rawValue = state.debugMinTranscriptionMsInput,
                    effectiveValue = state.debugMinTranscriptionMsInput.toFloatOrNull()?.coerceIn(100f, 3000f) ?: 320f,
                    valueRange = 100f..3000f,
                    steps = 28,
                    supportingText = "Trechos menores que isso sao descartados.",
                    onValueChange = { actions.onDebugMinTranscriptionMsChange(it.roundToInt().toString()) },
                    onReset = { actions.onDebugMinTranscriptionMsChange("") }
                )
                OptionalSliderSettingRow(
                    title = "Quebra de Frase (ms)",
                    rawValue = state.debugPhraseBreakSilenceMsInput,
                    effectiveValue = state.debugPhraseBreakSilenceMsInput.toFloatOrNull()?.coerceIn(500f, 4000f) ?: 1400f,
                    valueRange = 500f..4000f,
                    steps = 34,
                    supportingText = "Silencio necessario para considerar que a proxima fala e uma nova frase.",
                    onValueChange = { actions.onDebugPhraseBreakSilenceMsChange(it.roundToInt().toString()) },
                    onReset = { actions.onDebugPhraseBreakSilenceMsChange("") }
                )
                SliderSettingRow(
                    title = "Controle de repeticao",
                    value = state.transcriptionRepeatSuppressionInput.replace(',', '.').toFloatOrNull()?.coerceIn(0f, 1f)
                        ?: GatewaySettingsStore.DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION.toFloat(),
                    valueText = when {
                        (state.transcriptionRepeatSuppressionInput.replace(',', '.').toFloatOrNull()
                            ?: GatewaySettingsStore.DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION.toFloat()) < 0.34f -> "Baixo"
                        (state.transcriptionRepeatSuppressionInput.replace(',', '.').toFloatOrNull()
                            ?: GatewaySettingsStore.DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION.toFloat()) < 0.67f -> "Medio"
                        else -> "Alto"
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    supportingText = "Quanto maior, mais agressivo o app fica para suprimir repeticoes e sobreposicoes de texto entre janelas.",
                    onValueChange = { actions.onTranscriptionRepeatSuppressionChange(String.format(Locale.US, "%.2f", it)) }
                )
            }
        }
        if (state.deviceGuide != null) {
            item {
                ConfigSection(title = "Guia do aparelho") {
                    DeviceGuideCard(
                        deviceModelLabel = state.deviceModelLabel,
                        guide = state.deviceGuide,
                        currentRecommendation = state.deviceGuide.findRecommendation(
                            transcriptionMode = state.transcriptionMode,
                            localModelId = state.localModelName,
                            localExecutionMode = state.localExecutionMode,
                            remoteModel = state.remoteModel
                        )
                    )
                }
            }
        }
    }
}

private data class AssistantVoiceOption(
    val persistedValue: String,
    val label: String
)

private fun assistantVoiceOptions(): List<AssistantVoiceOption> = listOf(
    AssistantVoiceOption(AssistantVoiceStyle.SYSTEM.persistedValue, "Sistema"),
    AssistantVoiceOption(AssistantVoiceStyle.FEMININE.persistedValue, "Feminina"),
    AssistantVoiceOption(AssistantVoiceStyle.MASCULINE.persistedValue, "Masculina")
)
