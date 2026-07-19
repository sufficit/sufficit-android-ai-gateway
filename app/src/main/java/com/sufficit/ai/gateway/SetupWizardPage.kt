package com.sufficit.ai.gateway

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.InstallationId
import com.sufficit.ai.gateway.config.TranscriptionMode
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayClient
import com.sufficit.ai.gateway.openclaw.OpenClawGatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Assistente guiado para quem esta configurando o app pela primeira vez:
 * passo 1 liga o aparelho ao sistema de IA (OpenClaw), passo 2 escolhe e
 * testa a transcricao de voz (Whisper local ou remoto). Reaproveita os
 * mesmos campos/acoes das telas "OpenClaw" e "Transcricao" — o assistente
 * so guia a ordem e explica cada campo, nao duplica a logica de estado.
 */
private const val WIZARD_STEP_COUNT = 2

@Composable
fun SetupWizardPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    var step by rememberSaveable { mutableStateOf(1) }

    ConfigSectionScaffold(
        title = "Assistente de configuracao",
        subtitle = if (step == 1) "Passo 1 de 2 — Acesso ao sistema de IA" else "Passo 2 de 2 — Funcao Whisper",
        onBack = onBack
    ) {
        item {
            WizardStepIndicator(currentStep = step, totalSteps = WIZARD_STEP_COUNT)
        }
        item {
            AnimatedContent(
                targetState = step,
                transitionSpec = { wizardStepTransition(forward = targetState > initialState) },
                label = "wizard-step"
            ) { targetStep ->
                // AnimatedContent nao empilha filhos verticalmente sozinho (ao
                // contrario do item{} do LazyColumn) — sem este Column, os
                // ConfigSection/Row de cada passo ficam sobrepostos.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (targetStep == 1) {
                        WizardAiAccessStep(
                            state = state,
                            actions = actions,
                            onNext = { step = 2 }
                        )
                    } else {
                        WizardWhisperStep(
                            state = state,
                            actions = actions,
                            onBackStep = { step = 1 },
                            onFinish = onBack
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardAiAccessStep(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var pairingResultText by rememberSaveable { mutableStateOf<String?>(null) }
    var pairingOk by rememberSaveable { mutableStateOf<Boolean?>(null) }

    var testing by rememberSaveable { mutableStateOf(false) }
    var resultText by rememberSaveable { mutableStateOf<String?>(null) }
    var resultOk by rememberSaveable { mutableStateOf<Boolean?>(null) }

    ConfigSection(title = "1. Ligar ao sistema de IA") {
        Text(
            text = "Peca pro admin gerar um codigo no servidor " +
                "(generate_android_pairing_code.py) e cole aqui. O aparelho troca o codigo " +
                "pelos tokens sozinho — nao precisa copiar token comprido.",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        OutlinedTextField(
            value = state.openClawServerAddress,
            onValueChange = actions.onOpenClawServerAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Endereco do servidor") },
            supportingText = { Text("Ex.: your-openclaw-host.example.com") },
            singleLine = true,
            colors = configTextFieldColors()
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.trim().uppercase().take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Codigo de pareamento") },
            supportingText = { Text("6 caracteres, expira em minutos") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters
            ),
            colors = configTextFieldColors()
        )
        Button(
            onClick = {
                pairing = true
                pairingResultText = null
                pairingOk = null
                scope.launch {
                    val outcome = exchangePairingCode(state.openClawServerAddress, pairingCode)
                    pairing = false
                    pairingOk = outcome.isSuccess
                    pairingResultText = outcome.fold(
                        onSuccess = { result ->
                            actions.onOpenClawDeviceTokenChange(result.deviceToken)
                            actions.onOpenClawSessionKeyChange(result.sessionKey)
                            "Pareado! Tokens preenchidos automaticamente."
                        },
                        onFailure = { "Falhou: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            },
            enabled = !pairing && state.openClawServerAddress.isNotBlank() && pairingCode.length == 6,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (pairing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Parear automaticamente")
            }
        }
        pairingResultText?.let {
            Text(
                text = it,
                color = if (pairingOk == true) ConfigTheme.Accent else ConfigTheme.Danger,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    ConfigSection(title = "") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ConfigTheme.TextSecondary
            )
            Text(
                "Avancado: colar tokens manualmente",
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        AnimatedVisibility(visible = advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.openClawGatewayToken,
                    onValueChange = actions.onOpenClawGatewayTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token do gateway") },
                    supportingText = { Text("Legado — nao usado na autenticacao atual, pode deixar em branco.") },
                    singleLine = true,
                    colors = configTextFieldColors()
                )
                OutlinedTextField(
                    value = state.openClawDeviceToken,
                    onValueChange = actions.onOpenClawDeviceTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token do device") },
                    singleLine = true,
                    colors = configTextFieldColors()
                )
                Button(
                    onClick = {
                        testing = true
                        resultText = null
                        resultOk = null
                        scope.launch {
                            val outcome = testOpenClawConnection(context, state)
                            testing = false
                            resultOk = outcome.isSuccess
                            resultText = outcome.fold(
                                onSuccess = { "Conectado: $it" },
                                onFailure = { "Falhou: ${it.message ?: it.javaClass.simpleName}" }
                            )
                        }
                    },
                    enabled = !testing && state.openClawServerAddress.isNotBlank() &&
                        state.openClawDeviceToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Testar conexao")
                    }
                }
                resultText?.let {
                    Text(
                        text = it,
                        color = if (resultOk == true) ConfigTheme.Accent else ConfigTheme.Danger,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    IdentityConfigSection()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Button(onClick = onNext) { Text("Proximo: Whisper") }
    }
}

@Composable
private fun WizardWhisperStep(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBackStep: () -> Unit,
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var localModelDropdownExpanded by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var resultText by rememberSaveable { mutableStateOf<String?>(null) }
    var resultOk by rememberSaveable { mutableStateOf<Boolean?>(null) }

    ConfigSection(title = "2. Escolher a transcricao de voz") {
        Text(
            text = "Local: roda no proprio aparelho, sem depender de internet nem token — mais " +
                "privado, precisa baixar um modelo uma vez. Remoto: usa um servidor Whisper, " +
                "exige endereco e token.",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
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
            "Modo escolhido",
            if (TranscriptionMode.fromPersistedValue(state.transcriptionMode) == TranscriptionMode.REMOTE) "Remoto" else "Local"
        )
    }
    ConfigSection(title = "Configuracao") {
        if (TranscriptionMode.fromPersistedValue(state.transcriptionMode) == TranscriptionMode.REMOTE) {
            OutlinedTextField(
                value = state.whisperUrl,
                onValueChange = actions.onWhisperUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Endpoint remoto") },
                supportingText = { Text("Ex.: https://your-whisper-host.example.com/v1/audio/transcriptions") },
                colors = configTextFieldColors()
            )
            OutlinedTextField(
                value = state.remoteModel,
                onValueChange = actions.onRemoteModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Modelo remoto") },
                colors = configTextFieldColors()
            )
            OutlinedTextField(
                value = state.whisperAuthToken,
                onValueChange = actions.onWhisperAuthTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer token") },
                colors = configTextFieldColors()
            )
            Button(
                onClick = {
                    testing = true
                    resultText = null
                    resultOk = null
                    val url = state.whisperUrl
                    val token = state.whisperAuthToken
                    scope.launch {
                        val outcome = testWhisperEndpoint(url, token)
                        testing = false
                        resultOk = outcome.isSuccess
                        resultText = outcome.fold(
                            onSuccess = { it },
                            onFailure = { "Falhou: ${it.message ?: it.javaClass.simpleName}" }
                        )
                    }
                },
                enabled = !testing && state.whisperUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Testar endpoint")
                }
            }
            resultText?.let {
                Text(
                    text = it,
                    color = if (resultOk == true) ConfigTheme.Accent else ConfigTheme.Danger,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LocalTranscriptionSection(
                state = state,
                actions = actions,
                localModelDropdownExpanded = localModelDropdownExpanded,
                onLocalModelDropdownExpandedChange = { localModelDropdownExpanded = it }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onBackStep) { Text("Voltar") }
        Button(onClick = onFinish) { Text("Concluir") }
    }
}

private data class PairingExchangeResult(val deviceToken: String, val sessionKey: String)

private fun derivePairingUrl(serverAddress: String): String {
    val host = serverAddress.trim()
        .removePrefix("wss://").removePrefix("ws://")
        .removePrefix("https://").removePrefix("http://")
        .substringBefore("/")
        .ifBlank { GatewaySettingsStore.DEFAULT_OPENCLAW_SERVER_ADDRESS }
    return "https://$host/pair"
}

// Troca o codigo curto gerado pelo admin (generate_android_pairing_code.py,
// no servidor) pelos tokens reais via POST /pair — mesmo deviceId que sera
// usado no "hello" do websocket (OpenClawGatewayClient.currentDeviceId()),
// senao o token pareado nao bate na hora de conectar de verdade.
private suspend fun exchangePairingCode(
    serverAddress: String,
    code: String
): Result<PairingExchangeResult> = withContext(Dispatchers.IO) {
    runCatching {
        val deviceId = OpenClawGatewayClient().currentDeviceId()
        val body = JSONObject().put("code", code.trim().uppercase()).put("deviceId", deviceId)
        val connection = (URL(derivePairingUrl(serverAddress)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (statusCode !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                ?.ifBlank { null }
            throw IllegalStateException(detail ?: "HTTP $statusCode")
        }
        val json = JSONObject(text)
        PairingExchangeResult(
            deviceToken = json.optString("deviceToken"),
            sessionKey = json.optString("sessionKey")
        )
    }
}

private suspend fun testOpenClawConnection(
    context: android.content.Context,
    state: ConfigPageState
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val userId = GatewaySettingsStore(context.applicationContext).load().openClawUserId
        val config = OpenClawGatewayConfig(
            gatewayUrl = GatewaySettingsStore.buildGatewayUrl(state.openClawServerAddress),
            gatewayToken = state.openClawGatewayToken,
            deviceToken = state.openClawDeviceToken,
            sessionKey = state.openClawSessionKey.ifBlank { "wizard:test:${System.currentTimeMillis()}" },
            userId = userId,
            installationId = InstallationId.get(context.applicationContext)
        )
        OpenClawGatewayClient().verifyConnection(config, timeoutMs = 8_000L)
    }
}

// Sem endpoint de health dedicado no servidor Whisper: qualquer resposta
// HTTP (mesmo erro) ja prova que o endereco existe e esta alcancavel: o
// que falha silenciosamente de verdade e nome errado/rede inacessivel.
private suspend fun testWhisperEndpoint(url: String, token: String): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
            when {
                code in 200..299 -> "Servidor respondeu OK ($code)."
                code == 401 || code == 403 -> "Servidor alcancado, mas token invalido/ausente (HTTP $code)."
                else -> "Servidor alcancado (HTTP $code) — normal se a rota so aceitar POST."
            }
        }
    }
