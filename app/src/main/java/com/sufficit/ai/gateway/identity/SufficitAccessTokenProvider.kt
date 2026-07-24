package com.sufficit.ai.gateway.identity

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SufficitAuthenticatedSession(
    val accessToken: String,
    val subject: String
)

/**
 * Fonte unica de credenciais Sufficit para integracoes autenticadas do app.
 *
 * O token continua exclusivamente no armazenamento criptografado. Este
 * provider renova o access token quando necessario e nunca o registra em log
 * ou o copia para configuracoes comuns do gateway.
 */
class SufficitAccessTokenProvider(context: Context) {
    private val appContext = context.applicationContext
    private val store = SufficitIdentityStore(appContext)
    private val oauth = SufficitOAuthManager(appContext)
    private val refreshMutex = Mutex()

    suspend fun authenticatedSession(forceRefresh: Boolean = false): SufficitAuthenticatedSession {
        return refreshMutex.withLock {
            val now = System.currentTimeMillis()
            var accessToken = store.oauthAccessToken?.trim().orEmpty()
            var expiresAt = store.oauthAccessTokenExpiresAtMs
            var subject = store.subject?.trim().orEmpty()

            val tokenUsable = accessToken.isNotBlank() &&
                (expiresAt == null || expiresAt > now + EXPIRY_SAFETY_MARGIN_MS)
            if (forceRefresh || !tokenUsable) {
                val refreshToken = store.oauthRefreshToken?.trim().orEmpty()
                if (refreshToken.isBlank()) {
                    if (accessToken.isBlank()) {
                        throw SufficitAuthenticationRequiredException(
                            "Entre com a Sufficit para usar as memorias e ferramentas."
                        )
                    }
                    throw SufficitAuthenticationRequiredException(
                        "A sessao Sufficit expirou. Entre novamente para continuar."
                    )
                }

                val response = oauth.refreshAccessToken(refreshToken)
                accessToken = response.accessToken?.trim().orEmpty()
                if (accessToken.isBlank()) {
                    throw SufficitAuthenticationRequiredException(
                        "A Sufficit nao retornou um access token valido."
                    )
                }
                expiresAt = response.accessTokenExpirationTime
                store.oauthAccessToken = accessToken
                store.oauthAccessTokenExpiresAtMs = expiresAt
                response.refreshToken?.trim()?.takeIf { it.isNotBlank() }?.let {
                    store.oauthRefreshToken = it
                }
            }

            if (subject.isBlank()) {
                val info = oauth.fetchUserInfo(accessToken)
                subject = info.subject.trim()
                if (subject.isBlank()) {
                    throw SufficitAuthenticationRequiredException(
                        "A identidade Sufficit autenticada nao possui identificador de usuario."
                    )
                }
                store.subject = subject
                store.displayName = info.name
                store.email = info.email
                store.avatarUrl = info.pictureUrl
            }

            SufficitAuthenticatedSession(
                accessToken = accessToken,
                subject = subject
            )
        }
    }

    private companion object {
        const val EXPIRY_SAFETY_MARGIN_MS = 60_000L
    }
}

class SufficitAuthenticationRequiredException(message: String) : IllegalStateException(message)
