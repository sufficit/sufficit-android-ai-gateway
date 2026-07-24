package com.sufficit.ai.gateway.mcp

import android.content.Context
import com.sufficit.ai.gateway.identity.SufficitAccessTokenProvider
import com.sufficit.ai.gateway.identity.SufficitAuthenticatedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class SufficitMcpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
) {
    fun copyForCaller(): SufficitMcpTool = copy(
        inputSchema = JSONObject(inputSchema.toString())
    )
}

data class SufficitMcpPromptArgument(
    val name: String,
    val description: String,
    val required: Boolean
)

data class SufficitMcpPrompt(
    val name: String,
    val description: String,
    val arguments: List<SufficitMcpPromptArgument>
)

data class SufficitMcpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String
)

data class SufficitMcpCatalog(
    val tools: List<SufficitMcpTool> = emptyList(),
    val prompts: List<SufficitMcpPrompt> = emptyList(),
    val resources: List<SufficitMcpResource> = emptyList()
)

data class SufficitMcpToolResult(
    val toolName: String,
    val text: String,
    val rawResult: JSONObject
) {
    fun asJsonValue(): Any {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return JSONObject.NULL
        return runCatching { JSONObject(trimmed) }.getOrElse {
            runCatching { JSONArray(trimmed) }.getOrElse { trimmed }
        }
    }
}

interface McpSessionProvider {
    suspend fun authenticatedSession(forceRefresh: Boolean = false): SufficitAuthenticatedSession
}

private class SufficitOAuthMcpSessionProvider(context: Context) : McpSessionProvider {
    private val delegate = SufficitAccessTokenProvider(context.applicationContext)

    override suspend fun authenticatedSession(forceRefresh: Boolean): SufficitAuthenticatedSession =
        delegate.authenticatedSession(forceRefresh)
}

private class StaticMcpSessionProvider(
    private val accessToken: String,
    private val subject: String
) : McpSessionProvider {
    override suspend fun authenticatedSession(forceRefresh: Boolean): SufficitAuthenticatedSession =
        SufficitAuthenticatedSession(accessToken = accessToken, subject = subject)
}

object McpClientFactory {
    fun create(context: Context, configuration: McpServerConfiguration): SufficitMcpClient {
        val provider = when (configuration.authenticationMode) {
            McpAuthenticationMode.SUFFICIT ->
                SufficitOAuthMcpSessionProvider(context.applicationContext)
            McpAuthenticationMode.BEARER -> {
                require(configuration.bearerToken.isNotBlank()) {
                    "Informe o bearer token deste servidor MCP."
                }
                StaticMcpSessionProvider(
                    accessToken = configuration.bearerToken,
                    subject = "mcp:${configuration.id}"
                )
            }
            McpAuthenticationMode.NONE ->
                StaticMcpSessionProvider(accessToken = "", subject = "mcp:${configuration.id}")
        }
        return SufficitMcpClient(
            context = context.applicationContext,
            endpoint = configuration.endpoint,
            sessionProvider = provider
        )
    }
}

/**
 * Cliente MCP Streamable HTTP.
 *
 * O transporte e inicializado por endpoint/sessao, enquanto tools, prompts e
 * resources sao sempre descobertos dinamicamente. O nome historico foi
 * preservado para manter compatibilidade binaria com o bridge ja existente.
 */
