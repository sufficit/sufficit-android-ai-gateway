package com.sufficit.ai.gateway.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class McpAuthenticationMode(val persistedValue: String) {
    SUFFICIT("sufficit"),
    BEARER("bearer"),
    NONE("none");

    companion object {
        fun fromPersistedValue(value: String?): McpAuthenticationMode =
            entries.firstOrNull { it.persistedValue == value } ?: NONE
    }
}

data class McpCapabilitySummary(
    val tools: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val discoveredAtEpochMs: Long = 0L,
    val error: String? = null
)

data class McpServerConfiguration(
    val id: String,
    val namespace: String,
    val name: String,
    val endpoint: String,
    val authenticationMode: McpAuthenticationMode,
    val bearerToken: String = "",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val summary: McpCapabilitySummary = McpCapabilitySummary()
)

/**
 * Registro criptografado de servidores MCP configurados no aparelho.
 *
 * O mesmo armazenamento contem metadados e credenciais porque configuracoes
 * customizadas podem carregar bearer tokens. O servidor Sufficit e semeado na
 * primeira leitura e usa o OAuth ja autenticado no aplicativo.
 */
class McpServerStore(context: Context) {
    private val preferences: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun list(): List<McpServerConfiguration> {
        val saved = readSaved()
        val withBuiltIn = if (saved.any { it.id == SUFFICIT_SERVER_ID }) {
            saved
        } else {
            listOf(defaultSufficitServer()) + saved
        }
        if (withBuiltIn != saved) persist(withBuiltIn)
        return withBuiltIn.sortedWith(
            compareByDescending<McpServerConfiguration> { it.builtIn }
                .thenBy { it.name.lowercase() }
        )
    }

    @Synchronized
    fun find(id: String): McpServerConfiguration? =
        list().firstOrNull { it.id == id }

    @Synchronized
    fun save(configuration: McpServerConfiguration): McpServerConfiguration {
        val current = list().toMutableList()
        val existingIndex = current.indexOfFirst { it.id == configuration.id }
        val normalized = normalize(
            configuration.copy(
                id = configuration.id.ifBlank { UUID.randomUUID().toString() },
                builtIn = configuration.builtIn ||
                    configuration.id == SUFFICIT_SERVER_ID ||
                    current.getOrNull(existingIndex)?.builtIn == true
            )
        )
        if (existingIndex >= 0) current[existingIndex] = normalized else current += normalized
        persist(current)
        return normalized
    }

    @Synchronized
    fun createDraft(): McpServerConfiguration {
        val id = UUID.randomUUID().toString()
        return McpServerConfiguration(
            id = id,
            namespace = "server_${id.take(8)}",
            name = "Novo MCP",
            endpoint = "",
            authenticationMode = McpAuthenticationMode.NONE
        )
    }

