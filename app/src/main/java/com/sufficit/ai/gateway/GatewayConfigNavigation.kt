package com.sufficit.ai.gateway

/*
THESIS: configuracao e um mapa curto de caminhos, nunca uma lista de paineis tecnicos.
OWN-WORLD: noite azul de sala, superficies tonais firmes, nos luminosos e controles Material 3.
STORY: a pessoa escolhe uma area, conclui uma tarefa clara e volta sabendo o estado do sistema.
FIRST VIEWPORT: estado geral, uma missao principal ampla e tres caminhos compactos; uma acao por bloco.
FORM [SEED: user-brief-game-map]: mapa de missoes sem pontos, ranking ou premiacao; ludico pela navegacao, nao por decoracao.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, and DESIGN.md
*/

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.config.ScreenMode
import com.sufficit.ai.gateway.runtime.GatewayRuntime

private data class ConfigPathState(
    val destination: ConfigSectionDestination,
    val title: String,
    val subtitle: String,
    val status: String,
    val icon: ImageVector,
    val accent: Color,
    val featured: Boolean = false
)

private fun configBackgroundBrush() = Brush.verticalGradient(
    colors = listOf(ConfigTheme.BgTop, ConfigTheme.BgBottom)
)

@Composable
fun ConfigHubPage(
    state: ConfigPageState,
    onOpenSection: (ConfigSectionDestination) -> Unit
) {
    val wake by GatewayRuntime.wakeWord().collectAsState()
    val setupReady = state.hasPermission && state.openClawSessionKey.isNotBlank()
    val wakeReady = wake.readyProfileCount > 0
    val systemReady = setupReady && wakeReady
    val paths = listOf(
        ConfigPathState(
            destination = ConfigSectionDestination.START,
            title = "Começar",
            subtitle = "Prepare o acesso e ensine como chamar o agente",
            status = when {
                setupReady && wakeReady -> "Tudo pronto"
                setupReady -> "Falta ensinar uma chamada"
                else -> "Sua primeira missão"
            },
            icon = Icons.Filled.PlayArrow,
            accent = ConfigTheme.Accent,
            featured = true
        ),
        ConfigPathState(
            destination = ConfigSectionDestination.VOICE,
            title = "Ouvir e falar",
            subtitle = "Microfone transcrição e voz",
            status = if (state.assistantVoiceEnabled) "Conversa por voz ativa" else "Resposta em texto",
            icon = Icons.Filled.Call,
            accent = ConfigTheme.AccentGold
        ),
        ConfigPathState(
            destination = ConfigSectionDestination.CONNECTIONS,
            title = "Agente e ferramentas",
            subtitle = "OpenClaw MCP e memórias",
            status = if (state.openClawSessionKey.isBlank()) "Agente ainda não conectado" else "Agente conectado",
            icon = Icons.Filled.Build,
            accent = ConfigTheme.AccentBlue
        ),
        ConfigPathState(
            destination = ConfigSectionDestination.DEVICE,
            title = "Este aparelho",
            subtitle = "Permissões tela histórico e backup",
            status = if (state.hasPermission) "Microfone autorizado" else "Permissão pendente",
            icon = Icons.Filled.Settings,
            accent = ConfigTheme.AccentPurple
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = configBackgroundBrush())
            .padding(ConfigTheme.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ConfigHubHeader(systemReady = systemReady)
        }
        item {
            ConfigPathCard(paths.first(), onClick = { onOpenSection(paths.first().destination) })
        }
        items(paths.size - 1) { index ->
            val path = paths[index + 1]
            ConfigPathCard(path, onClick = { onOpenSection(path.destination) })
        }
        item {
            Text(
                text = "Os ajustes técnicos continuam dentro de cada caminho",
                color = ConfigTheme.TextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ConfigHubHeader(systemReady: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    color = if (systemReady) ConfigTheme.Accent else ConfigTheme.AccentGold,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (systemReady) Icons.Filled.CheckCircle else Icons.Filled.Star,
                contentDescription = null,
                tint = ConfigTheme.BgTop,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Seu gateway",
                style = MaterialTheme.typography.headlineMedium,
                color = ConfigTheme.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (systemReady) "Pronto para conversar" else "Vamos deixar tudo pronto",
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ConfigCategoryPage(
    destination: ConfigSectionDestination,
    state: ConfigPageState,
    onOpenSection: (ConfigSectionDestination) -> Unit,
    onBack: () -> Unit
) {
    val wake by GatewayRuntime.wakeWord().collectAsState()
    val (title, subtitle, paths) = when (destination) {
        ConfigSectionDestination.START -> Triple(
            "Começar",
            "Duas etapas para o primeiro uso",
            listOf(
                ConfigPathState(
                    ConfigSectionDestination.WIZARD,
                    "Preparar o aplicativo",
                    "Acesso ao agente e transcrição",
                    if (state.hasPermission) "Base pronta" else "Comece por aqui",
                    Icons.Filled.CheckCircle,
                    ConfigTheme.Accent
                ),
                ConfigPathState(
                    ConfigSectionDestination.WAKE_WORD,
                    "Ensinar uma chamada",
                    "Escolha uma ou varias palavras",
                    if (wake.readyProfileCount > 0) "${wake.readyProfileCount} chamada pronta" else "Treinamento pendente",
                    Icons.Filled.Star,
                    ConfigTheme.AccentGold
                )
            )
        )
        ConfigSectionDestination.VOICE -> Triple(
            "Ouvir e falar",
            "Escolha como a conversa acontece",
            listOf(
                ConfigPathState(
                    ConfigSectionDestination.TRANSCRIPTION,
                    "Entender sua voz",
                    "Captura modelo e sensibilidade",
                    if (state.transcriptionMode == "remote") state.remoteModel else state.localModelName,
                    Icons.Filled.Edit,
                    ConfigTheme.AccentGold
                ),
                ConfigPathState(
                    ConfigSectionDestination.ASSISTANT_VOICE,
                    "Responder em voz",
                    "Voz velocidade e tom",
                    if (state.assistantVoiceEnabled) "Ativa" else "Desligada",
                    Icons.Filled.Call,
                    ConfigTheme.AccentPurple
                )
            )
        )
        ConfigSectionDestination.CONNECTIONS -> Triple(
            "Agente e ferramentas",
            "Conecte a inteligência e os poderes",
            listOf(
                ConfigPathState(
                    ConfigSectionDestination.OPENCLAW,
                    "Agente remoto",
                    "OpenClaw sessão e contexto",
                    if (state.openClawSessionKey.isBlank()) "Precisa configurar" else "Sessao pronta",
                    Icons.AutoMirrored.Filled.Send,
                    ConfigTheme.AccentBlue
                ),
                ConfigPathState(
                    ConfigSectionDestination.MCP,
                    "Ferramentas MCP",
                    "Tools prompts recursos e memórias",
                    "Descoberta automática",
                    Icons.Filled.Build,
                    ConfigTheme.Accent
                ),
                ConfigPathState(
                    ConfigSectionDestination.ACCESS,
                    "Conta e acesso",
                    "Identidade Sufficit e API local",
                    "Credenciais deste aparelho",
                    Icons.Filled.Lock,
                    ConfigTheme.AccentPurple
                )
            )
        )
        ConfigSectionDestination.DEVICE -> Triple(
            "Este aparelho",
            "Controle o que acontece no celular",
            buildList {
                add(
                    ConfigPathState(
                        ConfigSectionDestination.GENERAL,
                        "Permissões e sensores",
                        "Microfone câmera gestos e voz",
                        if (state.hasPermission) "Microfone autorizado" else "Permissão pendente",
                        Icons.Filled.Settings,
                        ConfigTheme.Accent
                    )
                )
                add(
                    ConfigPathState(
                        ConfigSectionDestination.SCREEN,
                        "Tela e energia",
                        "Quando acender e quando descansar",
                        when (ScreenMode.fromPersistedValue(state.screenMode)) {
                            ScreenMode.ALWAYS_ON -> "Sempre ligada"
                            ScreenMode.ALWAYS_OFF -> "Sempre apagada"
                            ScreenMode.ACTIVITY -> "Por atividade"
                        },
                        Icons.Filled.Lock,
                        ConfigTheme.AccentTeal
                    )
                )
                add(
                    ConfigPathState(
                        ConfigSectionDestination.HISTORY,
                        "Histórico e backup",
                        "Exportar restaurar ou limpar",
                        "${state.historySnapshot.entryCount} frases salvas",
                        Icons.AutoMirrored.Filled.List,
                        ConfigTheme.AccentGold
                    )
                )
                if (state.development) {
                    add(
                        ConfigPathState(
                            ConfigSectionDestination.DEBUG,
                            "Oficina técnica",
                            "Calibragem e diagnóstico",
                            "Modo de desenvolvimento ativo",
                            Icons.Filled.Build,
                            ConfigTheme.Danger
                        )
                    )
                }
            }
        )
        else -> error("Destino nao e uma categoria: $destination")
    }

    ConfigSectionScaffold(title, subtitle, onBack) {
        items(paths.size) { index ->
            val path = paths[index]
            ConfigPathCard(path, onClick = { onOpenSection(path.destination) })
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
                        .padding(top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = ConfigTheme.TextPrimary
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = ConfigTheme.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
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
private fun ConfigPathCard(state: ConfigPathState, onClick: () -> Unit) {
    val background = if (state.featured) ConfigTheme.SurfaceRaised else ConfigTheme.Surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (state.featured) 132.dp else 104.dp)
            .clickable(onClick = onClick)
            .background(background, RoundedCornerShape(ConfigTheme.RadiusCard))
            .border(1.dp, state.accent.copy(alpha = if (state.featured) 0.72f else 0.34f), RoundedCornerShape(ConfigTheme.RadiusCard))
            .padding(horizontal = 18.dp, vertical = if (state.featured) 20.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (state.featured) 58.dp else 48.dp)
                .background(state.accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = state.icon,
                contentDescription = null,
                tint = state.accent,
                modifier = Modifier.size(if (state.featured) 30.dp else 25.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = state.title,
                style = if (state.featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = ConfigTheme.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ConfigTheme.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelLarge,
                color = state.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Abrir ${state.title}",
            tint = state.accent,
            modifier = Modifier.size(26.dp)
        )
    }
}
