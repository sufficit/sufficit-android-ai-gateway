package com.sufficit.openclaw.gateway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.config.GatewaySettingsStore
import com.sufficit.openclaw.gateway.config.LocalExecutionMode
import com.sufficit.openclaw.gateway.config.TranscriptionMode
import java.util.Locale

/** Transcription pipeline, capture, and local-model config section. */
@Composable
fun ConfigTranscriptionSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    var localModelDropdownExpanded by rememberSaveable { mutableStateOf(false) }

    ConfigSectionScaffold("Transcricao", "Whisper, captura e modelos", onBack) {
        item {
            ConfigSection(title = "Modo") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranscriptionMode.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { actions.onTranscriptionModeChange(option.persistedValue) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (option == TranscriptionMode.REMOTE) "Remoto" else "Local")
                        }
                    }
                }
                MetadataChip(
                    "Modo atual",
                    if (TranscriptionMode.fromPersistedValue(state.transcriptionMode) == TranscriptionMode.REMOTE) "Remoto" else "Local"
                )
            }
        }
        item {
            ConfigSection(title = "Pipeline") {
                if (TranscriptionMode.fromPersistedValue(state.transcriptionMode) == TranscriptionMode.REMOTE) {
                    OutlinedTextField(
                        value = state.whisperUrl,
                        onValueChange = actions.onWhisperUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Endpoint remoto") },
                        supportingText = { Text("Ex.: https://your-whisper-host.example.com/v1/audio/transcriptions") },
                        colors = configTextFieldColors()
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.remoteModel,
                            onValueChange = actions.onRemoteModelChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Modelo remoto") },
                            supportingText = { Text("Ex.: large-v3-turbo") },
                            colors = configTextFieldColors()
                        )
                        resolveFieldGuideTooltip(
                            deviceGuide = state.deviceGuide,
                            transcriptionMode = state.transcriptionMode,
                            localModelName = state.localModelName,
                            localExecutionMode = state.localExecutionMode,
                            remoteModel = state.remoteModel,
                            field = "remote_model"
                        )?.let { tooltip ->
                            FieldGuideInfoIcon(tooltip = tooltip, modifier = Modifier.align(Alignment.TopEnd))
                        }
                    }
                    OutlinedTextField(
                        value = state.whisperAuthToken,
                        onValueChange = actions.onWhisperAuthTokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bearer token") },
                        supportingText = { Text("Opcional quando o endpoint remoto nao exige autenticacao.") },
                        colors = configTextFieldColors()
                    )
                } else {
                    LocalTranscriptionSection(
                        state = state,
                        actions = actions,
                        localModelDropdownExpanded = localModelDropdownExpanded,
                        onLocalModelDropdownExpandedChange = { localModelDropdownExpanded = it }
                    )
                }
            }
        }
        item {
            ConfigSection(title = "Captura") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onMicrophoneAutoSensitivityEnabledChange(!state.microphoneAutoSensitivityEnabled) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingToggleRow(
                        title = "Ajuste automatico do microfone",
                        supportingText = "Reduz ganho no ruido de fundo e libera o ganho maximo ao detectar fala. Padrao: habilitado.",
                        checked = state.microphoneAutoSensitivityEnabled,
                        onCheckedChange = actions.onMicrophoneAutoSensitivityEnabledChange
                    )
                }
                SliderSettingRow(
                    title = if (state.microphoneAutoSensitivityEnabled) "Ganho maximo da fala" else "Ganho do microfone",
                    value = state.microphoneGainInput.replace(',', '.').toFloatOrNull()?.coerceIn(1f, 6f)
                        ?: GatewaySettingsStore.DEFAULT_MICROPHONE_GAIN.toFloat(),
                    valueText = String.format(
                        Locale.US,
                        "%.1fx",
                        state.microphoneGainInput.replace(',', '.').toFloatOrNull()?.coerceIn(1f, 6f)
                            ?: GatewaySettingsStore.DEFAULT_MICROPHONE_GAIN.toFloat()
                    ),
                    valueRange = 1f..6f,
                    steps = 49,
                    supportingText = if (state.microphoneAutoSensitivityEnabled) {
                        "No ajuste automatico, este valor vira o pico usado quando a fala e detectada."
                    } else {
                        "Amplifica a captura antes do VAD e da transcricao."
                    },
                    onValueChange = { actions.onMicrophoneGainChange(String.format(Locale.US, "%.1f", it)) }
                )
                SliderSettingRow(
                    title = "Limiar VAD",
                    value = state.vadThresholdInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.001f, 0.05f)
                        ?: GatewaySettingsStore.DEFAULT_VAD_THRESHOLD.toFloat(),
                    valueText = String.format(
                        Locale.US,
                        "%.3f",
                        state.vadThresholdInput.replace(',', '.').toFloatOrNull()?.coerceIn(0.001f, 0.05f)
                            ?: GatewaySettingsStore.DEFAULT_VAD_THRESHOLD.toFloat()
                    ),
                    valueRange = 0.001f..0.05f,
                    steps = 48,
                    supportingText = "Menor valor detecta fala mais facilmente; maior valor exige voz mais forte.",
                    onValueChange = { actions.onVadThresholdChange(String.format(Locale.US, "%.3f", it)) }
                )
            }
        }
    }
}

