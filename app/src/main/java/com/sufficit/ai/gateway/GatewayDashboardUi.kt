package com.sufficit.ai.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.runtime.GatewayUiState
import kotlinx.coroutines.delay

/**
 * Dashboard em formato de conversa (estilo WhatsApp/Telegram):
 *  - cabecalho compacto com status e controles;
 *  - historico de mensagens de baixo para cima (ChatMessagesList);
 *  - barra de envio no rodape (ChatInputBar): espectro de voz enquanto a
 *    escuta esta ativa; campo de texto + enviar quando parada.
 */
@Composable
fun DashboardPage(
    state: GatewayUiState,
    isActivePage: Boolean,
    development: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterruptAssistant: () -> Unit,
    onSendText: (String) -> Unit
) {
    val blockingAnnouncement = state.blockingAnnouncementMessage?.trim()?.takeIf { it.isNotBlank() }

    val nowForSystemInfo by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = state.systemInfoMessageUntilEpochMs
    ) {
        while (state.systemInfoMessageUntilEpochMs > System.currentTimeMillis()) {
            value = System.currentTimeMillis()
            delay(500)
        }
        value = System.currentTimeMillis()
    }
    val showSystemInfoBanner =
        state.systemInfoMessageUntilEpochMs > nowForSystemInfo &&
            !state.systemInfoMessage.isNullOrBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF08111C), Color(0xFF13283A), Color(0xFF08111C))
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Cabecalho compacto: titulo + status + controles existentes.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sala IA",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFFF2EFE8),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            state.speechDetected -> "Fala no ambiente"
                            state.listening -> "Ouvindo"
                            else -> state.statusText
                        },
                        color = if (state.speechDetected) Color(0xFFFF6B5A) else Color(0xFF90C7FF),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusIcons(
                    lastError = state.lastError,
                    transcribing = state.transcribing,
                    listening = state.listening,
                    speakingBack = state.speakingBack,
                    transcriptionBackendLabel = state.transcriptionBackendLabel,
                    transcriptionModelLabel = state.transcriptionModelLabel,
                    onStart = onStart,
                    onStop = onStop,
                    onInterruptAssistant = onInterruptAssistant
                )
            }

            // Historico da conversa (mais novo embaixo, rente a barra).
            ChatMessagesList(
                currentTranscript = state.currentTranscript,
                assistantProcessing = state.assistantProcessing,
                assistantProcessingLabel = state.assistantProcessingLabel,
                lastError = state.lastError,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // O espectro representa somente a escuta ambiente (nivel 2).
            // No modo texto, o nivel 1 continua monitorando a wake word sem
            // disputar espaco com o campo de digitacao.
            ChatInputBar(
                ambientListening = state.listening,
                isActivePage = isActivePage,
                currentMicrophoneGain = state.currentMicrophoneGain,
                onSendText = onSendText,
                onStartListening = onStart,
                onSwitchToTextInput = onStop,
                onAttach = {
                    GatewayRuntime.update {
                        it.copy(
                            systemInfoMessage = "Envio de anexos chega em breve.",
                            systemInfoMessageUntilEpochMs = System.currentTimeMillis() + 4_000L
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )

            if (development) {
                val micGainLabel = state.currentMicrophoneGain?.let { String.format(java.util.Locale.US, "%.2fx", it) } ?: "-"
                val noiseFloorLabel = state.estimatedNoiseFloorRms?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "-"
                Text(
                    text = "Mic: $micGainLabel | Ruido base: $noiseFloorLabel",
                    color = Color(0xFF6E8398),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }
        }

        if (blockingAnnouncement != null) {
            BlockingAnnouncementBanner(
                message = blockingAnnouncement,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 86.dp)
                    .zIndex(3f),
                confidence = state.lastAssistantReplyConfidence,
                overlap = state.lastAssistantReplyOverlap,
                tags = state.lastAssistantReplyTags
            )
        }

        if (showSystemInfoBanner && !state.systemInfoMessage.isNullOrBlank()) {
            InfoAnnouncementBanner(
                message = state.systemInfoMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 28.dp, vertical = if (blockingAnnouncement != null) 190.dp else 86.dp)
                    .zIndex(2f)
            )
        }
    }
}


// OpenClawResponseCard, estimateResponseVisibilityMillis, BlockingAnnouncementBanner,
// InfoAnnouncementBanner, formatProbabilityPercent, ListeningDotsPlaceholder, ActionStrip —
// extracted to dedicated files.
// See GatewayOpenClawResponseCard.kt, GatewayDashboardBanners.kt
