package com.sufficit.ai.gateway.mcp

import android.content.Context
import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import com.sufficit.ai.gateway.capabilities.ClientCapabilityDescriptor
import com.sufficit.ai.gateway.capabilities.ClientCapabilityCompletionMode
import com.sufficit.ai.gateway.capabilities.ClientCapabilitySensitivity
import com.sufficit.ai.gateway.network.WakeOnLanKnownDevice
import org.json.JSONArray
import org.json.JSONObject

data class McpServerDiscoveryResult(
    val configuration: McpServerConfiguration,
    val catalog: SufficitMcpCatalog?,
    val error: String?
)

data class McpCatalogRefreshSummary(
    val servers: Int,
    val tools: Int,
    val prompts: Int,
    val resources: Int,
    val failedServers: List<String>
)

/**
 * Ponte entre todos os MCPs habilitados no aparelho e o protocolo de client
 * tools do canal Android. O catalogo nunca e codificado no app: ele nasce de
 * tools/list, prompts/list e resources/list em cada sessao configurada.
 */
class SufficitMcpToolBridge(context: Context) {
    private val appContext = context.applicationContext
    private val store = McpServerStore(appContext)
    private val clients = mutableMapOf<String, ClientHolder>()

    @Volatile
    private var cachedServers: List<ServerRuntime> = emptyList()

    @Volatile
    private var cachedTargets: Map<String, ClientToolTarget> = emptyMap()

    @Volatile
    private var cachedRefreshFailures: List<String> = emptyList()

    suspend fun refreshTools(): List<SufficitMcpTool> {
        val runtimes = mutableListOf<ServerRuntime>()
        val previousById = cachedServers.associateBy { it.configuration.id }
        val failures = mutableListOf<String>()
        store.list().filter { it.enabled }.forEach { configuration ->
            val result = runCatching {
                val catalog = clientFor(configuration).discoverCatalog(forceRefresh = true)
                store.updateDiscovery(configuration.id, catalog = catalog)
                ServerRuntime(configuration, catalog)
            }
            result.getOrNull()?.let(runtimes::add)
            result.exceptionOrNull()?.let { error ->
                store.updateDiscovery(configuration.id, error = error.message)
                failures += configuration.name
                previousById[configuration.id]
                    ?.takeIf { previous -> previous.configuration.connectionFingerprint() == configuration.connectionFingerprint() }
                    ?.let { previous -> runtimes += previous.copy(configuration = configuration) }
            }
        }
        cachedServers = runtimes
        cachedTargets = buildTargetMap(runtimes)
        cachedRefreshFailures = failures
        return runtimes.flatMap { it.catalog.tools }.map { it.copyForCaller() }
    }

    fun refreshSummary(): McpCatalogRefreshSummary = McpCatalogRefreshSummary(
        servers = cachedServers.size,
        tools = cachedServers.sumOf { it.catalog.tools.size },
        prompts = cachedServers.sumOf { it.catalog.prompts.size },
        resources = cachedServers.sumOf { it.catalog.resources.size },
        failedServers = cachedRefreshFailures.toList()
    )

    suspend fun discoverServer(serverId: String): McpServerDiscoveryResult {
        val configuration = store.find(serverId)
            ?: throw IllegalArgumentException("Servidor MCP nao encontrado.")
        val result = runCatching {
            clientFor(configuration).discoverCatalog(forceRefresh = true)
        }
        val catalog = result.getOrNull()
        val updated = store.updateDiscovery(
            id = configuration.id,
            catalog = catalog,
            error = result.exceptionOrNull()?.message
        ) ?: configuration
        return McpServerDiscoveryResult(
            configuration = updated,
            catalog = catalog,
            error = result.exceptionOrNull()?.message
        )
    }

    suspend fun reset() {
        clients.values.forEach { holder -> runCatching { holder.client.reset() } }
        clients.clear()
        cachedServers = emptyList()
        cachedTargets = emptyMap()
        cachedRefreshFailures = emptyList()
    }

