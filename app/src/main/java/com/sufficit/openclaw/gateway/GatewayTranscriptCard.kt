package com.sufficit.openclaw.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.runtime.GatewayUiState
import kotlinx.coroutines.delay

/** Hint that is currently shown as a tooltip on the transcript card. */
enum class TranscriptCardHint {
    Listening,
    Queue,
    OpenClawDispatch,
    Gender,
    Emotion,
    SameVoice,
    Learning
}

/** Card showing the live transcript, metadata badges, and the last error. */
@Composable
fun TranscriptCard(state: GatewayUiState) {
    var activeHint by remember { mutableStateOf<TranscriptCardHint?>(null) }
    var hintNonce by remember { mutableStateOf(0) }
    val metadataUnavailableLabel = if (state.transcriptionBackendLabel.contains("Local", ignoreCase = true)) {
        "N/D local"
    } else {
        "-"
    }

    LaunchedEffect(activeHint, hintNonce) {
        if (activeHint != null) {
            val currentHint = activeHint
            val currentNonce = hintNonce
            delay(1800)
            if (activeHint == currentHint && hintNonce == currentNonce) {
                activeHint = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFFF3EBDD),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningBadgeIcon(
                        listening = state.listening,
                        speechDetected = state.speechDetected,
                        active = activeHint == TranscriptCardHint.Listening,
                        tooltip = when {
                            state.speechDetected -> "Escuta ativa: fala detectada agora."
                            state.listening -> "Escuta ativa: monitorando o ambiente."
                            else -> "Escuta inativa."
                        },
                        onDismiss = { if (activeHint == TranscriptCardHint.Listening) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.Listening) null else TranscriptCardHint.Listening
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    )
                    QueueBadgeIcon(
                        queueCount = state.transcriptionQueueCount,
                        active = activeHint == TranscriptCardHint.Queue,
                        tooltip = if (state.transcriptionQueueCount > 0) {
                            "Fila de transcricao: ${state.transcriptionQueueCount} item(ns) pendente(s)."
                        } else {
                            "Fila de transcricao vazia."
                        },
                        onDismiss = { if (activeHint == TranscriptCardHint.Queue) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.Queue) null else TranscriptCardHint.Queue
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    )
                    OpenClawDispatchBadgeIcon(
                        queueCount = state.openClawDispatchQueueCount,
                        active = activeHint == TranscriptCardHint.OpenClawDispatch,
                        tooltip = if (state.openClawDispatchQueueCount > 0) {
                            "Envio ao OpenClaw: ${state.openClawDispatchQueueCount} item(ns) aguardando despacho."
                        } else {
                            "Envio ao OpenClaw sem pendencias."
                        },
                        onDismiss = { if (activeHint == TranscriptCardHint.OpenClawDispatch) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.OpenClawDispatch) null else TranscriptCardHint.OpenClawDispatch
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val detectedGender = remember(state.lastGender) {
                        resolveDetectedGender(state.lastGender)
                    }
                    CompactMetricBadge(
                        tint = genderTint(detectedGender),
                        active = activeHint == TranscriptCardHint.Gender,
                        tooltip = "Genero detectado: ${state.lastGender ?: metadataUnavailableLabel}",
                        onDismiss = { if (activeHint == TranscriptCardHint.Gender) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.Gender) null else TranscriptCardHint.Gender
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    ) {
                        Text(
                            text = genderBadgeLabel(detectedGender),
                            color = genderTint(detectedGender),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    val detectedEmotion = remember(state.lastEmotion) {
                        resolveDetectedEmotion(state.lastEmotion)
                    }
                    CompactMetricBadge(
                        tint = emotionTint(detectedEmotion),
                        active = activeHint == TranscriptCardHint.Emotion,
                        tooltip = "Emocao detectada: ${state.lastEmotion ?: metadataUnavailableLabel}",
                        onDismiss = { if (activeHint == TranscriptCardHint.Emotion) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.Emotion) null else TranscriptCardHint.Emotion
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    ) {
                        Icon(
                            imageVector = emotionIcon(detectedEmotion),
                            contentDescription = emotionContentDescription(detectedEmotion),
                            tint = emotionTint(detectedEmotion),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    CompactMetricBadge(
                        tint = sameVoiceTint(state.sameSpeakerProbability),
                        active = activeHint == TranscriptCardHint.SameVoice,
                        tooltip = sameVoiceTooltip(state.sameSpeakerProbability),
                        onDismiss = { if (activeHint == TranscriptCardHint.SameVoice) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.SameVoice) null else TranscriptCardHint.SameVoice
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mic_status),
                            contentDescription = "Mesma voz",
                            tint = sameVoiceTint(state.sameSpeakerProbability),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    CompactMetricBadge(
                        tint = learningTint(state.voiceLearningProgress),
                        active = activeHint == TranscriptCardHint.Learning,
                        tooltip = learningTooltip(state.voiceLearningProgress),
                        onDismiss = { if (activeHint == TranscriptCardHint.Learning) activeHint = null },
                        onToggle = {
                            val next = if (activeHint == TranscriptCardHint.Learning) null else TranscriptCardHint.Learning
                            activeHint = next
                            if (next != null) hintNonce += 1
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Aprendizado",
                            tint = learningTint(state.voiceLearningProgress),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.currentTranscript.isBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                ListeningDotsPlaceholder(
                    active = state.listening,
                    color = Color(0xFF6C7A86)
                )
            } else {
                Text(
                    text = state.currentTranscript,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF102030)
                )
            }

            state.sameSpeakerProbability?.let { probability ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Confianca local de mesma voz: ${formatProbabilityPercent(probability)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B5A3A),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val stackedTranscripts = remember(state.currentTranscript, state.recentTranscripts, state.previousTranscript) {
                val fallback = state.previousTranscript.trim().takeIf { it.isNotBlank() }
                buildList {
                    addAll(state.recentTranscripts)
                    if (fallback != null && none { it.equals(fallback, ignoreCase = true) }) {
                        add(fallback)
                    }
                }.take(4)
            }

            if (stackedTranscripts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stackedTranscripts.forEachIndexed { index, transcript ->
                        val alpha = (0.62f - (index * 0.14f)).coerceIn(0.18f, 0.62f)
                        Text(
                            text = transcript,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { this.alpha = alpha },
                            style = when (index) {
                                0 -> MaterialTheme.typography.bodyLarge
                                1 -> MaterialTheme.typography.bodyMedium
                                else -> MaterialTheme.typography.bodySmall
                            },
                            color = Color(0xFF3A4A58),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            if (!state.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Ultimo erro",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF7A1F1F),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.lastError,
                    color = Color(0xFF7A1F1F),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
