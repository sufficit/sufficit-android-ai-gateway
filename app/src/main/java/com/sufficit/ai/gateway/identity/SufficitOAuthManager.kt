package com.sufficit.ai.gateway.identity

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Entrar com Sufficit" login (Authorization Code + PKCE) against
 * sufficit-identity — the same login used by the rest of Sufficit. Mirrors
 * sufficit-mobile-ai-models' OAuthManager.kt (same identity server, same
 * flow shape) so both native Android apps behave identically.
 *
 * Client registered directly in the identity database (2026-07-19):
 * client_id=sufficit_mobile_apps, public client (no secret), PKCE required,
 * redirect sufficitaigateway://callback, scopes openid+profile+email+
 * offline_access (refresh token so login survives app restarts). Deliberately
 * a SEPARATE, shared client rather than reusing sufficit_mobile_ai_models's
 * client_id — both apps' redirect URIs are registered on it so
 * sufficit-mobile-ai-models could migrate to it later without a server-side
 * change, but today only this app authenticates against it.
 */
object SufficitOAuthConfig {
    const val ISSUER = "https://identity.sufficit.com.br"
    const val CLIENT_ID = "sufficit_mobile_apps"
    const val REDIRECT_URI = "sufficitaigateway://callback"
    val SCOPES = listOf("openid", "profile", "email", "offline_access")
}

data class SufficitUserInfo(
    val subject: String,
    val name: String?,
    val email: String?,
    val pictureUrl: String?
)

data class SufficitRefreshedToken(
    val accessToken: String,
    val accessTokenExpirationTime: Long?,
    val refreshToken: String?
)

class SufficitOAuthManager(private val context: Context) {
    private val authService = AuthorizationService(context)
    private val http = OkHttpClient()

    // Discovery is fetched once and reused for both the login intent and any
    // later userinfo/refresh call — no need to hit /.well-known again per action.
    private var cachedServiceConfig: AuthorizationServiceConfiguration? = null

    private suspend fun discover(): AuthorizationServiceConfiguration {
        cachedServiceConfig?.let { return it }
        val config = suspendCancellableCoroutine<AuthorizationServiceConfiguration> { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(SufficitOAuthConfig.ISSUER)) { result, ex ->
                if (result != null) cont.resume(result) else cont.resumeWithException(ex ?: IllegalStateException("OIDC discovery failed"))
            }
        }
        cachedServiceConfig = config
        return config
    }

    /** Fetches OIDC discovery and builds the browser intent to start login. */
    suspend fun buildAuthorizationIntent(): Intent {
        val serviceConfig = discover()

        val request = AuthorizationRequest.Builder(
            serviceConfig,
            SufficitOAuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(SufficitOAuthConfig.REDIRECT_URI)
        )
            .setScope(SufficitOAuthConfig.SCOPES.joinToString(" "))
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /** Call from the activity-result callback with the intent AppAuth handed back. */
    suspend fun exchangeCodeForTokens(resultIntent: Intent): TokenResponse {
        val authResponse = AuthorizationResponse.fromIntent(resultIntent)
        val authException = AuthorizationException.fromIntent(resultIntent)
        if (authResponse == null) throw authException ?: IllegalStateException("authorization was not successful")

        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(authResponse.createTokenExchangeRequest()) { response, ex ->
                if (response != null) cont.resume(response) else cont.resumeWithException(ex ?: IllegalStateException("token exchange failed"))
            }
        }
    }

    /**
     * Refreshes an expired access token using the stored refresh token (scope
     * `offline_access` — see class kdoc). Access tokens from
     * identity.sufficit.com.br are short-lived; without this, login would
     * silently expire and the user would need to re-authenticate constantly.
     */
    suspend fun refreshAccessToken(refreshToken: String): SufficitRefreshedToken {
        val serviceConfig = discover()
        val request = Request.Builder()
            .url(serviceConfig.tokenEndpoint.toString())
            .post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", SufficitOAuthConfig.CLIENT_ID)
                    .add("refresh_token", refreshToken)
                    .add("scope", SufficitOAuthConfig.SCOPES.joinToString(" "))
                    .build()
            )
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        val json = runCatching { JSONObject(body) }.getOrElse { parseError ->
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("token refresh returned invalid JSON", parseError)
                                )
                            }
                            return
                        }
                        if (!it.isSuccessful) {
                            val description = json.optString("error_description")
                                .ifBlank { json.optString("error") }
                                .ifBlank { "HTTP ${it.code}" }
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("token refresh failed: $description")
                                )
                            }
                            return
                        }

                        val accessToken = json.optString("access_token").trim()
                        if (accessToken.isBlank()) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("token refresh returned no access token")
                                )
                            }
                            return
                        }
                        val expiresInSeconds = json.optLong("expires_in", 0L)
                        val expiresAt = expiresInSeconds
                            .takeIf { seconds -> seconds > 0L }
                            ?.let { seconds -> System.currentTimeMillis() + seconds * 1_000L }
                        if (cont.isActive) {
                            cont.resume(
                                SufficitRefreshedToken(
                                    accessToken = accessToken,
                                    accessTokenExpirationTime = expiresAt,
                                    refreshToken = json.optString("refresh_token")
                                        .trim()
                                        .takeIf { token -> token.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            })
        }
    }

    /**
     * Standard OIDC `/connect/userinfo` — name/email/avatar for the identity
     * section header. Best-effort: callers should treat a failure here as
     * "show a generic logged-in state", not as a login failure.
     */
    suspend fun fetchUserInfo(accessToken: String): SufficitUserInfo {
        val serviceConfig = discover()
        val userInfoEndpoint = serviceConfig.discoveryDoc?.userinfoEndpoint
            ?: throw IllegalStateException("issuer has no userinfo_endpoint")

        val request = Request.Builder()
            .url(userInfoEndpoint.toString())
            .header("Authorization", "Bearer $accessToken")
            .build()

        return suspendCancellableCoroutine { cont ->
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            cont.resumeWithException(IllegalStateException("userinfo HTTP ${it.code}"))
                            return
                        }
                        val json = JSONObject(it.body?.string().orEmpty())
                        val subject = json.optString("sub")
                        cont.resume(
                            SufficitUserInfo(
                                subject = subject,
                                name = json.optString("name").takeIf { s -> s.isNotBlank() },
                                email = json.optString("email").takeIf { s -> s.isNotBlank() },
                                // Not the IdP's `picture` claim — Sufficit's own contact avatar,
                                // keyed by contextid=sub (same pattern as sufficit-mobile-ai-models).
                                pictureUrl = subject.takeIf { s -> s.isNotBlank() }
                                    ?.let { sub -> "https://endpoints.sufficit.com.br/contact/avatar?contextid=$sub" }
                            )
                        )
                    }
                }
            })
        }
    }

    fun dispose() = authService.dispose()
}