    /**
     * Projeta o catalogo MCP descoberto no mesmo contrato usado pelas
     * capacidades nativas. Nenhuma credencial e copiada para o descritor.
     */
    fun clientCapabilityDescriptors(
        execute: (AgentClientActionRequest) -> Unit
    ): List<ClientCapabilityDescriptor> = buildList {
        cachedServers.forEach { runtime ->
            val provider = "mcp:${runtime.configuration.namespace}"
            runtime.catalog.tools.forEach { tool ->
                add(
                    ClientCapabilityDescriptor(
                        name = clientToolName(runtime.configuration, tool.name),
                        description = tool.description.ifBlank { "Ferramenta MCP ${tool.name}." },
                        inputSchema = JSONObject(tool.inputSchema.toString()),
                        timeoutMs = MCP_ACTION_TIMEOUT_MS,
                        provider = provider,
                        sensitivity = ClientCapabilitySensitivity.AUTHENTICATED_REMOTE,
                        completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
                        metadata = JSONObject()
                            .put("mcpTool", tool.name)
                            .put("mcpServer", runtime.configuration.name)
                            .put(
                                "authenticated",
                                runtime.configuration.authenticationMode != McpAuthenticationMode.NONE
                            ),
                        execute = execute
                    )
                )
            }
            if (runtime.catalog.prompts.isNotEmpty()) {
                val promptNames = runtime.catalog.prompts.map { it.name }
                add(
                    ClientCapabilityDescriptor(
                        name = protocolPromptToolName(runtime.configuration),
                        description = "Carrega um prompt publicado por ${runtime.configuration.name}.",
                        inputSchema = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        "prompt",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("enum", JSONArray(promptNames))
                                    )
                                    .put("promptArguments", JSONObject().put("type", "object"))
                            )
                            .put("required", JSONArray().put("prompt")),
                        timeoutMs = MCP_ACTION_TIMEOUT_MS,
                        provider = provider,
                        sensitivity = ClientCapabilitySensitivity.AUTHENTICATED_REMOTE,
                        completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
                        metadata = JSONObject().put("mcpServer", runtime.configuration.name),
                        validate = { request ->
                            val prompt = request.arguments.optString("prompt").trim()
                                .ifBlank { request.raw.optString("prompt").trim() }
                            if (prompt.isBlank()) "Informe o prompt MCP." else null
                        },
                        execute = execute
                    )
                )
            }
            if (runtime.catalog.resources.isNotEmpty()) {
                val resourceUris = runtime.catalog.resources.map { it.uri }
                add(
                    ClientCapabilityDescriptor(
                        name = protocolResourceToolName(runtime.configuration),
                        description = "Le um recurso publicado por ${runtime.configuration.name}.",
                        inputSchema = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(resourceUris))
                                )
                            )
                            .put("required", JSONArray().put("uri")),
                        timeoutMs = MCP_ACTION_TIMEOUT_MS,
                        provider = provider,
                        sensitivity = ClientCapabilitySensitivity.AUTHENTICATED_REMOTE,
                        completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
                        metadata = JSONObject().put("mcpServer", runtime.configuration.name),
                        validate = { request ->
                            val uri = request.arguments.optString("uri").trim()
                                .ifBlank { request.raw.optString("uri").trim() }
                            if (uri.isBlank()) "Informe o recurso MCP." else null
                        },
                        execute = execute
                    )
                )
            }
        }
    }

    fun displayNameForClientTool(toolName: String): String =
        cachedTargets[toolName.trim().lowercase()]?.displayName
            ?: toolName.substringAfterLast("__").ifBlank { toolName }

    suspend fun executeClientToolAction(action: JSONObject): SufficitMcpToolResult {
        val clientToolName = action.optString("tool").trim()
            .ifBlank { action.optString("name").trim() }
        val target = cachedTargets[clientToolName.lowercase()]
            ?: legacyTarget(clientToolName)
            ?: throw IllegalArgumentException(
                "Ferramenta cliente '$clientToolName' nao pertence ao catalogo MCP descoberto."
            )
        val configuration = store.find(target.serverId)
            ?: throw IllegalStateException("Servidor MCP da ferramenta nao esta mais configurado.")
        require(configuration.enabled) { "Servidor MCP '${configuration.name}' esta desativado." }
        val client = clientFor(configuration)
        return when (target.kind) {
            ClientToolKind.TOOL ->
                client.callTool(target.remoteName, extractArguments(action))
            ClientToolKind.PROMPT -> {
                val promptName = action.optString("prompt").trim()
                val promptArguments = action.optJSONObject("promptArguments")
                    ?: action.optJSONObject("arguments")
                    ?: JSONObject()
                client.getPrompt(promptName, promptArguments)
            }
            ClientToolKind.RESOURCE ->
                client.readResource(action.optString("uri").trim())
        }
    }

    /**
     * Migra/atualiza uma preferencia Wake-on-LAN nomeada para a memoria
     * Sufficit. O servidor atribui o UserId pelo access token; nenhum owner id
     * vindo do aparelho e enviado ou aceito.
     */
    suspend fun saveWakeOnLanPreference(device: WakeOnLanKnownDevice): SufficitMcpToolResult {
        val name = device.name?.trim().orEmpty()
        require(name.isNotBlank()) { "O dispositivo precisa de um nome antes de virar preferencia." }
        val client = sufficitClient()

        val payload = JSONObject()
            .put("schema", "sufficit.client-device-preference/v1")
            .put("kind", "wake-on-lan")
            .put("name", name)
            .put("macAddress", device.macAddress)
            .put("ipAddress", device.ipAddress)
            .put("broadcastAddress", device.broadcastAddress)
            .put("source", device.source)
            .put("lastSeenAtEpochMs", device.lastSeenAtEpochMs)
            .put("lastVerifiedAtEpochMs", device.lastVerifiedAtEpochMs)
            .put("lastReachable", device.lastReachable)

        val summary = buildString {
            append("Preferencia Wake-on-LAN: ")
            append(name)
            append(" usa o MAC ")
            append(device.macAddress)
            device.ipAddress?.takeIf { it.isNotBlank() }?.let {
                append(" e o IP ")
                append(it)
            }
            append(". Quando o usuario disser \"")
            append(name)
            append("\" ou \"meu computador\", resolva este dispositivo antes de executar Wake-on-LAN.")
        }

        val existingId = findWakeOnLanPreferenceId(client, device.macAddress)
        return if (existingId == null) {
            client.callTool(
                "memory_save",
                JSONObject()
                    .put("type", MEMORY_TYPE)
                    .put("title", "Dispositivo Wake-on-LAN: $name")
                    .put("summary", summary)
                    .put("payload", payload.toString())
                    .put("tags", "android,client-tool,wake-on-lan,device-preference")
                    .put("privacyLevel", "private")
            )
        } else {
            client.callTool(
                "memory_update",
                JSONObject()
                    .put("id", existingId)
                    .put("type", MEMORY_TYPE)
                    .put("title", "Dispositivo Wake-on-LAN: $name")
                    .put("summary", summary)
                    .put("payload", payload.toString())
                    .put("tags", "android,client-tool,wake-on-lan,device-preference")
                    .put("privacyLevel", "private")
            )
        }
    }

    private suspend fun findWakeOnLanPreferenceId(
        client: SufficitMcpClient,
        macAddress: String
    ): String? {
        val search = client.callTool(
            "memory_search",
            JSONObject()
                .put("query", macAddress)
                .put("type", MEMORY_TYPE)
                .put("limit", 20)
        ).asJsonValue()
        val records = search as? JSONArray ?: return null
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val searchable = listOf(
                record.optString("title"),
                record.optString("summary")
            ).joinToString(" ")
            if (searchable.contains(macAddress, ignoreCase = true)) {
                return record.optString("id").trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun sufficitClient(): SufficitMcpClient {
        val configuration = store.find(McpServerStore.SUFFICIT_SERVER_ID)
            ?: McpServerStore.defaultSufficitServer()
        require(configuration.enabled) { "O MCP Sufficit AI esta desativado." }
        return clientFor(configuration)
    }

    private fun clientFor(configuration: McpServerConfiguration): SufficitMcpClient {
        val fingerprint = configuration.connectionFingerprint()
        val current = clients[configuration.id]
        if (current != null && current.fingerprint == fingerprint) return current.client
        val replacement = McpClientFactory.create(appContext, configuration)
        clients[configuration.id] = ClientHolder(fingerprint, replacement)
        return replacement
    }

    private fun buildTargetMap(
        runtimes: List<ServerRuntime>
    ): Map<String, ClientToolTarget> = buildMap {
        runtimes.forEach { runtime ->
            runtime.catalog.tools.forEach { tool ->
                put(
                    clientToolName(runtime.configuration, tool.name).lowercase(),
                    ClientToolTarget(
                        serverId = runtime.configuration.id,
                        kind = ClientToolKind.TOOL,
                        remoteName = tool.name,
                        displayName = "${runtime.configuration.name}: ${tool.name}"
                    )
                )
            }
            if (runtime.catalog.prompts.isNotEmpty()) {
                put(
                    protocolPromptToolName(runtime.configuration).lowercase(),
                    ClientToolTarget(
                        serverId = runtime.configuration.id,
                        kind = ClientToolKind.PROMPT,
                        remoteName = "",
                        displayName = "${runtime.configuration.name}: prompt"
                    )
                )
            }
            if (runtime.catalog.resources.isNotEmpty()) {
                put(
                    protocolResourceToolName(runtime.configuration).lowercase(),
                    ClientToolTarget(
                        serverId = runtime.configuration.id,
                        kind = ClientToolKind.RESOURCE,
                        remoteName = "",
                        displayName = "${runtime.configuration.name}: recurso"
                    )
                )
            }
        }
    }

    private fun legacyTarget(clientToolName: String): ClientToolTarget? {
        val remoteName = SufficitMcpClient.fromClientToolName(clientToolName) ?: return null
        return ClientToolTarget(
            serverId = McpServerStore.SUFFICIT_SERVER_ID,
            kind = ClientToolKind.TOOL,
            remoteName = remoteName,
            displayName = "Sufficit AI: $remoteName"
        )
    }

    private fun extractArguments(action: JSONObject): JSONObject {
        action.optJSONObject("arguments")?.let { return JSONObject(it.toString()) }
        action.optJSONObject("args")?.let { return JSONObject(it.toString()) }
        val hasExplicitTool = action.optString("tool").isNotBlank()
        return JSONObject().also { output ->
            val keys = action.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val envelopeKey = key in ACTION_ENVELOPE_KEYS ||
                    (!hasExplicitTool && key == "name")
                if (!envelopeKey) output.put(key, action.opt(key))
            }
        }
    }

    private fun clientToolName(
        configuration: McpServerConfiguration,
        remoteName: String
    ): String = if (configuration.id == McpServerStore.SUFFICIT_SERVER_ID) {
        SufficitMcpClient.toClientToolName(remoteName)
    } else {
        "${SufficitMcpClient.GENERIC_CLIENT_TOOL_PREFIX}${configuration.namespace}__${remoteName.trim()}"
    }

    private fun protocolPromptToolName(configuration: McpServerConfiguration): String =
        "${SufficitMcpClient.GENERIC_CLIENT_TOOL_PREFIX}${configuration.namespace}__protocol_prompt_get"

    private fun protocolResourceToolName(configuration: McpServerConfiguration): String =
        "${SufficitMcpClient.GENERIC_CLIENT_TOOL_PREFIX}${configuration.namespace}__protocol_resource_read"

    private data class ClientHolder(
        val fingerprint: String,
        val client: SufficitMcpClient
    )

    private data class ServerRuntime(
        val configuration: McpServerConfiguration,
        val catalog: SufficitMcpCatalog
    )

    private data class ClientToolTarget(
        val serverId: String,
        val kind: ClientToolKind,
        val remoteName: String,
        val displayName: String
    )

    private enum class ClientToolKind {
        TOOL,
        PROMPT,
        RESOURCE
    }

    companion object {
        private const val MCP_ACTION_TIMEOUT_MS = 60_000L
        private const val MEMORY_TYPE = "client-device-preference"
        private val ACTION_ENVELOPE_KEYS = setOf(
            "tool",
            "label",
            "callId",
            "call_id",
            "arguments",
            "args"
        )
    }
}

private fun McpServerConfiguration.connectionFingerprint(): String = listOf(
    endpoint,
    authenticationMode.persistedValue,
    bearerToken
).joinToString("\u0000")
