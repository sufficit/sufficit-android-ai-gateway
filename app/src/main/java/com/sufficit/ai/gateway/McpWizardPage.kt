package com.sufficit.ai.gateway

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.mcp.McpAuthenticationMode
import com.sufficit.ai.gateway.mcp.McpServerConfiguration
import com.sufficit.ai.gateway.mcp.McpServerDiscoveryResult
import com.sufficit.ai.gateway.mcp.McpServerStore
import com.sufficit.ai.gateway.mcp.SufficitMcpToolBridge
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val MCP_LAB_STEP_COUNT = 3

private enum class McpLabStage(val step: Int) {
    OVERVIEW(0),
    CONNECTION(1),
    AUTHENTICATION(2),
    DISCOVERY(3)
}

@Composable
fun McpWizardPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { McpServerStore(context.applicationContext) }
    val bridge = remember { SufficitMcpToolBridge(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(store.list()) }
    var stageName by rememberSaveable { mutableStateOf(McpLabStage.OVERVIEW.name) }
    val stage = McpLabStage.valueOf(stageName)
    var draft by remember { mutableStateOf<McpServerConfiguration?>(null) }
    var validationError by rememberSaveable { mutableStateOf("") }
    var discoveringId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastDiscovery by remember { mutableStateOf<McpServerDiscoveryResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun reloadServers() {
        servers = store.list()
        draft = draft?.id?.let(store::find) ?: draft
    }

    fun goTo(target: McpLabStage) {
        validationError = ""
        stageName = target.name
    }

    fun closeEditor() {
        draft = null
        lastDiscovery = null
        validationError = ""
        stageName = McpLabStage.OVERVIEW.name
        reloadServers()
    }

    fun previous() {
        when (stage) {
            McpLabStage.OVERVIEW -> onBack()
            McpLabStage.CONNECTION -> closeEditor()
            McpLabStage.AUTHENTICATION -> goTo(McpLabStage.CONNECTION)
            McpLabStage.DISCOVERY -> goTo(McpLabStage.AUTHENTICATION)
        }
    }

    fun validateConnection(value: McpServerConfiguration): String? {
        if (value.name.trim().length < 2) return "Dê um nome com pelo menos dois caracteres."
        val uri = runCatching { android.net.Uri.parse(value.endpoint.trim()) }.getOrNull()
        if (
            uri == null ||
            uri.scheme !in setOf("https", "http") ||
            uri.host.isNullOrBlank()
        ) {
            return "Informe uma URL MCP completa, começando com https:// ou http://."
        }
        return null
    }

    fun validateAuthentication(value: McpServerConfiguration): String? =
        if (
            value.authenticationMode == McpAuthenticationMode.BEARER &&
            value.bearerToken.isBlank()
        ) {
            "O modo Bearer exige um token."
        } else {
            null
        }

    fun saveDraft(): McpServerConfiguration? {
        val current = draft ?: return null
        validateConnection(current)?.let {
            validationError = it
            return null
        }
        validateAuthentication(current)?.let {
            validationError = it
            return null
        }
        val saved = store.save(current)
        draft = saved
        reloadServers()
        RoomAudioForegroundService.reloadConfig(context)
        return saved
    }

    fun discover(configuration: McpServerConfiguration, openResult: Boolean) {
        if (discoveringId != null) return
        discoveringId = configuration.id
        validationError = ""
        if (openResult) goTo(McpLabStage.DISCOVERY)
        scope.launch {
            val result = runCatching { bridge.discoverServer(configuration.id) }
                .getOrElse { error ->
                    store.updateDiscovery(configuration.id, error = error.message)
                    McpServerDiscoveryResult(
                        configuration = store.find(configuration.id) ?: configuration,
                        catalog = null,
                        error = error.message ?: "Falha ao conectar ao MCP."
                    )
                }
            lastDiscovery = result
            discoveringId = null
            reloadServers()
            RoomAudioForegroundService.reloadConfig(context)
        }
    }

    BackHandler(onBack = ::previous)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remover servidor MCP?") },
            text = {
                Text("A configuração e a credencial salva neste aparelho serão removidas.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft?.let { store.delete(it.id) }
                        confirmDelete = false
                        RoomAudioForegroundService.reloadConfig(context)
                        closeEditor()
                    }
                ) {
                    Text("Remover", color = ConfigTheme.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ConfigTheme.BgTop, ConfigTheme.BgBottom)
                )
            )
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            McpLabTopBar(
                stage = stage,
                serverName = draft?.name,
                onBack = ::previous
            )
            if (stage != McpLabStage.OVERVIEW) {
                WizardStepIndicator(
                    currentStep = stage.step,
                    totalSteps = MCP_LAB_STEP_COUNT
                )
            }
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    wizardStepTransition(
                        forward = targetState.step >= initialState.step
                    )
                },
                label = "mcp-lab-stage",
                modifier = Modifier.weight(1f)
            ) { currentStage ->
                when (currentStage) {
                    McpLabStage.OVERVIEW -> McpLabOverview(
                        servers = servers,
                        discoveringId = discoveringId,
                        onAddTuya = {
                            draft = store.createTuyaDraft()
                            lastDiscovery = null
                            goTo(McpLabStage.CONNECTION)
                        },
                        onAdd = {
                            draft = store.createDraft()
                            lastDiscovery = null
                            goTo(McpLabStage.CONNECTION)
                        },
                        onEdit = {
                            draft = it
                            lastDiscovery = null
                            goTo(McpLabStage.CONNECTION)
                        },
                        onDiscover = { discover(it, false) }
                    )
                    McpLabStage.CONNECTION -> McpConnectionStep(
                        draft = requireNotNull(draft),
                        error = validationError,
                        onDraftChange = { draft = it },
                        onContinue = {
                            val error = validateConnection(requireNotNull(draft))
                            if (error == null) goTo(McpLabStage.AUTHENTICATION)
                            else validationError = error
                        },
                        onDelete = {
                            if (draft?.builtIn == false && store.find(requireNotNull(draft).id) != null) {
                                confirmDelete = true
                            }
                        }
                    )
                    McpLabStage.AUTHENTICATION -> McpAuthenticationStep(
                        draft = requireNotNull(draft),
                        error = validationError,
                        onDraftChange = { draft = it },
                        onContinue = {
                            val saved = saveDraft()
                            if (saved != null) discover(saved, true)
                        }
                    )
                    McpLabStage.DISCOVERY -> McpDiscoveryStep(
                        configuration = requireNotNull(draft),
                        discovery = lastDiscovery,
                        running = discoveringId == draft?.id,
                        onRetry = {
                            saveDraft()?.let { discover(it, true) }
                        },
                        onDone = ::closeEditor
                    )
                }
            }
        }
    }
}