    fun createTuyaDraft(): McpServerConfiguration {
        val id = UUID.randomUUID().toString()
        return McpServerConfiguration(
            id = id,
            namespace = TUYA_NAMESPACE,
            name = "Tuya / Smart Life",
            endpoint = TUYA_MCP_ENDPOINT,
            authenticationMode = McpAuthenticationMode.BEARER,
            enabled = true,
            builtIn = false
        )
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = list()
        val target = current.firstOrNull { it.id == id } ?: return false
        if (target.builtIn) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun updateDiscovery(
        id: String,
        catalog: SufficitMcpCatalog? = null,
        error: String? = null
    ): McpServerConfiguration? {
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val existing = current[index]
        val summary = if (catalog != null) {
            McpCapabilitySummary(
                tools = catalog.tools.map { it.name },
                prompts = catalog.prompts.map { it.name },
                resources = catalog.resources.map { it.name.ifBlank { it.uri } },
                discoveredAtEpochMs = System.currentTimeMillis(),
                error = null
            )
        } else {
            existing.summary.copy(
                discoveredAtEpochMs = System.currentTimeMillis(),
                error = error?.trim()?.takeIf { it.isNotBlank() } ?: "Falha na descoberta MCP."
            )
        }
        val updated = existing.copy(summary = summary)
        current[index] = updated
        persist(current)
        return updated
    }

    private fun readSaved(): List<McpServerConfiguration> {
        val raw = preferences.getString(KEY_SERVERS, null)?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    fromJson(item)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(servers: List<McpServerConfiguration>) {
        val array = JSONArray()
        servers.distinctBy { it.id }.forEach { array.put(toJson(normalize(it))) }
        preferences.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun normalize(configuration: McpServerConfiguration): McpServerConfiguration {
        val name = configuration.name.trim().ifBlank { "MCP sem nome" }
        val endpoint = configuration.endpoint.trim().trimEnd('/')
        val namespace = sanitizeNamespace(configuration.namespace.ifBlank { name })
            .ifBlank { "server_${configuration.id.take(8)}" }
        return configuration.copy(
            namespace = namespace,
            name = name,
            endpoint = endpoint,
            bearerToken = configuration.bearerToken.trim()
        )
    }

    private fun toJson(value: McpServerConfiguration): JSONObject = JSONObject()
        .put("id", value.id)
        .put("namespace", value.namespace)
        .put("name", value.name)
        .put("endpoint", value.endpoint)
        .put("authenticationMode", value.authenticationMode.persistedValue)
        .put("bearerToken", value.bearerToken)
        .put("enabled", value.enabled)
        .put("builtIn", value.builtIn)
        .put(
            "summary",
            JSONObject()
                .put("tools", JSONArray(value.summary.tools))
                .put("prompts", JSONArray(value.summary.prompts))
                .put("resources", JSONArray(value.summary.resources))
                .put("discoveredAtEpochMs", value.summary.discoveredAtEpochMs)
                .put("error", value.summary.error)
        )

    private fun fromJson(value: JSONObject): McpServerConfiguration? {
        val id = value.optString("id").trim()
        if (id.isBlank()) return null
        val summaryJson = value.optJSONObject("summary")
        return normalize(
            McpServerConfiguration(
                id = id,
                namespace = value.optString("namespace"),
                name = value.optString("name"),
                endpoint = value.optString("endpoint"),
                authenticationMode = McpAuthenticationMode.fromPersistedValue(
                    value.optString("authenticationMode")
                ),
                bearerToken = value.optString("bearerToken"),
                enabled = value.optBoolean("enabled", true),
                builtIn = value.optBoolean("builtIn", id == SUFFICIT_SERVER_ID),
                summary = McpCapabilitySummary(
                    tools = summaryJson?.optJSONArray("tools").toStringList(),
                    prompts = summaryJson?.optJSONArray("prompts").toStringList(),
                    resources = summaryJson?.optJSONArray("resources").toStringList(),
                    discoveredAtEpochMs = summaryJson?.optLong("discoveredAtEpochMs") ?: 0L,
                    error = summaryJson?.optString("error")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                )
            )
        )
    }

    companion object {
        const val SUFFICIT_SERVER_ID = "sufficit-ai"
        const val TUYA_NAMESPACE = "tuya"
        const val TUYA_MCP_ENDPOINT = "https://openclaw.sufficit.com.br/mcp/tuya"
        private const val PREFERENCES_NAME = "mcp_servers_secure_prefs"
        private const val KEY_SERVERS = "servers_json"

        fun defaultSufficitServer(): McpServerConfiguration =
            McpServerConfiguration(
                id = SUFFICIT_SERVER_ID,
                namespace = "sufficit",
                name = "Sufficit AI",
                endpoint = SufficitMcpClient.DEFAULT_ENDPOINT,
                authenticationMode = McpAuthenticationMode.SUFFICIT,
                enabled = true,
                builtIn = true
            )

        fun sanitizeNamespace(value: String): String = value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(28)
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
