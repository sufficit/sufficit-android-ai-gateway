package com.sufficit.ai.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Configuracao da API HTTP de controle. Liga/desliga o servidor embarcado,
 * define porta, bind (LAN x localhost) e o token de acesso. Mudancas
 * persistem no config.json e recarregam o servidor no servico.
 *
 * O token e obrigatorio: ligar a API sem token gera um automaticamente. A
 * URL exibida usa o IP de LAN do aparelho para facilitar o primeiro comando.
 */
@Composable
fun ApiConfigSection() {
    val context = LocalContext.current
    val store = remember { GatewaySettingsStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.load()) }
    var portDraft by remember { mutableStateOf(settings.apiPort.toString()) }

    fun persist(updated: com.sufficit.ai.gateway.config.GatewaySettings) {
        store.save(updated)
        settings = updated
        RoomAudioForegroundService.reloadApi(context.applicationContext)
    }

    ConfigSection(title = "API HTTP de controle") {
        Text(
            text = "Servidor HTTP no proprio aparelho para controlar todas as funcoes e " +
                "configuracoes por comandos HTTP, inclusive participar da conversa. " +
                "Exige token em toda chamada. Bind em LAN expoe na rede local — proteja o token.",
            style = MaterialTheme.typography.bodySmall
        )
        SettingToggleRow(
            title = "Ativar API",
            supportingText = "Sobe o servidor enquanto a escuta estiver ativa.",
            checked = settings.apiEnabled,
            onCheckedChange = { enabled ->
                val token = if (enabled && settings.apiToken.isBlank()) generateToken() else settings.apiToken
                persist(settings.copy(apiEnabled = enabled, apiToken = token))
            }
        )
        SettingToggleRow(
            title = "Expor na rede local (LAN)",
            supportingText = "Ligado: acessivel por outros aparelhos da rede. Desligado: so localhost.",
            checked = settings.apiBindAllInterfaces,
            onCheckedChange = { bindAll -> persist(settings.copy(apiBindAllInterfaces = bindAll)) }
        )
        OutlinedTextField(
            value = portDraft,
            onValueChange = { portDraft = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Porta") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val port = portDraft.toIntOrNull()?.coerceIn(1024, 65535) ?: settings.apiPort
                    portDraft = port.toString()
                    persist(settings.copy(apiPort = port))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Salvar porta") }
            OutlinedButton(
                onClick = { persist(settings.copy(apiToken = generateToken(), apiEnabled = true)) },
                modifier = Modifier.weight(1f)
            ) { Text("Gerar token") }
        }
        MetadataChip("Token", if (settings.apiToken.isBlank()) "(nenhum)" else settings.apiToken)
        if (settings.apiToken.isNotBlank()) {
            OutlinedButton(
                onClick = { copyToClipboard(context, "api-token", settings.apiToken) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Copiar token") }
        }
        val host = if (settings.apiBindAllInterfaces) (localIpAddress() ?: "0.0.0.0") else "127.0.0.1"
        val baseUrl = "http://$host:${settings.apiPort}"
        MetadataChip("Base URL", baseUrl)
        MetadataChip("Status", if (settings.apiEnabled && settings.apiToken.isNotBlank()) "Ativa" else "Desativada")
        Text(
            text = "Exemplo:\n" +
                "curl $baseUrl/api/status -H 'Authorization: Bearer <token>'\n" +
                "curl -X POST $baseUrl/api/conversation -H 'Authorization: Bearer <token>' " +
                "-H 'Content-Type: application/json' -d '{\"text\":\"que horas sao?\",\"speak\":true}'",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun generateToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun localIpAddress(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
