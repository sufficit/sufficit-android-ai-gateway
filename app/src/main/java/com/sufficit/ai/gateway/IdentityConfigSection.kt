package com.sufficit.ai.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.InstallationId

/**
 * Identidade da PESSOA. O userId (sufficit-ai) vincula esta instalacao ao
 * perfil/preferencias da pessoa no servidor — o agente passa a saber quem
 * voce e, independente do aparelho. Uma pessoa pode ter varias instalacoes,
 * todas com o mesmo userId.
 *
 * O installationId identifica este aparelho de forma estavel (nao depende do
 * ANDROID_ID, que muda com reinstalacao/repackage). E somente leitura.
 *
 * No futuro o userId vem de login; por ora e informado aqui.
 */
@Composable
fun IdentityConfigSection() {
    val context = LocalContext.current
    val store = remember { GatewaySettingsStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.load()) }
    var userIdDraft by remember { mutableStateOf(settings.openClawUserId) }
    val installationId = remember { InstallationId.get(context.applicationContext) }

    ConfigSection(title = "Identidade") {
        Text(
            text = "Seu userId (sufficit-ai) liga este aparelho ao seu perfil e preferencias " +
                "no assistente. A mesma pessoa pode ter varios aparelhos com o mesmo userId.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = userIdDraft,
            onValueChange = { userIdDraft = it.trim() },
            label = { Text("userId") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val updated = settings.copy(openClawUserId = userIdDraft.trim())
                store.save(updated)
                settings = updated
                RoomAudioForegroundService.reloadConfig(context.applicationContext)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Salvar e reconectar") }
        MetadataChip("userId atual", settings.openClawUserId.ifBlank { "(nao vinculado)" })
        MetadataChip("installationId", installationId)
        OutlinedButton(
            onClick = { copyToClipboard(context, "installationId", installationId) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Copiar installationId") }
        if (settings.openClawUserId.isBlank()) {
            Text(
                text = "Sem userId, o servidor identifica pelo vinculo do aparelho (fragil). " +
                    "Informe o userId para o reconhecimento estavel da pessoa.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
