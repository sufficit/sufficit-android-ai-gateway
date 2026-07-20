package com.sufficit.ai.gateway.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists "Entrar com Sufficit" OAuth state — access/refresh tokens plus
 * cached profile fields from `/connect/userinfo`. EncryptedSharedPreferences
 * (Android Keystore-backed), never plain prefs or logs — mirrors
 * sufficit-mobile-ai-models' PairingStore.kt (same shape, OAuth-only subset:
 * this app has no separate pairing-token mode).
 *
 * Deliberately a SEPARATE store from GatewaySettingsStore/GatewayConfigCatalog:
 * those hold non-secret runtime config (server addresses, feature toggles)
 * persisted as plain JSON; OAuth tokens are the one thing in this app that
 * actually needs encryption at rest.
 */
class SufficitIdentityStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "sufficit_identity_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var oauthAccessToken: String?
        get() = prefs.getString(KEY_OAUTH_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OAUTH_ACCESS_TOKEN, value).apply()

    var oauthRefreshToken: String?
        get() = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OAUTH_REFRESH_TOKEN, value).apply()

    /** Epoch millis when [oauthAccessToken] expires, or null if unknown. */
    var oauthAccessTokenExpiresAtMs: Long?
        get() = prefs.getLong(KEY_OAUTH_EXPIRES_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong(KEY_OAUTH_EXPIRES_AT, value ?: -1L).apply()

    /** Cached from `/connect/userinfo` — best-effort, only used for display. */
    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR_URL, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_URL, value).apply()

    /** OIDC `sub` claim — stable per-person id, used to auto-fill openClawUserId. */
    var subject: String?
        get() = prefs.getString(KEY_SUBJECT, null)
        set(value) = prefs.edit().putString(KEY_SUBJECT, value).apply()

    fun isLoggedIn(): Boolean = !oauthAccessToken.isNullOrBlank()

    fun clearSession() {
        prefs.edit()
            .remove(KEY_OAUTH_ACCESS_TOKEN)
            .remove(KEY_OAUTH_REFRESH_TOKEN)
            .remove(KEY_OAUTH_EXPIRES_AT)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_AVATAR_URL)
            .remove(KEY_SUBJECT)
            .apply()
    }

    private companion object {
        const val KEY_OAUTH_ACCESS_TOKEN = "oauth_access_token"
        const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
        const val KEY_OAUTH_EXPIRES_AT = "oauth_expires_at"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_EMAIL = "email"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_SUBJECT = "subject"
    }
}
