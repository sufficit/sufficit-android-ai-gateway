package com.sufficit.ai.gateway.capabilities

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import org.json.JSONArray
import org.json.JSONObject

enum class CapabilityAvailabilityStatus(val wireValue: String) {
    AVAILABLE("available"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    AUTHENTICATION_REQUIRED("authentication_required"),
    PERMISSION_REQUIRED("permission_required"),
    UNSUPPORTED_ON_DEVICE("unsupported_on_device"),
    DISABLED_BY_USER("disabled_by_user")
}

enum class ClientCapabilitySensitivity(val wireValue: String) {
    LOCAL_DEVICE("local_device"),
    LOCAL_NETWORK("local_network"),
    AUTHENTICATED_REMOTE("authenticated_remote"),
    USER_DATA_MUTATION("user_data_mutation")
}

enum class ClientCapabilityCompletionMode {
    SYNCHRONOUS,
    ASYNCHRONOUS
}

data class CapabilityAvailability(
    val status: CapabilityAvailabilityStatus,
    val reason: String? = null,
    val retryAfterSeconds: Long? = null
) {
    val available: Boolean get() = status == CapabilityAvailabilityStatus.AVAILABLE

    companion object {
        val Available = CapabilityAvailability(CapabilityAvailabilityStatus.AVAILABLE)
    }
}

data class ClientCapabilityDescriptor(
    val name: String,
    val aliases: Set<String> = emptySet(),
    val description: String,
    val inputSchema: JSONObject,
    val timeoutMs: Long,
    val provider: String = "android",
    val sensitivity: ClientCapabilitySensitivity = ClientCapabilitySensitivity.LOCAL_DEVICE,
    val completionMode: ClientCapabilityCompletionMode = ClientCapabilityCompletionMode.SYNCHRONOUS,
    val metadata: JSONObject = JSONObject(),
    val availability: () -> CapabilityAvailability = { CapabilityAvailability.Available },
    val validate: (AgentClientActionRequest) -> String? = { null },
    val execute: (AgentClientActionRequest) -> Unit
) {
    init {
        require(name.isNotBlank()) { "Nome canonico da capacidade vazio." }
        require(description.isNotBlank()) { "Descricao da capacidade vazia." }
        require(timeoutMs > 0L) { "Timeout da capacidade invalido." }
    }

    val allNames: Set<String> = (aliases + name).map { it.trim().lowercase() }.toSet()
}

sealed interface CapabilityDispatchResult {
    data class Dispatched(val descriptor: ClientCapabilityDescriptor) : CapabilityDispatchResult
    data class Unknown(val requestedName: String) : CapabilityDispatchResult
    data class Unavailable(
        val descriptor: ClientCapabilityDescriptor,
        val availability: CapabilityAvailability
    ) : CapabilityDispatchResult
    data class Invalid(
        val descriptor: ClientCapabilityDescriptor,
        val reason: String
    ) : CapabilityDispatchResult
    data class Failed(
        val descriptor: ClientCapabilityDescriptor,
        val error: Throwable
    ) : CapabilityDispatchResult
}

class ClientCapabilityRegistry(descriptors: List<ClientCapabilityDescriptor>) {
    private val staticDescriptors = descriptors.toList()
    private val dynamicGroups = linkedMapOf<String, List<ClientCapabilityDescriptor>>()

    @Volatile
    private var snapshot = buildSnapshot(staticDescriptors)

    fun descriptors(): List<ClientCapabilityDescriptor> = snapshot.canonical.values.toList()

    fun resolve(name: String): ClientCapabilityDescriptor? = snapshot.byName[name.trim().lowercase()]

    @Synchronized
    fun replaceDynamic(group: String, descriptors: List<ClientCapabilityDescriptor>) {
        require(group.isNotBlank()) { "Grupo dinamico vazio." }
        dynamicGroups[group.trim().lowercase()] = descriptors.toList()
        snapshot = buildSnapshot(staticDescriptors + dynamicGroups.values.flatten())
    }

