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
 * passo 1 liga o aparelho ao sistema de IA (OpenClaw), passo 2 escolhe o
 * modo de transcricao de voz, passo 3 configura e testa o modo escolhido.
 * Reaproveita os mesmos campos/acoes das telas "OpenClaw" e "Transcricao" —
 * o assistente so guia a ordem e explica cada campo, nao duplica a logica
 * de estado. Passos 2 e 3 ficam em WizardTranscriptionSteps.kt.
 */
private const val WIZARD_STEP_COUNT = 3

@Composable
fun SetupWizardPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBack: () -> Unit
) {
    var step by rememberSaveable { mutableStateOf(1) }

    ConfigSectionScaffold(
        title = "Assistente de configuracao",
        subtitle = when (step) {
            1 -> "Passo 1 de 3 — Acesso ao sistema de IA"
            2 -> "Passo 2 de 3 — Escolher transcricao"
            else -> "Passo 3 de 3 — Configurar transcricao"
        },
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
                    when (targetStep) {
                        1 -> WizardAiAccessStep(
                            state = state,
                            actions = actions,
                            onNext = { step = 2 }
                        )
                        2 -> WizardTranscriptionModeStep(
                            state = state,
                            actions = actions,
                            onBackStep = { step = 1 },
                            onNext = { step = 3 }
                        )
                        else -> WizardTranscriptionConfigStep(
                            state = state,
                            actions = actions,
                            onBackStep = { step = 2 },
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

    // Login com Sufficit primeiro — o resto do pareamento (OpenClaw) e mais
    // facil de diagnosticar/associar depois de saber quem esta configurando.
    IdentityConfigSection()
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Button(onClick = onNext) { Text("Proximo: transcricao") }
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
