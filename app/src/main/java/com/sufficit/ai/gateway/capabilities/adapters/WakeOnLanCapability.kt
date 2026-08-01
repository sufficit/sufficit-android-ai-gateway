package com.sufficit.ai.gateway.capabilities.adapters

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import com.sufficit.ai.gateway.capabilities.ClientCapabilityDescriptor
import com.sufficit.ai.gateway.capabilities.ClientCapabilityCompletionMode
import com.sufficit.ai.gateway.capabilities.ClientCapabilitySensitivity
import org.json.JSONObject

object WakeOnLanCapability {
    const val NAME = "wakeonlan"

    fun descriptor(
        execute: (AgentClientActionRequest) -> Unit
    ): ClientCapabilityDescriptor = ClientCapabilityDescriptor(
        name = NAME,
        aliases = setOf("wake_on_lan", "wol"),
        description = "Liga um equipamento na rede local e verifica por ate 60 segundos se ele ficou acessivel.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("mac", stringProperty("MAC do equipamento, por exemplo AA:BB:CC:DD:EE:FF"))
                    .put("name", stringProperty("Nome amigavel opcional"))
                    .put("ip", stringProperty("Ultimo IPv4 conhecido, opcional"))
                    .put("broadcast", stringProperty("Broadcast IPv4 opcional"))
                    .put("port", integerProperty("Porta UDP preferida; 9 e 7 sao cobertas por compatibilidade"))
                    .put("repeat", integerProperty("Repeticoes de 1 a 5"))
                    .put("waitSeconds", integerProperty("Verificacao entre 5 e 60 segundos"))
            )
            .put("required", org.json.JSONArray().put("mac")),
        timeoutMs = 65_000L,
        sensitivity = ClientCapabilitySensitivity.LOCAL_NETWORK,
        completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
        validate = { request ->
            val raw = request.raw
            val mac = raw.optString("mac").trim()
                .ifBlank { raw.optString("macAddress").trim() }
                .ifBlank { raw.optString("targetMac").trim() }
                .ifBlank { request.arguments.optString("mac").trim() }
                .ifBlank { request.arguments.optString("macAddress").trim() }
                .ifBlank { request.arguments.optString("targetMac").trim() }
            if (mac.isBlank()) "A ferramenta Wake-on-LAN exige o MAC do equipamento." else null
        },
        execute = execute
    )

    private fun stringProperty(description: String): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)

    private fun integerProperty(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)
}
