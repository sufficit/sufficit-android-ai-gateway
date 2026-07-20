package com.sufficit.ai.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.config.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Passo 2 do assistente: escolher SOMENTE o modo de transcricao (cartoes
 * selecionaveis, sem campos). A configuracao especifica de cada modo fica no
 * passo 3 (WizardTranscriptionConfigStep) — separar decisao de detalhe deixa
 * cada tela do wizard com uma unica pergunta, mais facil de guiar.
 */
@Composable
fun WizardTranscriptionModeStep(
    state: ConfigPageState,
    actions: ConfigPageActions,
    onBackStep: () -> Unit,
    onNext: () -> Unit
) {
    ConfigSection(title = "2. Escolher a transcricao de voz") {
        Text(
            text = "Como o audio vira texto. Da pra trocar depois em Configuracoes > Transcricao.",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TranscriptionMode.entries.forEach { option ->
                TranscriptionModeCard(
                    mode = option,
                    selected = TranscriptionMode.fromPersistedValue(state.transcriptionMode) == option,
                    onSelect = { actions.onTranscriptionModeChange(option.persistedValue) }
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onBackStep) { Text("Voltar") }
        Button(onClick = onNext) { Text("Proximo: configurar") }
    }
}

@Composable
private fun TranscriptionModeCard(
    mode: TranscriptionMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                color = if (selected) ConfigTheme.SurfaceVariant else ConfigTheme.Surface,
                shape = RoundedCornerShape(ConfigTheme.RadiusInner)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ConfigTheme.Accent else ConfigTheme.Border,
                shape = RoundedCornerShape(ConfigTheme.RadiusInner)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = transcriptionModeLabel(mode),
                color = ConfigTheme.TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = ConfigTheme.Accent,
                    unselectedColor = ConfigTheme.TextSecondary
                )
            )
        }
        Text(
            text = transcriptionModeDescription(mode),
            color = ConfigTheme.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Passo 3 do assistente: campos e teste do modo escolhido no passo 2.
 * Mesmo conteudo de [ConfigTranscriptionSectionPage]'s "Pipeline" — o
 * assistente so guia a ordem, nao duplica logica de estado.
 */
@Composable
fun WizardTranscriptionConfigStep(
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

    val mode = TranscriptionMode.fromPersistedValue(state.transcriptionMode)

    ConfigSection(title = "3. Configurar — ${transcriptionModeLabel(mode)}") {
        when (mode) {
            TranscriptionMode.COMPANION -> CompanionTranscriptionStatusSection()
            TranscriptionMode.LOCAL -> LocalTranscriptionSection(
                state = state,
                actions = actions,
                localModelDropdownExpanded = localModelDropdownExpanded,
                onLocalModelDropdownExpandedChange = { localModelDropdownExpanded = it }
            )
            TranscriptionMode.REMOTE -> {
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
            }
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
