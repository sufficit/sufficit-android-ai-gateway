package com.sufficit.ai.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.speaker.SpeakerVoiceStore
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Configuracao da verificacao de voz do usuario ("so a minha voz").
 *
 * Fluxo de uso:
 *  1. Baixar o modelo de embeddings de locutor (CAM++ ~28MB, uma vez);
 *  2. "Aprender minha voz": as proximas N falas viram amostras do perfil
 *     (e nao sao enviadas para transcricao);
 *  3. Ativar o filtro: falas com similaridade abaixo do limiar sao
 *     ignoradas — vozes de outras pessoas nao chegam ao assistente.
 */
@Composable
fun SpeakerVoiceConfigSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeakerVoiceStore(context.applicationContext) }
    val state by GatewayRuntime.speakerVoice().collectAsState()
    val runtime by GatewayRuntime.state().collectAsState()

    var configRevision by remember { mutableStateOf(0) }
    val config = remember(configRevision) { store.loadConfig() }
    var thresholdDraft by remember(configRevision) { mutableStateOf(config.threshold.toFloat()) }

    // Sincroniza o estado base (modelo/amostras) ao abrir a tela.
    LaunchedEffect(configRevision) {
        GatewayRuntime.updateSpeakerVoice {
            it.copy(
                enabled = config.enabled,
                threshold = config.threshold,
                modelReady = store.isModelReady(),
                sampleCount = store.embeddingCount()
            )
        }
    }

    ConfigSection(
        title = "Minha voz",
        subtitle = "So aceita falas com a sua voz; vozes de outras pessoas sao ignoradas"
    ) {
        Text(
            text = "Compara cada fala com o seu perfil de voz (impressao vocal por " +
                "rede neural local, independente do conteudo falado).",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        MetadataChip("Status", state.status)
        // Feedback ao vivo durante o cadastro: o usuario precisa ver que o
        // microfone esta captando ENQUANTO fala — a amostra so registra
        // quando a frase termina (pausa de ~1s).
        if (state.enrollRemaining > 0) {
            MetadataChip(
                "Captura",
                when {
                    !runtime.listening -> "Microfone parado — inicie a escuta"
                    runtime.speechDetected -> "FALANDO — continue ate o fim da frase"
                    else -> "Aguardando voce falar (pause ao terminar a frase)"
                }
            )
        }
        MetadataChip("Modelo", if (state.modelReady) "Pronto" else "Nao baixado (~28MB)")
        MetadataChip("Amostras de voz", state.sampleCount.toString())
        MetadataChip(
            "Ultimo score",
            state.lastScore?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
        )

        if (!state.modelReady) {
            state.downloadProgressPercent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        downloadSpeakerModel(store)
                        configRevision += 1
                    }
                },
                enabled = state.downloadProgressPercent == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.downloadProgressPercent != null) {
                        "Baixando... ${state.downloadProgressPercent}%"
                    } else {
                        "Baixar modelo de voz"
                    }
                )
            }
        }

        SettingToggleRow(
            title = "Filtrar pela minha voz",
            supportingText = "Falas que nao casarem com o perfil sao descartadas antes da transcricao.",
            checked = config.enabled,
            onCheckedChange = { enabled ->
                store.saveConfig(store.loadConfig().copy(enabled = enabled))
                configRevision += 1
            }
        )

        Text(
            text = "Limiar de similaridade: ${String.format(Locale.US, "%.2f", thresholdDraft)} " +
                "(maior = mais rigido; sua voz costuma pontuar 0.60+, outras vozes abaixo de 0.40)",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        Slider(
            value = thresholdDraft,
            onValueChange = { thresholdDraft = it },
            onValueChangeFinished = {
                store.saveConfig(store.loadConfig().copy(threshold = thresholdDraft.toDouble()))
                configRevision += 1
            },
            valueRange = 0.30f..0.80f
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    // Precisa do microfone ativo para captar as falas do cadastro:
                    // inicia a escuta se preciso (permissao de audio ja concedida).
                    if (!runtime.listening) {
                        com.sufficit.ai.gateway.audio.RoomAudioForegroundService.start(context)
                    }
                    GatewayRuntime.requestSpeakerEnrollment(ENROLL_SAMPLES)
                },
                enabled = state.modelReady && state.enrollRemaining == 0,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (state.enrollRemaining > 0) {
                        "Fale... (${state.enrollRemaining})"
                    } else {
                        "Aprender minha voz"
                    }
                )
            }
            OutlinedButton(
                onClick = {
                    GatewayRuntime.cancelSpeakerEnrollment()
                    store.clearEmbeddings()
                    GatewayRuntime.updateSpeakerVoice {
                        it.copy(sampleCount = 0, status = "Perfil de voz apagado.")
                    }
                    configRevision += 1
                },
                enabled = state.sampleCount > 0 || state.enrollRemaining > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("Limpar perfil")
            }
        }
        if (!runtime.listening) {
            Text(
                text = "Inicie a escuta no dashboard para cadastrar sua voz.",
                style = MaterialTheme.typography.bodySmall,
                color = ConfigTheme.TextSecondary
            )
        }
    }
}

/**
 * Baixa o modelo ONNX de embeddings para filesDir/models/speaker/, com
 * progresso publicado no estado da UI. Download atomico: escreve em .part
 * e renomeia no final, para nunca deixar um modelo truncado no caminho.
 */
private suspend fun downloadSpeakerModel(store: SpeakerVoiceStore) {
    GatewayRuntime.updateSpeakerVoice {
        it.copy(downloadProgressPercent = 0, status = "Baixando modelo de voz...")
    }
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val target = store.modelFile()
            target.parentFile?.mkdirs()
            val partFile = File(target.parentFile, "${target.name}.part")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(SpeakerVoiceStore.MODEL_DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("Resposta sem corpo")
                val total = body.contentLength().takeIf { it > 0 }
                    ?: SpeakerVoiceStore.MODEL_SIZE_BYTES
                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                GatewayRuntime.updateSpeakerVoice {
                                    it.copy(downloadProgressPercent = percent)
                                }
                            }
                        }
                    }
                }
            }
            check(partFile.renameTo(target)) { "Falha ao mover arquivo baixado" }
        }
    }
    GatewayRuntime.updateSpeakerVoice {
        if (result.isSuccess) {
            it.copy(
                downloadProgressPercent = null,
                modelReady = true,
                status = "Modelo pronto. Cadastre sua voz."
            )
        } else {
            it.copy(
                downloadProgressPercent = null,
                status = "Falha no download: ${result.exceptionOrNull()?.message ?: "erro"}"
            )
        }
    }
}

private const val ENROLL_SAMPLES = 3
