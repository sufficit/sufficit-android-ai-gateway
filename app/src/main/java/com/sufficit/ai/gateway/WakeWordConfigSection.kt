package com.sufficit.ai.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import java.util.Locale

/**
 * Configuracao da palavra de ativacao (ex.: "chuchu"): amostras pre-gravadas
 * comparadas por espectro (MFCC+DTW), sem rede neural. A gravacao usa o
 * microfone do servico, entao a escuta precisa estar ativa.
 */
@Composable
fun WakeWordConfigSection() {
    val context = LocalContext.current
    val store = remember { WakeWordStore(context.applicationContext) }
    val version by GatewayRuntime.wakeWordConfigVersion().collectAsState()
    val wake by GatewayRuntime.wakeWord().collectAsState()
    val runtime by GatewayRuntime.state().collectAsState()

    val config = remember(version) { store.loadConfig() }
    val sampleCount = remember(version, wake.sampleCount) { store.sampleCount() }
    var thresholdDraft by remember(version) { mutableStateOf(config.threshold.toFloat()) }

    ConfigSection(title = "Palavra de ativacao") {
        Text(
            text = "Acorda o telefone ao ouvir a palavra gravada (ex.: \"chuchu\"). " +
                "Comparacao por espectro com amostras suas, sem IA. " +
                "Grave 3+ amostras em ambiente silencioso.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingToggleRow(
            title = "Ativar palavra de ativacao",
            supportingText = "Escuta continua leve comparando com as amostras gravadas.",
            checked = config.enabled,
            onCheckedChange = { enabled ->
                store.saveConfig(config.copy(enabled = enabled))
                GatewayRuntime.bumpWakeWordConfigVersion()
            }
        )
        MetadataChip("Status", wake.status)
        MetadataChip("Amostras", sampleCount.toString())
        MetadataChip(
            "Ultima distancia",
            wake.lastDistance?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
        )
        SettingToggleRow(
            title = "Limiar automatico",
            supportingText = "Calcula o limiar pela variacao entre as proprias amostras (recomendado).",
            checked = config.autoThreshold,
            onCheckedChange = { auto ->
                store.saveConfig(store.loadConfig().copy(autoThreshold = auto))
                GatewayRuntime.bumpWakeWordConfigVersion()
            }
        )
        Text(
            text = "Limiar de deteccao: ${String.format(Locale.US, "%.2f", thresholdDraft)} " +
                "(menor = mais rigido; ajuste observando a distancia ao falar a palavra)",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = thresholdDraft,
            onValueChange = { thresholdDraft = it },
            onValueChangeFinished = {
                store.saveConfig(
                    store.loadConfig().copy(
                        threshold = thresholdDraft.toDouble(),
                        autoThreshold = false
                    )
                )
                GatewayRuntime.bumpWakeWordConfigVersion()
            },
            enabled = !config.autoThreshold,
            valueRange = 0.05f..0.5f
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { GatewayRuntime.requestWakeWordRecording() },
                enabled = runtime.listening && !wake.recording,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (wake.recording) "Gravando..." else "Gravar amostra")
            }
            OutlinedButton(
                onClick = {
                    store.clearSamples()
                    GatewayRuntime.bumpWakeWordConfigVersion()
                },
                enabled = sampleCount > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("Limpar amostras")
            }
        }
        if (!runtime.listening) {
            Text(
                text = "Inicie a escuta no dashboard para gravar amostras.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
