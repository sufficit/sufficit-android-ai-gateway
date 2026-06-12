package com.sufficit.openclaw.gateway

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
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime
import com.sufficit.openclaw.gateway.runtime.GatewayUiState
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
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onInterruptAssistant = onInterruptAssistant
                )
            }

            // Historico da conversa (mais novo embaixo, rente a barra).
            ChatMessagesList(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Barra de envio: espectro quando ouvindo; texto quando parado.
            ChatInputBar(
                state = state,
                onSendText = onSendText,
                onStartListening = onStart,
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

@Composable
fun SpectrumCard(state: GatewayUiState, development: Boolean) {
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = state.microphoneGainAdjustedUntilEpochMs
    ) {
        while (state.microphoneGainAdjustedUntilEpochMs > System.currentTimeMillis()) {
            value = System.currentTimeMillis()
            delay(220)
        }
        value = System.currentTimeMillis()
    }
    val showGainAdjustedWarning =
        state.microphoneGainAdjustedUntilEpochMs > nowEpochMs &&
            !state.microphoneGainAdjustedMessage.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(
                color = Color(0x221E3246),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val values = state.spectrum
                if (values.isEmpty()) {
                    return@Canvas
                }

                val spacing = 6.dp.toPx()
                val barWidth = (size.width - spacing * (values.size - 1)) / values.size
                val baseLine = size.height * 0.5f

                drawRoundRect(
                    color = Color(0x143A87E8),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 2.dp.toPx())
                )

                values.forEachIndexed { index, value ->
                    val normalized = value.coerceIn(0.05f, 1f)
                    val barHeight = normalized * (size.height * 0.28f)
                    val x = index * (barWidth + spacing)
                    val top = baseLine - barHeight
                    val bottom = baseLine + barHeight

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFFF6B5A), Color(0xFFF6D365), Color(0xFF39D0FF))
                        ),
                        topLeft = Offset(x, top),
                        size = androidx.compose.ui.geometry.Size(barWidth, bottom - top),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            if (state.ambientNoiseDetected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0x223CCB9D),
                                shape = RoundedCornerShape(99.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = when (state.ambientNoiseKind?.lowercase()) {
                                "music" -> "Musica ambiente"
                                else -> "Ruido ambiente"
                            },
                            color = Color(0xFFB9F6E8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (development) {
                Spacer(modifier = Modifier.height(10.dp))
                val micGainLabel = state.currentMicrophoneGain?.let { String.format(java.util.Locale.US, "%.2fx", it) } ?: "-"
                val noiseFloorLabel = state.estimatedNoiseFloorRms?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "-"
                val noiseScoreLabel = state.ambientNoiseScore?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"
                Text(
                    text = "Mic atual: $micGainLabel | Ruido base RMS: $noiseFloorLabel | Estabilidade: $noiseScoreLabel",
                    color = Color(0xFFA9BBCB),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Gain level — always visible, bottom-left
        val gainOverlayLabel = state.currentMicrophoneGain
            ?.let { String.format(java.util.Locale.US, "%.2fx", it) } ?: "-"
        Box(
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Text(
                text = gainOverlayLabel,
                color = Color(0xFF8AAFCC),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(Color(0x33000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }

        if (showGainAdjustedWarning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomEnd),
                contentAlignment = Alignment.BottomEnd
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            color = Color(0xA8B47B1D),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Ajuste de ganho aplicado",
                        tint = Color(0xFFFFE5A8),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = state.microphoneGainAdjustedMessage.orEmpty(),
                        color = Color(0xFFFFF3D5),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// TranscriptCard, TranscriptCardHint, OpenClawResponseCard, estimateResponseVisibilityMillis,
// BlockingAnnouncementBanner, InfoAnnouncementBanner, formatProbabilityPercent,
// ListeningDotsPlaceholder, ActionStrip — extracted to dedicated files.
// See GatewayTranscriptCard.kt, GatewayOpenClawResponseCard.kt, GatewayDashboardBanners.kt
