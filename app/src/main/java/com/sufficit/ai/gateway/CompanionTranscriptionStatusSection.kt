package com.sufficit.ai.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.transcription.CompanionTranscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COMPANION_APP_PACKAGE = "com.sufficit.ai.mobiledevice"

/**
 * Status/config block for [com.sufficit.ai.gateway.config.TranscriptionMode.COMPANION]: no
 * free-text fields to fill (unlike REMOTE/LOCAL) — presence and readiness of
 * sufficit-mobile-ai-models on the same device drive everything, so this just surfaces that
 * state and offers to open the companion app if it needs attention.
 */
@Composable
fun CompanionTranscriptionStatusSection() {
    val context = LocalContext.current
    val client = remember(context) { CompanionTranscriptionClient(context.applicationContext) }
    var checking by remember { mutableStateOf(true) }
    var installed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(client) {
        onDispose { client.release() }
    }

    LaunchedEffect(client) {
        checking = true
        installed = client.isCompanionAppInstalled()
        ready = if (installed) withContext(Dispatchers.IO) { client.isReady() } else false
        checking = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Transcreve pelo modelo Whisper ja rodando no app sufficit-mobile-ai-models, " +
                "se instalado no mesmo aparelho — sem endereco ou token pra configurar aqui.",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        MetadataChip(
            "App no aparelho",
            when {
                checking -> "Verificando..."
                installed -> "Instalado"
                else -> "Nao instalado"
            }
        )
        if (installed) {
            MetadataChip(
                "Modelo de transcricao",
                when {
                    checking -> "Verificando..."
                    ready -> "Pronto"
                    else -> "Nao ativo"
                }
            )
        }
        if (installed && !checking && !ready) {
            Button(onClick = {
                context.packageManager.getLaunchIntentForPackage(COMPANION_APP_PACKAGE)?.let {
                    context.startActivity(it)
                }
            }) {
                Text("Abrir app e ativar um modelo Whisper")
            }
        }
        if (!installed && !checking) {
            Text(
                text = "Instale o app sufficit-mobile-ai-models no mesmo aparelho pra usar este modo.",
                style = MaterialTheme.typography.bodySmall,
                color = ConfigTheme.Danger
            )
        }
    }
}
