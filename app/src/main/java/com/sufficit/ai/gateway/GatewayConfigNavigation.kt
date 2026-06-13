package com.sufficit.ai.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class ConfigSectionCardState(
    val destination: ConfigSectionDestination,
    val title: String,
    val subtitle: String,
    val summary: String,
    val icon: ImageVector,
    val accent: Color
)

private fun configBackgroundBrush() = Brush.verticalGradient(
    colors = listOf(ConfigTheme.BgTop, ConfigTheme.BgBottom)
)

@Composable
fun ConfigHubPage(
    state: ConfigPageState,
    onOpenSection: (ConfigSectionDestination) -> Unit
) {
    val cards = buildList {
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.GENERAL,
                title = "Geral",
                subtitle = "Permissoes, escuta automatica e ativacao",
                summary = if (state.cameraGestureEnabled) {
                    state.cameraGestureStatus
                } else if (state.hasPermission) {
                    "Microfone pronto"
                } else {
                    "Permissao pendente"
                },
                icon = Icons.Filled.Settings,
                accent = Color(0xFF34D399)
            )
        )
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.OPENCLAW,
                title = "OpenClaw",
                subtitle = "WebSocket, sessao e skill do canal",
                summary = state.openClawSessionKey.ifBlank { "Sessao nao definida" },
                icon = Icons.AutoMirrored.Filled.Send,
                accent = Color(0xFF38BDF8)
            )
        )
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.TRANSCRIPTION,
                title = "Transcricao",
                subtitle = "Whisper, captura e modelos",
                summary = if (state.transcriptionMode == "remote") {
                    "Remoto: ${state.remoteModel}"
                } else {
                    "Local: ${state.localModelName}"
                },
                icon = Icons.Filled.Edit,
                accent = Color(0xFFFBBF24)
            )
        )
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.ASSISTANT_VOICE,
                title = "Voz do Assistente",
                subtitle = "TTS, velocidade e tom",
                summary = if (state.assistantVoiceEnabled) {
                    "Ativa • ${state.assistantSpeechRateInput}x"
                } else {
                    "Desligada"
                },
                icon = Icons.Filled.Call,
                accent = Color(0xFFC084FC)
            )
        )
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.SCREEN,
                title = "Tela",
                subtitle = "Wake e permanencia da tela",
                summary = state.screenMode,
                icon = Icons.Filled.Lock,
                accent = Color(0xFF2DD4BF)
            )
        )
        add(
            ConfigSectionCardState(
                destination = ConfigSectionDestination.HISTORY,
                title = "Historico Local",
                subtitle = "CSV exportavel e volume atual",
                summary = "${state.historySnapshot.entryCount} frases",
                icon = Icons.AutoMirrored.Filled.List,
                accent = Color(0xFFD9B864)
            )
        )
        if (state.development) {
            add(
                ConfigSectionCardState(
                    destination = ConfigSectionDestination.DEBUG,
                    title = "Depuracao",
                    subtitle = "Segmentacao e calibragem fina",
                    summary = "Controles tecnicos ativos",
                    icon = Icons.Filled.Build,
                    accent = Color(0xFFF87171)
                )
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = configBackgroundBrush())
            .padding(ConfigTheme.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Configuracao",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ConfigTheme.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Ajustes do gateway de voz da sala.",
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        items(cards.size) { index ->
            val card = cards[index]
            ConfigHubCard(
                state = card,
                onClick = { onOpenSection(card.destination) }
            )
        }
    }
}

@Composable
fun ConfigSectionScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = configBackgroundBrush())
            .padding(ConfigTheme.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBack)
                        .background(
                            color = ConfigTheme.Surface,
                            shape = RoundedCornerShape(ConfigTheme.RadiusCard)
                        )
                        .border(
                            width = 1.dp,
                            color = ConfigTheme.Border,
                            shape = RoundedCornerShape(ConfigTheme.RadiusCard)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = ConfigTheme.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = ConfigTheme.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ConfigTheme.TextSecondary
                        )
                    }
                }
            }
            content()
        }
    )
}

@Composable
private fun ConfigHubCard(
    state: ConfigSectionCardState,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = ConfigTheme.Surface,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .border(
                width = 1.dp,
                color = ConfigTheme.Border,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .padding(ConfigTheme.CardPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = state.accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(ConfigTheme.RadiusInner)
                )
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = state.title,
                tint = state.accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                color = ConfigTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ConfigTheme.TextSecondary
            )
            Text(
                text = state.summary,
                style = MaterialTheme.typography.labelLarge,
                color = state.accent,
                maxLines = 1
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ConfigTheme.TextMuted,
            modifier = Modifier.size(22.dp)
        )
    }
}