@Composable
private fun McpLabTopBar(
    stage: McpLabStage,
    serverName: String?,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                tint = ConfigTheme.TextPrimary
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (stage == McpLabStage.OVERVIEW) "MCP Lab" else serverName.orEmpty(),
                color = ConfigTheme.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (stage) {
                    McpLabStage.OVERVIEW -> "Conecte, descubra e libere capacidades"
                    McpLabStage.CONNECTION -> "Etapa 1 · Conexão"
                    McpLabStage.AUTHENTICATION -> "Etapa 2 · Autenticação"
                    McpLabStage.DISCOVERY -> "Etapa 3 · Descoberta"
                },
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Surface(
            color = ConfigTheme.Accent.copy(alpha = 0.14f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "LAB",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = ConfigTheme.Accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun McpLabOverview(
    servers: List<McpServerConfiguration>,
    discoveringId: String?,
    onAddTuya: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (McpServerConfiguration) -> Unit,
    onDiscover: (McpServerConfiguration) -> Unit
) {
    val enabled = servers.count { it.enabled }
    val tools = servers.sumOf { it.summary.tools.size }
    val prompts = servers.sumOf { it.summary.prompts.size }
    val resources = servers.sumOf { it.summary.resources.size }
    val xp = enabled * 100 + tools * 40 + prompts * 70 + resources * 25

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = ConfigTheme.Surface,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Rede de capacidades",
                                color = ConfigTheme.TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$enabled MCP ativo${if (enabled == 1) "" else "s"}",
                                color = ConfigTheme.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "$xp XP",
                            color = ConfigTheme.Accent,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        McpMetric("TOOLS", tools, Modifier.weight(1f))
                        McpMetric("PROMPTS", prompts, Modifier.weight(1f))
                        McpMetric("RECURSOS", resources, Modifier.weight(1f))
                    }
                    Button(
                        onClick = onAddTuya,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ConfigTheme.Accent,
                            contentColor = ConfigTheme.BgTop
                        )
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Conectar Tuya / Smart Life", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Adicionar servidor MCP", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        items(servers.size, key = { servers[it].id }) { index ->
            val server = servers[index]
            McpServerCard(
                server = server,
                discovering = discoveringId == server.id,
                discoveryBlocked = discoveringId != null,
                onEdit = { onEdit(server) },
                onDiscover = { onDiscover(server) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun McpMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                ConfigTheme.SurfaceVariant,
                RoundedCornerShape(ConfigTheme.RadiusInner)
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            color = ConfigTheme.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = ConfigTheme.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerConfiguration,
    discovering: Boolean,
    discoveryBlocked: Boolean,
    onEdit: () -> Unit,
    onDiscover: () -> Unit
) {
    val healthy = server.summary.discoveredAtEpochMs > 0L && server.summary.error == null
    val statusColor = when {
        server.summary.error != null -> ConfigTheme.Danger
        healthy -> ConfigTheme.Accent
        else -> ConfigTheme.TextMuted
    }
    Surface(
        color = ConfigTheme.Surface,
        shape = RoundedCornerShape(ConfigTheme.RadiusCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            statusColor.copy(alpha = 0.14f),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Build,
                        contentDescription = null,
                        tint = statusColor
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name,
                            color = ConfigTheme.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (server.builtIn) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Servidor interno",
                                tint = ConfigTheme.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = server.endpoint,
                        color = ConfigTheme.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Editar ${server.name}",
                    tint = ConfigTheme.TextSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                McpCountChip("Tools", server.summary.tools.size, Modifier.weight(1f))
                McpCountChip("Prompts", server.summary.prompts.size, Modifier.weight(1f))
                McpCountChip("Recursos", server.summary.resources.size, Modifier.weight(1f))
            }
            server.summary.error?.let {
                Text(
                    text = it,
                    color = ConfigTheme.Danger,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        !server.enabled -> "Desativado"
                        healthy -> "Conectado · ${formatDiscoveryTime(server.summary.discoveredAtEpochMs)}"
                        else -> "Descoberta pendente"
                    },
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium
                )
                OutlinedButton(
                    onClick = onDiscover,
                    enabled = server.enabled && !discoveryBlocked,
                    modifier = Modifier.height(48.dp)
                ) {
                    if (discovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ConfigTheme.Accent
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (discovering) "Mapeando" else "Descobrir")
                }
            }
        }
    }
}

@Composable
private fun McpCountChip(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(ConfigTheme.SurfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count",
            color = ConfigTheme.Accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = ConfigTheme.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun McpConnectionStep(
    draft: McpServerConfiguration,
    error: String,
    onDraftChange: (McpServerConfiguration) -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            McpStepHero(
                number = "01",
                title = "Onde vive este MCP?",
                subtitle = "Dê uma identidade clara e informe o endpoint Streamable HTTP."
            )
        }
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { value ->
                    val namespace = if (draft.builtIn) {
                        draft.namespace
                    } else {
                        McpServerStore.sanitizeNamespace(value)
                            .ifBlank { draft.namespace }
                    }
                    onDraftChange(draft.copy(name = value, namespace = namespace))
                },
                label = { Text("Nome do servidor") },
                supportingText = { Text("Ex.: Automação da casa, CRM, Arquivos") },
                singleLine = true,
                colors = configTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { onDraftChange(draft.copy(endpoint = it)) },
                label = { Text("Endpoint MCP") },
                supportingText = { Text("URL completa, normalmente terminando em /mcp") },
                singleLine = true,
                colors = configTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ConfigTheme.Surface, RoundedCornerShape(ConfigTheme.RadiusCard))
                    .border(1.dp, ConfigTheme.Border, RoundedCornerShape(ConfigTheme.RadiusCard))
                    .clickable { onDraftChange(draft.copy(enabled = !draft.enabled)) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Disponível para o agente",
                        color = ConfigTheme.TextPrimary,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Quando ativo, as ferramentas descobertas entram no catálogo do chat.",
                        color = ConfigTheme.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = {
                        onDraftChange(draft.copy(enabled = it))
                    }
                )
            }
        }
        if (error.isNotBlank()) {
            item { McpInlineError(error) }
        }
        item {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfigTheme.Accent,
                    contentColor = ConfigTheme.BgTop
                )
            ) {
                Text("Escolher autenticação", fontWeight = FontWeight.Bold)
            }
        }
        if (!draft.builtIn) {
            item {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Remover este servidor", color = ConfigTheme.Danger)
                }
            }
        }
    }
}

