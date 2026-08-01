package com.sufficit.ai.gateway.capabilities.adapters

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import com.sufficit.ai.gateway.capabilities.ClientCapabilityDescriptor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Definicoes canônicas das capacidades Android que nao dependem de um
 * provedor remoto. Catalogo, aliases, validacao e dispatch nascem daqui.
 */
object NativeCapabilityDescriptors {
    fun create(
        execute: (canonicalName: String, request: AgentClientActionRequest) -> Unit
    ): List<ClientCapabilityDescriptor> = listOf(
        descriptor(
            name = "photo",
            aliases = setOf("camera", "takephoto", "take_photo"),
            description = "Tira uma foto com a camera e anexa ao chat.",
            properties = JSONObject()
                .put("camera", enumProperty("Camera a usar", "front", "back"))
                .put("label", stringProperty("Legenda opcional")),
            timeoutMs = 20_000L,
            execute = execute
        ),
        descriptor(
            name = "screenshot",
            aliases = setOf("print"),
            description = "Captura a tela do aplicativo e anexa ao chat.",
            properties = JSONObject().put("label", stringProperty("Legenda opcional")),
            timeoutMs = 10_000L,
            execute = execute
        ),
        descriptor("wake", description = "Acorda a tela e retoma a escuta.", execute = execute),
        descriptor(
            "effect",
            aliases = setOf("flash"),
            description = "Dispara um aviso visual e sonoro.",
            properties = JSONObject().put("label", stringProperty("Texto do aviso")),
            execute = execute
        ),
        descriptor(
            "say",
            aliases = setOf("speak"),
            description = "Fala um texto curto pelo sintetizador do aparelho.",
            properties = JSONObject().put("text", stringProperty("Texto a falar")),
            required = listOf("text"),
            execute = execute,
            validate = { request ->
                if (request.stringArgument("text", "label").isBlank()) "Informe o texto a falar." else null
            }
        ),
        descriptor(
            "listen",
            aliases = setOf("startlistening", "start_listening"),
            description = "Inicia ou retoma a captura ambiente de nivel 2.",
            execute = execute
        ),
        descriptor(
            "stop_listening",
            aliases = setOf("stoplistening"),
            description = "Pausa a captura ambiente sem parar o monitor permanente de wake word.",
            execute = execute
        ),
        descriptor("standby", description = "Volta ao modo de espera pela wake word.", execute = execute),
        descriptor("interrupt", description = "Interrompe a fala atual do assistente.", execute = execute),
        descriptor(
            "finalize",
            aliases = setOf("finalizesegment"),
            description = "Finaliza o segmento de voz atual e solicita seu processamento.",
            execute = execute
        ),
        descriptor(
            "clear_chat",
            aliases = setOf("clearchat"),
            description = "Limpa o historico visual do chat.",
            execute = execute
        ),
        descriptor(
            "gesture",
            description = "Encaminha um gesto reconhecido ao controlador de interacao.",
            properties = JSONObject().put("gestureId", stringProperty("Identificador do gesto")),
            required = listOf("gestureId"),
            execute = execute,
            validate = { request ->
                if (request.stringArgument("gestureId", "gesture").isBlank()) "Informe o gesto." else null
            }
        ),
        descriptor(
            "config",
            aliases = setOf("editconfig", "edit_config", "settings"),
            description = "Edita configuracoes permitidas do aplicativo.",
            properties = JSONObject().put("patch", objectProperty("Alteracoes de configuracao")),
            execute = execute
        ),
        descriptor(
            "export_diagnostics",
            aliases = setOf("diagnostics_export", "doctor"),
            description = "Gera um relatorio JSON sanitizado de transporte, capacidades, MCP e ledger.",
            timeoutMs = 20_000L,
            execute = execute
        ),
        descriptor(
            "discover_wol_devices",
            aliases = setOf("discover_wakeonlan", "discover_wake_on_lan", "wol_discover", "discoverwol"),
            description = "Descobre MACs e IPs na rede local sem exigir senha do roteador.",
            properties = JSONObject().put("probe", booleanProperty("Executar sondagem ativa")),
            timeoutMs = 65_000L,
            execute = execute
        ),
        descriptor(
            "verify_wol_devices",
            aliases = setOf("verify_wakeonlan", "verify_wake_on_lan", "wol_verify"),
            description = "Envia Wake-on-LAN aos alvos e verifica quais ficaram acessiveis.",
            properties = JSONObject()
                .put("macs", arrayProperty("MACs opcionais", stringProperty("MAC")))
                .put("waitSeconds", integerProperty("Espera entre 5 e 60 segundos")),
            timeoutMs = 65_000L,
            execute = execute
        ),
        descriptor(
            "name_wol_device",
            aliases = setOf("name_wakeonlan", "remember_wol_device"),
            description = "Associa um nome a um MAC aprendido e sincroniza a preferencia com a memoria Sufficit.",
            properties = JSONObject()
                .put("mac", stringProperty("MAC ja presente no inventario"))
                .put("name", stringProperty("Nome escolhido pelo usuario")),
            required = listOf("mac", "name"),
            execute = execute,
            validate = { request ->
                when {
                    request.stringArgument("mac", "macAddress").isBlank() -> "Informe o MAC do dispositivo."
                    request.stringArgument("name", "deviceName").isBlank() -> "Informe o nome do dispositivo."
                    else -> null
                }
            }
        )
    )

    private fun descriptor(
        name: String,
        aliases: Set<String> = emptySet(),
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
        timeoutMs: Long = 15_000L,
        validate: (AgentClientActionRequest) -> String? = { null },
        execute: (String, AgentClientActionRequest) -> Unit
    ): ClientCapabilityDescriptor = ClientCapabilityDescriptor(
        name = name,
        aliases = aliases,
        description = description,
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .also { schema ->
                if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            },
        timeoutMs = timeoutMs,
        validate = validate,
        execute = { request -> execute(name, request) }
    )

    private fun stringProperty(description: String) = JSONObject()
        .put("type", "string")
        .put("description", description)

    private fun integerProperty(description: String) = JSONObject()
        .put("type", "integer")
        .put("description", description)

    private fun booleanProperty(description: String) = JSONObject()
        .put("type", "boolean")
        .put("description", description)

    private fun objectProperty(description: String) = JSONObject()
        .put("type", "object")
        .put("description", description)

    private fun enumProperty(description: String, vararg values: String) = stringProperty(description)
        .put("enum", JSONArray(values.toList()))

    private fun arrayProperty(description: String, items: JSONObject) = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", items)

    private fun AgentClientActionRequest.stringArgument(vararg names: String): String =
        names.firstNotNullOfOrNull { name ->
            arguments.optString(name).trim().takeIf { it.isNotBlank() }
                ?: raw.optString(name).trim().takeIf { it.isNotBlank() }
        }.orEmpty()
}