class SufficitMcpClient(
    context: Context,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val sessionProvider: McpSessionProvider =
        SufficitOAuthMcpSessionProvider(context.applicationContext)
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val lock = Mutex()
    private val requestIds = AtomicLong(1L)

    private var sessionId: String? = null
    private var sessionSubject: String? = null
    private var sessionInitialized = false
    private var catalogLoaded = false
    private var catalog = SufficitMcpCatalog()

    suspend fun discoverCatalog(forceRefresh: Boolean = false): SufficitMcpCatalog =
        withContext(Dispatchers.IO) {
            lock.withLock {
                withAuthenticatedSessionLocked { auth ->
                    if (forceRefresh || !catalogLoaded) {
                        catalog = SufficitMcpCatalog(
                            tools = listToolsLocked(auth),
                            prompts = listPromptsLocked(auth),
                            resources = listResourcesLocked(auth)
                        )
                        catalogLoaded = true
                    }
                    catalog.copyForCaller()
                }
            }
        }

    suspend fun discoverTools(forceRefresh: Boolean = false): List<SufficitMcpTool> =
        discoverCatalog(forceRefresh).tools

    suspend fun callTool(
        name: String,
        arguments: JSONObject = JSONObject()
    ): SufficitMcpToolResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val normalizedName = name.trim()
            require(normalizedName.isNotBlank()) { "Nome da ferramenta MCP ausente." }
            withAuthenticatedSessionLocked { auth ->
                if (!catalogLoaded) {
                    catalog = catalog.copy(tools = listToolsLocked(auth))
                    catalogLoaded = true
                }
                require(catalog.tools.any { it.name == normalizedName }) {
                    "Ferramenta MCP '$normalizedName' nao esta no catalogo descoberto."
                }
                callToolLocked(auth, normalizedName, arguments)
            }
        }
    }

    suspend fun getPrompt(
        name: String,
        arguments: JSONObject = JSONObject()
    ): SufficitMcpToolResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val normalizedName = name.trim()
            require(normalizedName.isNotBlank()) { "Nome do prompt MCP ausente." }
            withAuthenticatedSessionLocked { auth ->
                val response = executeRpcLocked(
                    auth = auth,
                    method = "prompts/get",
                    params = JSONObject()
                        .put("name", normalizedName)
                        .put("arguments", JSONObject(arguments.toString()))
                )
                val result = response.optJSONObject("result")
                    ?: throw SufficitMcpException("Resposta prompts/get sem result.")
                SufficitMcpToolResult(
                    toolName = "protocol_prompt_get",
                    text = result.toString(),
                    rawResult = JSONObject(result.toString())
                )
            }
        }
    }

    suspend fun readResource(uri: String): SufficitMcpToolResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val normalizedUri = uri.trim()
                require(normalizedUri.isNotBlank()) { "URI do recurso MCP ausente." }
                withAuthenticatedSessionLocked { auth ->
                    val response = executeRpcLocked(
                        auth = auth,
                        method = "resources/read",
                        params = JSONObject().put("uri", normalizedUri)
                    )
                    val result = response.optJSONObject("result")
                        ?: throw SufficitMcpException("Resposta resources/read sem result.")
                    SufficitMcpToolResult(
                        toolName = "protocol_resource_read",
                        text = result.toString(),
                        rawResult = JSONObject(result.toString())
                    )
                }
            }
        }

    suspend fun reset() = withContext(Dispatchers.IO) {
        lock.withLock { clearSessionLocked() }
    }

    private suspend fun <T> withAuthenticatedSessionLocked(
        block: (SufficitAuthenticatedSession) -> T
    ): T {
        var auth = sessionProvider.authenticatedSession()
        ensureSessionLocked(auth)
        return try {
            block(auth)
        } catch (error: McpSessionExpiredException) {
            clearSessionLocked()
            ensureSessionLocked(auth)
            block(auth)
        } catch (error: McpAuthenticationException) {
            clearSessionLocked()
            auth = sessionProvider.authenticatedSession(forceRefresh = true)
            ensureSessionLocked(auth)
            block(auth)
        }
    }

    private fun ensureSessionLocked(auth: SufficitAuthenticatedSession) {
        if (sessionSubject != auth.subject) clearSessionLocked()
        if (sessionInitialized) return

        val payload = JSONObject()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("id", requestIds.getAndIncrement())
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put("protocolVersion", MCP_PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", CLIENT_NAME)
                            .put("version", CLIENT_VERSION)
                    )
            )
        val response = executeHttp(auth, payload, includeSession = false)
        validateRpcResponse(response.body, response.statusCode)
        sessionId = response.sessionId?.trim()?.takeIf { it.isNotBlank() }
        sessionSubject = auth.subject
        sessionInitialized = true

        executeHttp(
            auth = auth,
            payload = JSONObject()
                .put("jsonrpc", JSON_RPC_VERSION)
                .put("method", "notifications/initialized"),
            includeSession = true,
            allowEmptyBody = true
        )
    }

    private fun listToolsLocked(auth: SufficitAuthenticatedSession): List<SufficitMcpTool> {
        val output = mutableListOf<SufficitMcpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val response = executeRpcLocked(
                auth = auth,
                method = "tools/list",
                params = cursorParams(cursor)
            )
            val result = response.optJSONObject("result")
                ?: throw SufficitMcpException("Resposta tools/list sem result.")
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until tools.length()) {
                val value = tools.optJSONObject(index) ?: continue
                val name = value.optString("name").trim()
                if (name.isBlank()) continue
                output += SufficitMcpTool(
                    name = name,
                    description = value.optString("description").trim(),
                    inputSchema = value.optJSONObject("inputSchema")
                        ?.let { JSONObject(it.toString()) }
                        ?: JSONObject().put("type", "object")
                )
            }
            cursor = result.optString("nextCursor").trim().takeIf { it.isNotBlank() }
            if (cursor == null) return output.distinctBy { it.name }.sortedBy { it.name }
        }
        return output.distinctBy { it.name }.sortedBy { it.name }
    }

    private fun listPromptsLocked(auth: SufficitAuthenticatedSession): List<SufficitMcpPrompt> {
        val output = mutableListOf<SufficitMcpPrompt>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val response = try {
                executeRpcLocked(auth, "prompts/list", cursorParams(cursor))
            } catch (_: McpMethodNotFoundException) {
                return emptyList()
            }
            val result = response.optJSONObject("result") ?: return output
            val prompts = result.optJSONArray("prompts") ?: JSONArray()
            for (index in 0 until prompts.length()) {
                val value = prompts.optJSONObject(index) ?: continue
                val name = value.optString("name").trim()
                if (name.isBlank()) continue
                val promptArguments = value.optJSONArray("arguments") ?: JSONArray()
                val arguments = buildList {
                    for (argumentIndex in 0 until promptArguments.length()) {
                        val argument = promptArguments.optJSONObject(argumentIndex) ?: continue
                        val argumentName = argument.optString("name").trim()
                        if (argumentName.isBlank()) continue
                        add(
                            SufficitMcpPromptArgument(
                                name = argumentName,
                                description = argument.optString("description").trim(),
                                required = argument.optBoolean("required", false)
                            )
                        )
                    }
                }
                output += SufficitMcpPrompt(
                    name = name,
                    description = value.optString("description").trim(),
                    arguments = arguments
                )
            }
            cursor = result.optString("nextCursor").trim().takeIf { it.isNotBlank() }
            if (cursor == null) return output.distinctBy { it.name }.sortedBy { it.name }
        }
        return output.distinctBy { it.name }.sortedBy { it.name }
    }

    private fun listResourcesLocked(auth: SufficitAuthenticatedSession): List<SufficitMcpResource> {
        val output = mutableListOf<SufficitMcpResource>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val response = try {
                executeRpcLocked(auth, "resources/list", cursorParams(cursor))
            } catch (_: McpMethodNotFoundException) {
                return emptyList()
            }
            val result = response.optJSONObject("result") ?: return output
            val resources = result.optJSONArray("resources") ?: JSONArray()
            for (index in 0 until resources.length()) {
                val value = resources.optJSONObject(index) ?: continue
                val uri = value.optString("uri").trim()
                if (uri.isBlank()) continue
                output += SufficitMcpResource(
                    uri = uri,
                    name = value.optString("name").trim(),
                    description = value.optString("description").trim(),
                    mimeType = value.optString("mimeType").trim()
                )
            }
            cursor = result.optString("nextCursor").trim().takeIf { it.isNotBlank() }
            if (cursor == null) return output.distinctBy { it.uri }.sortedBy { it.name.ifBlank { it.uri } }
        }
        return output.distinctBy { it.uri }.sortedBy { it.name.ifBlank { it.uri } }
    }

    private fun callToolLocked(
        auth: SufficitAuthenticatedSession,
        name: String,
        arguments: JSONObject
    ): SufficitMcpToolResult {
        val response = executeRpcLocked(
            auth = auth,
            method = "tools/call",
            params = JSONObject()
                .put("name", name)
                .put("arguments", JSONObject(arguments.toString()))
        )
        val result = response.optJSONObject("result")
            ?: throw SufficitMcpException("Resposta da ferramenta '$name' sem result.")
        val text = extractTextContent(result.optJSONArray("content"))
        if (result.optBoolean("isError", false)) {
            throw SufficitMcpException(text.ifBlank { "A ferramenta '$name' retornou erro." })
        }
        return SufficitMcpToolResult(
            toolName = name,
            text = text,
            rawResult = JSONObject(result.toString())
        )
    }

    private fun executeRpcLocked(
        auth: SufficitAuthenticatedSession,
        method: String,
        params: JSONObject
    ): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("id", requestIds.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val response = executeHttp(auth, payload, includeSession = true)
        return validateRpcResponse(response.body, response.statusCode)
    }

    private fun executeHttp(
        auth: SufficitAuthenticatedSession,
        payload: JSONObject,
        includeSession: Boolean,
        allowEmptyBody: Boolean = false
    ): RawMcpResponse {
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .header(MEMORY_SOURCE_HEADER, CLIENT_NAME)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        auth.accessToken.trim().takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        auth.subject.trim()
            .takeIf { subject ->
                runCatching { UUID.fromString(subject) }
                    .getOrNull()
                    ?.takeUnless { it == UUID(0L, 0L) } != null
            }
            ?.let { subject ->
                builder.header(MEMORY_CONTEXT_HEADER, subject)
            }
        if (includeSession) {
            sessionId?.takeIf { it.isNotBlank() }?.let {
                builder.header("mcp-session-id", it)
            }
        }

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (error: IOException) {
            throw SufficitMcpException("Falha de rede ao acessar o MCP.", error)
        } catch (error: IllegalArgumentException) {
            throw SufficitMcpException("Endpoint MCP invalido.", error)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!allowEmptyBody || body.isNotBlank()) {
                classifyHttpFailure(it.code, body)
            } else if (!it.isSuccessful) {
                classifyHttpFailure(it.code, body)
            }
            return RawMcpResponse(
                statusCode = it.code,
                sessionId = it.header("mcp-session-id"),
                body = normalizeResponseBody(body)
            )
        }
    }

    private fun validateRpcResponse(body: String, statusCode: Int): JSONObject {
        classifyHttpFailure(statusCode, body)
        val response = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw SufficitMcpException("Resposta JSON-RPC invalida do MCP.", error)
        }
        val rpcError = response.optJSONObject("error")
        if (rpcError != null) {
            val code = rpcError.optInt("code")
            val message = rpcError.optString("message").ifBlank { "Erro MCP $code." }
            when (code) {
                -32601 -> throw McpMethodNotFoundException(message)
                -32000 -> throw McpSessionExpiredException(message)
                -32001 -> throw McpAuthenticationException(message)
                else -> throw SufficitMcpException(message)
            }
        }
        return response
    }

    private fun classifyHttpFailure(statusCode: Int, body: String) {
        if (statusCode in 200..299) return
        val normalizedBody = normalizeResponseBody(body)
        val rpcError = runCatching { JSONObject(normalizedBody).optJSONObject("error") }.getOrNull()
        val code = rpcError?.optInt("code")
        val message = rpcError?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: "MCP retornou HTTP $statusCode."
        when {
            code == -32601 -> throw McpMethodNotFoundException(message)
            code == -32000 -> throw McpSessionExpiredException(message)
            statusCode == 401 || code == -32001 -> throw McpAuthenticationException(message)
            else -> throw SufficitMcpException(message)
        }
    }

    private fun clearSessionLocked() {
        sessionId = null
        sessionSubject = null
        sessionInitialized = false
        catalogLoaded = false
        catalog = SufficitMcpCatalog()
    }

    private fun SufficitMcpCatalog.copyForCaller(): SufficitMcpCatalog = copy(
        tools = tools.map { it.copyForCaller() },
        prompts = prompts.map { prompt ->
            prompt.copy(arguments = prompt.arguments.map { it.copy() })
        },
        resources = resources.map { it.copy() }
    )

    private fun cursorParams(cursor: String?): JSONObject = JSONObject().apply {
        cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
    }

    private fun extractTextContent(content: JSONArray?): String = buildString {
        if (content == null) return@buildString
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("type") != "text") continue
            if (isNotEmpty()) append('\n')
            append(item.optString("text"))
        }
    }

    private fun normalizeResponseBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank() || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        val dataPayloads = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .toList()
        return dataPayloads.lastOrNull { it.startsWith("{") } ?: trimmed
    }

    private data class RawMcpResponse(
        val statusCode: Int,
        val sessionId: String?,
        val body: String
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://ai.sufficit.com.br/mcp"
        const val CLIENT_TOOL_PREFIX = "sufficit_mcp__"
        const val GENERIC_CLIENT_TOOL_PREFIX = "mcp__"
        private const val CLIENT_NAME = "sufficit-android-ai-gateway"
        private const val CLIENT_VERSION = "1.1"
        private const val JSON_RPC_VERSION = "2.0"
        private const val MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val MEMORY_CONTEXT_HEADER = "x-memory-context-id"
        private const val MEMORY_SOURCE_HEADER = "x-memory-source-id"
        private const val MAX_PAGES = 20
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun toClientToolName(mcpName: String): String = CLIENT_TOOL_PREFIX + mcpName.trim()

        fun fromClientToolName(clientToolName: String): String? {
            val normalized = clientToolName.trim()
            if (!normalized.startsWith(CLIENT_TOOL_PREFIX, ignoreCase = true)) return null
            return normalized.substring(CLIENT_TOOL_PREFIX.length).takeIf { it.isNotBlank() }
        }
    }
}

open class SufficitMcpException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private class McpSessionExpiredException(
    message: String = "A sessao MCP expirou."
) : SufficitMcpException(message)

private class McpAuthenticationException(
    message: String = "A autenticacao MCP foi rejeitada."
) : SufficitMcpException(message)

private class McpMethodNotFoundException(message: String) : SufficitMcpException(message)
