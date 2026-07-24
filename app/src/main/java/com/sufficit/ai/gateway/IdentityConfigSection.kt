package com.sufficit.ai.gateway

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.InstallationId
import com.sufficit.ai.gateway.identity.SufficitIdentityStore
import com.sufficit.ai.gateway.identity.SufficitOAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identidade da PESSOA: "Entrar com Sufficit" (OAuth, mesmo login do resto da
 * Sufficit — ver identity/SufficitOAuthManager.kt). Uma vez logado, o userId
 * (sufficit-ai) e preenchido automaticamente a partir do login em vez de
 * digitado a mao — liga esta instalacao ao perfil/preferencias da pessoa no
 * servidor. Uma pessoa pode ter varias instalacoes, todas com o mesmo login.
 *
 * O installationId identifica este aparelho de forma estavel (nao depende do
 * ANDROID_ID, que muda com reinstalacao/repackage). E somente leitura.
 */
@Composable
fun IdentityConfigSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GatewaySettingsStore(context.applicationContext) }
    val identityStore = remember { SufficitIdentityStore(context.applicationContext) }
    val oauth = remember { SufficitOAuthManager(context.applicationContext) }
    var settings by remember { mutableStateOf(store.load()) }
    val installationId = remember { InstallationId.get(context.applicationContext) }

    var loggedIn by remember { mutableStateOf(identityStore.isLoggedIn()) }
    var displayName by remember { mutableStateOf(identityStore.displayName) }
    var email by remember { mutableStateOf(identityStore.email) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(oauth) {
        onDispose { oauth.dispose() }
    }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            loading = false
            status = "Login cancelado."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            status = null
            try {
                val tokenResponse = withContext(Dispatchers.IO) { oauth.exchangeCodeForTokens(data) }
                val accessToken = tokenResponse.accessToken
                    ?: throw IllegalStateException("resposta sem access_token")

                // Login salvo imediatamente — busca de userinfo abaixo e melhor-esforco,
                // uma falha ali nao pode desfazer um login que ja deu certo.
                identityStore.oauthAccessToken = accessToken
                identityStore.oauthRefreshToken = tokenResponse.refreshToken
                identityStore.oauthAccessTokenExpiresAtMs = tokenResponse.accessTokenExpirationTime

                var newUserId: String? = null
                try {
                    val info = withContext(Dispatchers.IO) { oauth.fetchUserInfo(accessToken) }
                    identityStore.displayName = info.name
                    identityStore.email = info.email
                    identityStore.avatarUrl = info.pictureUrl
                    identityStore.subject = info.subject
                    // userId e sempre a claim "sub" (Guid estavel) — nunca o e-mail,
                    // que pode mudar e nao e o identificador canonico da pessoa.
                    newUserId = info.subject.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    // Melhor-esforco: sessao ja esta salva, so ficamos sem nome/e-mail pra exibir.
                }

                loggedIn = true
                displayName = identityStore.displayName
                email = identityStore.email
                loading = false

                if (!newUserId.isNullOrBlank() && newUserId != settings.openClawUserId) {
                    val updated = settings.copy(openClawUserId = newUserId)
                    store.save(updated)
                    settings = updated
                }
                // Reabre a sessao MCP e atualiza o catalogo de client tools
                // mesmo quando o mesmo usuario apenas renovou o login.
                RoomAudioForegroundService.reloadConfig(context.applicationContext)
            } catch (ex: Exception) {
                loading = false
                status = "Falha no login: ${ex.message ?: ex.javaClass.simpleName}"
            }
        }
    }

    ConfigSection(title = "Identidade") {
        Text(
            text = "Entrar com Sufficit liga este aparelho ao seu perfil e preferencias no " +
                "assistente — a mesma pessoa pode ter varios aparelhos com o mesmo login.",
            style = MaterialTheme.typography.bodySmall
        )
        if (loggedIn) {
            MetadataChip("Logado como", displayName ?: email ?: "Sufficit")
            if (!email.isNullOrBlank() && email != displayName) {
                MetadataChip("E-mail", email.orEmpty())
            }
            OutlinedButton(
                onClick = {
                    identityStore.clearSession()
                    if (settings.openClawUserId.isNotBlank()) {
                        val updated = settings.copy(openClawUserId = "")
                        store.save(updated)
                        settings = updated
                    }
                    // Remove imediatamente o catalogo MCP autenticado e
                    // reconecta o canal sem reutilizar a identidade anterior.
                    RoomAudioForegroundService.reloadConfig(context.applicationContext)
                    loggedIn = false
                    displayName = null
                    email = null
                    status = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sair") }
        } else {
            Button(
                onClick = {
                    loading = true
                    status = null
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { oauth.buildAuthorizationIntent() }
                            loginLauncher.launch(intent)
                        } catch (ex: Exception) {
                            loading = false
                            status = "Nao foi possivel iniciar o login: ${ex.message ?: ex.javaClass.simpleName}"
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Entrar com Sufficit")
                }
            }
        }
        status?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        MetadataChip("userId atual", settings.openClawUserId.ifBlank { "(nao vinculado)" })
        MetadataChip("installationId", installationId)
        OutlinedButton(
            onClick = { copyToClipboard(context, "installationId", installationId) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Copiar installationId") }
        if (settings.openClawUserId.isBlank()) {
            Text(
                text = "Sem login, o servidor identifica pelo vinculo do aparelho (fragil). " +
                    "Entre com Sufficit para o reconhecimento estavel da pessoa.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
