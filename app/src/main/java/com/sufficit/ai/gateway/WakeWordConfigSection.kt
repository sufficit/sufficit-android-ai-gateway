package com.sufficit.ai.gateway

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.runtime.GatewayRuntime

/** Resumo global; treinamento e ajustes por chamada ficam no Wake Lab. */
@Composable
fun WakeWordConfigSection(onOpenWizard: () -> Unit) {
    val context = LocalContext.current
    val store = remember { WakeWordStore(context.applicationContext) }
    val version by GatewayRuntime.wakeWordConfigVersion().collectAsState()
    val wake by GatewayRuntime.wakeWord().collectAsState()
    val config = remember(version) { store.loadConfig() }
    val summaries = remember(version, wake.sampleCount, wake.profileCount) { store.profileSummaries() }
    val names = summaries.joinToString(limit = 3, truncated = "…") { it.profile.phraseLabel }

    ConfigSection(title = "Wake words") {
        Button(onClick = onOpenWizard, modifier = Modifier.fillMaxWidth()) {
            Text(if (summaries.isEmpty()) "Configurar no Wake Lab" else "Gerenciar no Wake Lab")
        }
        Text(
            text = if (summaries.isEmpty()) {
                "Escolha uma ou várias chamadas. Cada uma exige três gravações válidas e fica somente neste aparelho."
            } else {
                "Chamadas cadastradas: $names. Qualquer perfil pronto pode acordar o telefone pelo monitor local."
            },
            style = MaterialTheme.typography.bodySmall
        )
        SettingToggleRow(
            title = "Ativar wake words",
            supportingText = "Mantém o nível 1 comparando o áudio com todas as chamadas prontas.",
            checked = config.enabled,
            onCheckedChange = { enabled ->
                store.saveConfig(config.copy(enabled = enabled))
                GatewayRuntime.bumpWakeWordConfigVersion()
            }
        )
        MetadataChip("Status", wake.status)
        MetadataChip("Chamadas prontas", "${wake.readyProfileCount}/${summaries.size}")
        MetadataChip("Chaves de voz", summaries.sumOf { it.sampleCount }.toString())
        wake.lastMatchedPhraseLabel?.let { MetadataChip("Última chamada", it) }
    }
}