@Composable
private fun McpAuthenticationStep(
    draft: McpServerConfiguration,
    error: String,
    onDraftChange: (McpServerConfiguration) -> Unit,
    onContinue: () -> Unit
) {
    var revealToken by rememberSaveable(draft.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            McpStepHero(
                number = "02",
                title = "Como abrir a porta?",
                subtitle = "A credencial fica criptografada no Android Keystore e nunca entra no histórico."
            )
        }
        item {
            McpAuthChoice(
                title = "Sessão Sufficit",
                subtitle = if (draft.namespace == McpServerStore.TUYA_NAMESPACE) {
                    "Usa seu login Sufficit e a conta Tuya vinculada pelo QR oficial."
                } else {
                    "Reutiliza automaticamente o login e o usuário atual."
                },
                selected = draft.authenticationMode == McpAuthenticationMode.SUFFICIT,
                onClick = {
                    onDraftChange(
                        draft.copy(authenticationMode = McpAuthenticationMode.SUFFICIT)
                    )
                }
            )
        }
        item {
            McpAuthChoice(
                title = "Bearer token",
                subtitle = "Para MCPs externos com token estático.",
                selected = draft.authenticationMode == McpAuthenticationMode.BEARER,
                onClick = {
                    onDraftChange(
                        draft.copy(authenticationMode = McpAuthenticationMode.BEARER)
                    )
                }
            )
        }
        item {
            McpAuthChoice(
                title = "Sem autenticação",
                subtitle = "Use apenas em servidores confiáveis da rede local.",
                selected = draft.authenticationMode == McpAuthenticationMode.NONE,
                onClick = {
                    onDraftChange(
                        draft.copy(authenticationMode = McpAuthenticationMode.NONE)
                    )
                }
            )
        }
        if (draft.authenticationMode == McpAuthenticationMode.BEARER) {
            item {
                val isTuya = draft.namespace == McpServerStore.TUYA_NAMESPACE
                OutlinedTextField(
                    value = draft.bearerToken,
                    onValueChange = { onDraftChange(draft.copy(bearerToken = it)) },
                    label = {
                        Text(if (isTuya) "API Key Tuya (sk-…)" else "Bearer token")
                    },
                    supportingText = {
                        Text(
                            if (isTuya) {
                                "Gere em tuya.ai. A chave fica somente no cofre local do aparelho."
                            } else {
                                "Armazenado somente no cofre local do aparelho"
                            }
                        )
                    },
                    visualTransformation = if (revealToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { revealToken = !revealToken }) {
                            Text(if (revealToken) "Ocultar" else "Mostrar")
                        }
                    },
                    singleLine = true,
                    colors = configTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (
            draft.namespace == McpServerStore.TUYA_NAMESPACE &&
            draft.authenticationMode == McpAuthenticationMode.SUFFICIT
        ) {
            item {
                Text(
                    "A autorização Tuya fica vinculada ao seu ID Sufficit no servidor. " +
                        "Nenhuma chave Tuya precisa ser copiada para este aparelho.",
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (error.isNotBlank()) {
            item { McpInlineError(error) }
        }
        item {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfigTheme.Accent,
                    contentColor = ConfigTheme.BgTop
                )
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Salvar e descobrir capacidades", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun McpAuthChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) {
                    ConfigTheme.Accent.copy(alpha = 0.12f)
                } else {
                    ConfigTheme.Surface
                },
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ConfigTheme.Accent else ConfigTheme.Border,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(
                    2.dp,
                    if (selected) ConfigTheme.Accent else ConfigTheme.TextMuted,
                    CircleShape
                )
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ConfigTheme.Accent, CircleShape)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = ConfigTheme.TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun McpDiscoveryStep(
    configuration: McpServerConfiguration,
    discovery: McpServerDiscoveryResult?,
    running: Boolean,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    val catalog = discovery?.catalog
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            McpStepHero(
                number = "03",
                title = if (running) "Mapeando o servidor…" else "Mapa de capacidades",
                subtitle = if (running) {
                    "Negociando sessão e consultando tools, prompts e resources."
                } else {
                    "Tudo que o MCP publicou, sem catálogo fixo no aplicativo."
                }
            )
        }
        if (running) {
            item {
                Surface(
                    color = ConfigTheme.Surface,
                    shape = RoundedCornerShape(ConfigTheme.RadiusCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = ConfigTheme.Accent)
                        Text(
                            text = "Inicializando ${configuration.name}",
                            color = ConfigTheme.TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "A primeira conexão pode levar alguns segundos.",
                            color = ConfigTheme.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else if (catalog != null) {
            item {
                Surface(
                    color = ConfigTheme.Accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(ConfigTheme.RadiusCard),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        ConfigTheme.Accent.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = ConfigTheme.Accent,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = "Handshake concluído",
                                color = ConfigTheme.TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${catalog.tools.size} tools · " +
                                    "${catalog.prompts.size} prompts · " +
                                    "${catalog.resources.size} recursos",
                                color = ConfigTheme.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            item {
                McpCapabilityGroup(
                    title = "Ferramentas",
                    items = catalog.tools.map { it.name },
                    emptyText = "Este MCP não publicou tools."
                )
            }
            item {
                McpCapabilityGroup(
                    title = "Prompts",
                    items = catalog.prompts.map { it.name },
                    emptyText = "Este MCP não publicou prompts."
                )
            }
            item {
                McpCapabilityGroup(
                    title = "Recursos",
                    items = catalog.resources.map { it.name.ifBlank { it.uri } },
                    emptyText = "Este MCP não publicou resources."
                )
            }
            item {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ConfigTheme.Accent,
                        contentColor = ConfigTheme.BgTop
                    )
                ) {
                    Text("Concluir missão", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            item {
                McpInlineError(
                    discovery?.error ?: configuration.summary.error
                    ?: "Não foi possível descobrir as capacidades."
                )
            }
            item {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tentar novamente")
                }
            }
        }
    }
}

@Composable
private fun McpStepHero(number: String, title: String, subtitle: String) {
    Surface(
        color = ConfigTheme.Surface,
        shape = RoundedCornerShape(ConfigTheme.RadiusCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        ConfigTheme.Accent.copy(alpha = 0.14f),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = ConfigTheme.Accent,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun McpCapabilityGroup(
    title: String,
    items: List<String>,
    emptyText: String
) {
    Surface(
        color = ConfigTheme.Surface,
        shape = RoundedCornerShape(ConfigTheme.RadiusCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = items.size.toString(),
                    color = ConfigTheme.Accent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider(color = ConfigTheme.Border)
            if (items.isEmpty()) {
                Text(
                    text = emptyText,
                    color = ConfigTheme.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                items.take(12).forEach { item ->
                    Text(
                        text = "• $item",
                        color = ConfigTheme.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (items.size > 12) {
                    Text(
                        text = "+ ${items.size - 12} itens",
                        color = ConfigTheme.Accent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun McpInlineError(message: String) {
    Surface(
        color = ConfigTheme.Danger.copy(alpha = 0.10f),
        shape = RoundedCornerShape(ConfigTheme.RadiusInner),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            ConfigTheme.Danger.copy(alpha = 0.45f)
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            color = ConfigTheme.Danger,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDiscoveryTime(epochMs: Long): String {
    if (epochMs <= 0L) return "agora"
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
}
