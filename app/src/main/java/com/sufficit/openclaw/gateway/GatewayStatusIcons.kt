@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sufficit.openclaw.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.runtime.GatewayUiState
import java.util.Locale

/** Row containing service status, backend status, and the start/stop control icon. */
@Composable
fun StatusIcons(
    state: GatewayUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterruptAssistant: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ServiceStatusIcon(state)
        BackendStatusIcon(state)
        GatewayControlIcon(
            listening = state.listening,
            transcribing = state.transcribing,
            speakingBack = state.speakingBack,
            onStart = onStart,
            onStop = onStop,
            onInterruptAssistant = onInterruptAssistant
        )
    }
}

/** Microphone / service state badge with tooltip. */
@Composable
fun ServiceStatusIcon(state: GatewayUiState) {
    val (color, text) = when {
        !state.lastError.isNullOrBlank() -> Color(0xFFD32F2F) to "Servico: erro"
        state.transcribing -> Color(0xFFFFA726) to "Servico: transcrevendo"
        state.listening -> Color(0xFF42D392) to "Servico: ativo"
        else -> Color(0xFF6C7A89) to "Servico: parado"
    }
    TooltipBadgeIcon(
        icon = {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic_status),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        },
        tooltip = text,
        color = color
    )
}

/** Transcription backend (local / remote) badge with model detail tooltip. */
@Composable
fun BackendStatusIcon(state: GatewayUiState) {
    val backend = state.transcriptionBackendLabel
    val model = state.transcriptionModelLabel.trim()
    val normalized = backend.lowercase(Locale.ROOT)
    val isLocal = normalized.contains("local") || normalized.contains("nnapi") || normalized.contains("cpu")
    val hasModelProblem = !state.lastError.isNullOrBlank() && run {
        val error = state.lastError.orEmpty().lowercase(Locale.ROOT)
        error.contains("modelo") ||
            error.contains("model") ||
            error.contains("nnapi") ||
            error.contains("whisper") ||
            error.contains("sherpa")
    }
    val backendColor = when {
        hasModelProblem -> Color(0xFFD32F2F)
        isLocal -> Color(0xFF3A7DDA)
        else -> Color(0xFF7B61FF)
    }
    TooltipBadgeIcon(
        icon = {
            Text(
                text = if (isLocal) "LOC" else "REM",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        tooltip = buildString {
            append("Backend: ")
            append(if (isLocal) "local" else "remoto")
            append(" (")
            append(backend)
            append(")")
            if (model.isNotEmpty()) {
                append("\nModelo: ")
                append(model)
            }
            if (hasModelProblem) {
                append("\nStatus do modelo: com problema")
            }
        },
        color = backendColor
    )
}

/** Play/stop/interrupt control icon with dropdown confirmation. */
@Composable
fun GatewayControlIcon(
    listening: Boolean,
    transcribing: Boolean,
    speakingBack: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterruptAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = listening || transcribing
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .combinedClickable(
                    onClick = {
                        if (isRunning) {
                            expanded = true
                        } else {
                            onStart()
                        }
                    },
                    onLongClick = {
                        expanded = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = when {
                            speakingBack -> Color(0xFFC98322)
                            isRunning -> Color(0xFFD94B4B)
                            else -> Color(0xFF2D8CFF)
                        },
                        shape = RoundedCornerShape(99.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        speakingBack -> Icons.Filled.Notifications
                        isRunning -> Icons.Filled.Close
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 8.dp)
        ) {
            if (speakingBack) {
                DropdownMenuItem(
                    text = { Text("Interromper voz") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        expanded = false
                        onInterruptAssistant()
                    }
                )
            }
            if (isRunning) {
                DropdownMenuItem(
                    text = { Text("Deseja parar a escuta?") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        expanded = false
                        onStop()
                    }
                )
            }
        }
    }
}
