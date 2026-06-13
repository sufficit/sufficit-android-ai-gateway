package com.sufficit.ai.gateway

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
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.LocalExecutionMode
import com.sufficit.ai.gateway.config.TranscriptionMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ConfigGeneralSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("Geral", "Permissao, autoplay e modo de trabalho", onBack) {
        item {
            ConfigSection(title = "Aplicativo") {
                MetadataChip("Microfone", if (state.hasPermission) "Autorizado" else "Sem permissao")
                MetadataChip("Camera", if (state.hasCameraPermission) "Autorizada" else "Sem permissao")
                SettingToggleRow(
                    title = "Escuta automatica",
                    supportingText = "Ativa a escuta assim que o app abrir.",
                    checked = state.autoStartEnabled,
                    onCheckedChange = actions.onAutoStartEnabledChange
                )
                SettingToggleRow(
                    title = "Gesto por camera",
                    supportingText = "Detecta um indicador levantado para acordar a tela e abrir o microfone.",
                    checked = state.cameraGestureEnabled,
                    onCheckedChange = actions.onCameraGestureEnabledChange
                )
                MetadataChip("Status do gesto", state.cameraGestureStatus)
                SettingToggleRow(
                    title = "Development",
                    supportingText = "Ativa diagnosticos e informacoes tecnicas extras.",
                    checked = state.development,
                    onCheckedChange = actions.onDevelopmentChange
                )
                OutlinedButton(onClick = actions.onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Permissao do microfone")
                }
                OutlinedButton(onClick = actions.onRequestCameraPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Permissao da camera")
                }
            }
        }
        item {
            HandSkinConfigSection()
        }
        item {
            WakeWordConfigSection()
        }
        item {
            SpeakerVoiceConfigSection()
        }
        item {
            ApiConfigSection()
        }
        item {
            ConfigSection(title = "Backup JSON") {
                Text(
                    text = "Exporte ou restaure todas as configuracoes do app em JSON para backup e padronizacao.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ConfigTheme.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = actions.onExportSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exportar JSON")
                    }
                    OutlinedButton(
                        onClick = actions.onImportSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Importar JSON")
                    }
                }
                if (state.settingsBackupStatus.isNotBlank()) {
                    Text(
                        text = state.settingsBackupStatus,
                        color = ConfigTheme.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigOpenClawSectionPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    ConfigSectionScaffold("OpenClaw", "WebSocket, sessao e contexto do canal", onBack) {
        item {
            ConfigSection(title = "Gateway") {
                OutlinedTextField(
                    value = state.openClawServerAddress,
                    onValueChange = actions.onOpenClawServerAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Endereço do servidor") },
                    supportingText = { Text("Ex.: your-openclaw-host.example.com") },
                    colors = configTextFieldColors()
                )
                OutlinedTextField(
                    value = state.openClawGatewayToken,
                    onValueChange = actions.onOpenClawGatewayTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token do gateway") },
                    supportingText = { Text("Token global do gateway websocket.") },
                    colors = configTextFieldColors()
                )
                OutlinedTextField(
                    value = state.openClawDeviceToken,
                    onValueChange = actions.onOpenClawDeviceTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token do device") },
                    supportingText = { Text("Token aprovado para este Android.") },
                    colors = configTextFieldColors()
                )
                OutlinedTextField(
                    value = state.openClawSessionKey,
                    onValueChange = actions.onOpenClawSessionKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sessao OpenClaw") },
                    supportingText = { Text("Ex.: agent:main:android:samsung:sm-a515f:abc123") },
                    colors = configTextFieldColors()
                )
            }
        }
        item {
            ConfigSection(title = "Skill do canal") {
                SettingToggleRow(
                    title = "Habilitar contexto conversacional",
                    supportingText = "Envia tudo ao OpenClaw, mas acrescenta pistas para ele decidir se a fala parece conversa ambiente ou continuidade do dialogo.",
                    checked = state.voiceChannelSkillEnabled,
                    onCheckedChange = actions.onVoiceChannelSkillEnabledChange
                )
                SliderSettingRow(
                    title = "Janela de continuidade",
                    value = state.voiceChannelFollowUpSecondsInput.toFloatOrNull()?.coerceIn(3f, 60f)
                        ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS.toFloat(),
                    valueText = "${state.voiceChannelFollowUpSecondsInput.toIntOrNull()?.coerceIn(3, 60) ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS}s",
                    valueRange = 3f..60f,
                    steps = 56,
                    supportingText = "Quanto tempo a conversa continua quente depois de uma resposta do assistente.",
                    onValueChange = { actions.onVoiceChannelFollowUpSecondsChange(it.roundToInt().toString()) }
                )
                SliderSettingRow(
                    title = "Tempo para pedir confirmacao",
                    value = state.voiceChannelIdlePromptSecondsInput.toFloatOrNull()?.coerceIn(30f, 1800f)
                        ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS.toFloat(),
                    valueText = "${state.voiceChannelIdlePromptSecondsInput.toIntOrNull()?.coerceIn(30, 1800) ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS}s",
                    valueRange = 30f..1800f,
                    steps = 58,
                    supportingText = "Depois desse tempo sem fala direta com o OpenClaw, ele deve preferir responder com duvida e pedir que voce fale o nome dele.",
                    onValueChange = { actions.onVoiceChannelIdlePromptSecondsChange(it.roundToInt().toString()) }
                )
                SliderSettingRow(
                    title = "Janela de acumulacao de fala",
                    value = state.openClawAccumulationWindowSecsInput.toFloatOrNull()?.coerceIn(1f, 10f)
                        ?: GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS.toFloat(),
                    valueText = "${state.openClawAccumulationWindowSecsInput.toIntOrNull()?.coerceIn(1, 10) ?: GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS}s",
                    valueRange = 1f..10f,
                    steps = 8,
                    supportingText = "Silencio que o app aguarda antes de enviar a transcricao ao OpenClaw. Aumente para agrupar frases longas em um unico envio.",
                    onValueChange = { actions.onOpenClawAccumulationWindowSecsChange(it.roundToInt().toString()) }
                )
                OutlinedTextField(
                    value = state.voiceChannelWakeTermsInput,
                    onValueChange = actions.onVoiceChannelWakeTermsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Apelidos e palavras de chamada") },
                    supportingText = { Text("Uma por linha. Quando um desses nomes aparecer, o OpenClaw prioriza resposta em voz e reinicia o contexto local.") },
                    colors = configTextFieldColors()
                )
            }
        }
    }
}

// ConfigTranscriptionSectionPage and LocalTranscriptionSection
// extracted to GatewayConfigTranscriptionSection.kt
