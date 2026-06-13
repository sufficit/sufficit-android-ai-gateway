package com.sufficit.ai.gateway

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sufficit.ai.gateway.runtime.GatewayUiState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GestureDebugPage(
    state: GatewayUiState,
    previewView: PreviewView,
    onOpenConfig: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStartCameraDebug: () -> Unit,
    onStopCameraDebug: () -> Unit
) {
    val updatedAtLabel by produceState(initialValue = "-", key1 = state.gestureDebugUpdatedAtEpochMs) {
        value = if (state.gestureDebugUpdatedAtEpochMs > 0L) {
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(state.gestureDebugUpdatedAtEpochMs))
        } else {
            "-"
        }
        while (true) {
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040A12))
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x33040A12), Color(0x120A1723), Color(0x44040A12))
                    )
                )
        )
        // Luvas desenhadas pelo overlay global do MainActivity.

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (state.gestureDebugMatched) "MATCH" else "SEM MATCH",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (state.gestureDebugMatched) Color(0xFF6FF7B8) else Color(0xFFFF8A80),
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .background(Color(0x66000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                    Text(
                        text = state.gestureDebugDetectedLabel ?: "Nenhum gesto em match ainda",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFF4F7FB),
                        modifier = Modifier
                            .background(Color(0x4D000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Text(
                        text = state.gestureDebugReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD5E0EA),
                        modifier = Modifier
                            .background(Color(0x40000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DebugActionChip("Dashboard", Icons.Filled.Home, onOpenDashboard)
                    DebugActionChip("Config", Icons.Filled.Settings, onOpenConfig)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onStartCameraDebug, enabled = !state.gestureDebugActive) {
                        Text("Iniciar camera")
                    }
                    OutlinedButton(onClick = onStopCameraDebug, enabled = state.gestureDebugActive) {
                        Text("Parar camera")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x55000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OverlayMetricRow("Status", state.cameraGestureStatus)
                    OverlayMetricRow("Preview", if (state.gestureDebugPreviewAvailable) "VISIVEL" else "AGUARDANDO")
                    OverlayMetricRow("Camera", if (state.gestureDebugActive) "ATIVA" else "PARADA")
                    OverlayMetricRow("Mao", state.gestureDebugHandedness ?: "Nao detectada")
                    OverlayMetricRow("Landmarks", state.gestureDebugLandmarkCount.toString())
                    OverlayMetricRow("Atualizacao", updatedAtLabel)
                    OverlayBooleanGrid(
                        indexExtended = state.gestureDebugIndexExtended,
                        middleFolded = state.gestureDebugMiddleFolded,
                        ringFolded = state.gestureDebugRingFolded,
                        pinkyFolded = state.gestureDebugPinkyFolded,
                        thumbFolded = state.gestureDebugThumbFolded
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color(0x40000000), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Color(0xFFF4F7FB), modifier = Modifier.size(18.dp))
        Text(label, color = Color(0xFFF4F7FB), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OverlayMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF9EB5C7), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color(0xFFF4F7FB), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OverlayBooleanGrid(
    indexExtended: Boolean,
    middleFolded: Boolean,
    ringFolded: Boolean,
    pinkyFolded: Boolean,
    thumbFolded: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayBooleanChip("Indicador", indexExtended)
            OverlayBooleanChip("Medio", middleFolded)
            OverlayBooleanChip("Anelar", ringFolded)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayBooleanChip("Mindinho", pinkyFolded)
            OverlayBooleanChip("Polegar", thumbFolded)
        }
    }
}

@Composable
private fun OverlayBooleanChip(label: String, value: Boolean) {
    Box(
        modifier = Modifier
            .background(
                if (value) Color(0x6632D296) else Color(0x66C94B4B),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$label: ${if (value) "OK" else "NAO"}",
            color = Color(0xFFF4F7FB),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}