    @Synchronized
    fun removeDynamic(group: String) {
        dynamicGroups.remove(group.trim().lowercase())
        snapshot = buildSnapshot(staticDescriptors + dynamicGroups.values.flatten())
    }

    fun catalog(): JSONArray = JSONArray().also(::appendCatalog)

    fun appendCatalog(target: JSONArray) {
        descriptors().forEach { descriptor ->
            val availability = descriptor.availability()
            target.put(
                JSONObject()
                    .put("tool", descriptor.name)
                    .put("description", descriptor.description)
                    .put("provider", descriptor.provider)
                    .put("sensitivity", descriptor.sensitivity.wireValue)
                    .put("completionMode", descriptor.completionMode.name.lowercase())
                    .put("inputSchema", JSONObject(descriptor.inputSchema.toString()))
                    .put("args", legacyArguments(descriptor.inputSchema))
                    .put("timeoutMs", descriptor.timeoutMs)
                    .put(
                        "availability",
                        JSONObject()
                            .put("status", availability.status.wireValue)
                            .put("reason", availability.reason ?: JSONObject.NULL)
                            .put("retryAfterSeconds", availability.retryAfterSeconds ?: JSONObject.NULL)
                    )
                    .also { item ->
                        val keys = descriptor.metadata.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            item.put(key, descriptor.metadata.opt(key))
                        }
                    }
            )
        }
    }

    fun dispatch(
        request: AgentClientActionRequest,
        beforeExecute: (ClientCapabilityDescriptor) -> Unit = {}
    ): CapabilityDispatchResult {
        val descriptor = resolve(request.tool) ?: return CapabilityDispatchResult.Unknown(request.tool)
        val availability = descriptor.availability()
        if (!availability.available) {
            return CapabilityDispatchResult.Unavailable(descriptor, availability)
        }
        descriptor.validate(request)?.let { reason ->
            return CapabilityDispatchResult.Invalid(descriptor, reason)
        }
        beforeExecute(descriptor)
        return runCatching { descriptor.execute(request) }
            .fold(
                onSuccess = { CapabilityDispatchResult.Dispatched(descriptor) },
                onFailure = { CapabilityDispatchResult.Failed(descriptor, it) }
            )
    }

    private fun legacyArguments(schema: JSONObject): JSONObject {
        val properties = schema.optJSONObject("properties") ?: return JSONObject()
        return JSONObject().apply {
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val property = properties.optJSONObject(key)
                put(key, property?.optString("description")?.takeIf(String::isNotBlank) ?: "opcional")
            }
        }
    }

    private fun buildSnapshot(
        descriptors: List<ClientCapabilityDescriptor>
    ): RegistrySnapshot {
        val canonical = linkedMapOf<String, ClientCapabilityDescriptor>()
        val byName = linkedMapOf<String, ClientCapabilityDescriptor>()
        descriptors.forEach { descriptor ->
            val canonicalName = descriptor.name.trim().lowercase()
            require(canonical.put(canonicalName, descriptor) == null) {
                "Capacidade duplicada: $canonicalName"
            }
            descriptor.allNames.forEach { name ->
                require(byName.put(name, descriptor) == null) {
                    "Alias de capacidade duplicado: $name"
                }
            }
        }
        return RegistrySnapshot(canonical, byName)
    }

    private data class RegistrySnapshot(
        val canonical: Map<String, ClientCapabilityDescriptor>,
        val byName: Map<String, ClientCapabilityDescriptor>
    )
}

fun JSONObject.toAgentClientActionRequest(): AgentClientActionRequest {
    val tool = optString("tool").trim()
        .ifBlank { optString("name").trim() }
        .ifBlank { optString("type").trim() }
    val arguments = optJSONObject("arguments") ?: JSONObject(toString()).apply {
        remove("tool")
        remove("name")
        remove("type")
        remove("callId")
        remove("call_id")
    }
    return AgentClientActionRequest(
        tool = tool,
        callId = optString("callId").trim()
            .ifBlank { optString("call_id").trim() }
            .ifBlank { null },
        arguments = arguments,
        raw = JSONObject(toString())
    )
}