@Composable
private fun LocalTranscriptionSection(
    state: ConfigPageState,
    actions: ConfigPageActions,
    localModelDropdownExpanded: Boolean,
    onLocalModelDropdownExpandedChange: (Boolean) -> Unit
) {
    OutlinedTextField(
        value = state.localModelName,
        onValueChange = actions.onLocalModelNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Modelo local") },
        supportingText = { Text("Sugestao: sherpa-whisper-small") },
        singleLine = true,
        colors = configTextFieldColors()
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onLocalModelDropdownExpandedChange(true) },
            enabled = state.localModelOptions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.localModelOptionsLoading) "Carregando modelos locais..." else "Selecionar da pasta local (${state.localModelOptions.size})")
        }
        DropdownMenu(
            expanded = localModelDropdownExpanded,
            onDismissRequest = { onLocalModelDropdownExpandedChange(false) }
        ) {
            state.localModelOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.name)
                            Text(
                                text = "${option.status} • SHA-256 ${option.sha256.take(12)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (option.isInvalid) ConfigTheme.Danger else ConfigTheme.TextSecondary
                            )
                        }
                    },
                    onClick = {
                        actions.onLocalModelNameChange(option.name)
                        onLocalModelDropdownExpandedChange(false)
                    }
                )
            }
        }
    }
    MetadataChip("Arquivo local", if (state.localModelExists) "Encontrado" else "Nao encontrado")
    selectedModelOption(state.localModelName, state.localModelOptions)?.let { selected ->
        MetadataChip("Checksum SHA-256", selected.sha256)
        MetadataChip("Integridade", selected.status)
    }
    MetadataChip(
        "Hugging Face",
        when {
            state.huggingFaceCheckInProgress -> "Verificando..."
            state.huggingFaceModelExists == true -> "Disponivel"
            state.huggingFaceModelExists == false -> "Nao encontrado"
            else -> "-"
        }
    )
    if (state.shouldOfferDownload) {
        if (state.localModelDownloadInProgress) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { state.localModelDownloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = state.localModelDownloadProgressLabel.ifBlank { "Baixando..." },
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Button(
                onClick = actions.onDownloadLocalModel,
                enabled = !state.huggingFaceCheckInProgress && state.huggingFaceModelExists == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.selectedModelInvalid) "Baixar novamente" else "Baixar do Hugging Face")
            }
        }
    }
    if (state.localModelDownloadStatus.isNotBlank()) {
        Text(
            text = state.localModelDownloadStatus,
            color = ConfigTheme.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalExecutionMode.entries.forEach { option ->
            OutlinedButton(
                onClick = { actions.onLocalExecutionModeChange(option.persistedValue) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (option == LocalExecutionMode.CPU) "CPU" else "NNAPI")
            }
        }
    }
    MetadataChip(
        "Backend local",
        if (LocalExecutionMode.fromPersistedValue(state.localExecutionMode) == LocalExecutionMode.CPU) "CPU" else "NNAPI"
    )
    MetadataChip("Runtime local", state.localSystemInfo)
}
