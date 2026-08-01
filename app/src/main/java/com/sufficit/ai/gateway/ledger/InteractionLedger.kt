package com.sufficit.ai.gateway.ledger

import android.content.Context
import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import com.sufficit.ai.gateway.agentinterface.AgentTurnEnvelope
import com.sufficit.ai.gateway.capabilities.ClientActionState
import com.sufficit.ai.gateway.capabilities.ClientActionUpdate
import com.sufficit.ai.gateway.runtime.ChatAgentActivityState
import com.sufficit.ai.gateway.runtime.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class LedgerRecoveryResult(
    val interruptedTurns: Int,
    val interruptedActions: Int
)

class InteractionLedger(context: Context) {
    private val dao = InteractionLedgerDatabase.get(context).ledgerDao()

    fun beginTurn(
        turn: AgentTurnEnvelope,
        sources: List<Pair<Long, String>>
    ): Boolean {
        val now = System.currentTimeMillis()
        val created = dao.createTurn(
            InteractionTurnEntity(
                turnId = turn.turnId,
                inputMode = turn.interaction.inputMode.wireValue,
                awakened = turn.interaction.awakened,
                wakeWord = turn.interaction.wakeWord,
                textHash = LedgerSanitizer.sha256(turn.text),
                state = "queued",
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            sources.mapIndexed { index, source ->
                InteractionSourceEntity(
                    turnId = turn.turnId,
                    messageId = source.first,
                    position = index,
                    textHash = LedgerSanitizer.sha256(source.second)
                )
            }
        )
        if (created) {
            event(turn.turnId, null, "turn", "queued", "Turno criado.")
        }
        return created
    }

    /** Importa somente correlacoes uteis do JSON legado, nunca o texto bruto. */
    fun importLegacyChat(messages: List<ChatMessage>): Int {
        var imported = 0
        messages.filter { it.deliverySourceMessageIds.isNotEmpty() }.forEach { message ->
            val turnId = "legacy-chat-${message.id}"
            val state = when (message.agentActivityState) {
                ChatAgentActivityState.FAILED -> "failed"
                null -> "completed"
                else -> "interrupted"
            }
            val created = dao.createTurn(
                InteractionTurnEntity(
                    turnId = turnId,
                    inputMode = "unknown",
                    awakened = false,
                    wakeWord = null,
                    textHash = LedgerSanitizer.sha256(
                        message.deliverySourceTexts.joinToString(" ")
                    ),
                    state = state,
                    createdAtEpochMs = message.atEpochMs,
                    updatedAtEpochMs = message.agentActivityUpdatedAtEpochMs ?: message.atEpochMs
                ),
                message.deliverySourceMessageIds.mapIndexed { index, sourceId ->
                    InteractionSourceEntity(
                        turnId = turnId,
                        messageId = sourceId,
                        position = index,
                        textHash = LedgerSanitizer.sha256(
                            message.deliverySourceTexts.getOrNull(index).orEmpty()
                        )
                    )
                }
            )
            if (created) imported++
        }
        return imported
    }

    fun transitionTurn(turnId: String, state: String, summary: String, details: JSONObject? = null) {
        val now = System.currentTimeMillis()
        dao.updateTurnState(turnId, state, now)
        event(turnId, null, "turn", state, summary, details)
    }

    fun recordDelivery(
        turnId: String,
        state: String,
        deliveryId: String? = null,
        receipt: String? = null,
        error: String? = null
    ): RemoteDeliveryEntity {
        val now = System.currentTimeMillis()
        val existing = deliveryId?.let(dao::findDelivery)
        val attempt = existing?.attempt ?: (dao.maxDeliveryAttempt(turnId) + 1)
        val delivery = RemoteDeliveryEntity(
            deliveryId = existing?.deliveryId ?: UUID.randomUUID().toString(),
            turnId = turnId,
            transport = "remote_agent",
            attempt = attempt,
            state = state,
            receipt = receipt?.let(LedgerSanitizer::safeSummary),
            error = error?.let(LedgerSanitizer::safeSummary),
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now
        )
        dao.upsertDelivery(delivery)
        event(turnId, null, "delivery", state, "Entrega remota: $state.")
        return delivery
    }

    fun reserveAction(
        request: AgentClientActionRequest,
        turnId: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        val callId = requireNotNull(request.callId) { "callId obrigatorio para reservar uma acao." }
        val inserted = dao.insertAction(
            ClientActionCallEntity(
                callId = callId,
                turnId = turnId,
                tool = request.tool,
                argumentsHash = LedgerSanitizer.sha256(
                    LedgerSanitizer.canonicalJson(request.arguments)
                ),
                state = "queued",
                summary = "Acao reservada para execucao.",
                retryable = false,
                error = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        ) != -1L
        if (inserted) {
            event(turnId, callId, "client_action", "queued", "Acao reservada para execucao.")
        }
        return inserted
    }

    fun findAction(callId: String): ClientActionCallEntity? = dao.findAction(callId)

    fun transitionAction(update: ClientActionUpdate) {
        val existing = dao.findAction(update.callId) ?: return
        dao.updateAction(
            callId = update.callId,
            state = update.state.name.lowercase(),
            summary = LedgerSanitizer.safeSummary(update.summary),
            retryable = update.retryable,
            error = update.error?.let(LedgerSanitizer::safeSummary),
            atEpochMs = update.atEpochMs
        )
        event(
            existing.turnId,
            update.callId,
            "client_action",
            update.state.name.lowercase(),
            update.summary
        )
    }

    fun recoverInterrupted(): LedgerRecoveryResult {
        val now = System.currentTimeMillis()
        val turns = dao.openTurns()
        turns.forEach { turn ->
            dao.updateTurnState(turn.turnId, "interrupted", now)
            event(
                turn.turnId,
                null,
                "recovery",
                "interrupted",
                "Turno encerrado apos reinicio do processo."
            )
        }
        val actions = dao.openActions()
        actions.forEach { action ->
            dao.updateAction(
                action.callId,
                ClientActionState.CANCELED.name.lowercase(),
                "Acao interrompida pelo reinicio do aplicativo.",
                true,
                "process_restarted",
                now
            )
            event(
                action.turnId,
                action.callId,
                "recovery",
                "canceled",
                "Acao encerrada apos reinicio do processo."
            )
        }
        prune(now)
        return LedgerRecoveryResult(turns.size, actions.size)
    }

    fun recentEvents(limit: Int = 200): List<InteractionEventEntity> =
        dao.recentEvents(limit.coerceIn(1, 2_000))

    private fun event(
        turnId: String?,
        callId: String?,
        category: String,
        state: String,
        summary: String,
        details: JSONObject? = null
    ) {
        dao.insertEvent(
            InteractionEventEntity(
                turnId = turnId,
                callId = callId,
                category = category,
                state = state,
                summary = LedgerSanitizer.safeSummary(summary),
                detailsJson = details?.let(LedgerSanitizer::sanitizeJson)?.toString(),
                atEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun prune(now: Long) {
        dao.deleteEventsOlderThan(now - EVENT_RETENTION_MS)
        dao.trimEventsToCount(EVENT_MAX_COUNT)
    }

    companion object {
        private const val EVENT_RETENTION_MS = 14L * 24L * 60L * 60L * 1_000L
        private const val EVENT_MAX_COUNT = 10_000
    }
}

object LedgerSanitizer {
    private val sensitiveKey = Regex(
        "(?i)(token|authorization|password|secret|credential|cookie|bearer|api[_-]?key)"
    )
    private val bearerValue = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+=*")

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun safeSummary(value: String): String = bearerValue
        .replace(value.trim(), "Bearer [REDACTED]")
        .take(800)

    fun sanitizeJson(value: JSONObject): JSONObject = JSONObject().also { output ->
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            output.put(
                key,
                if (sensitiveKey.containsMatchIn(key)) "[REDACTED]" else sanitizeValue(value.opt(key))
            )
        }
    }

    fun canonicalJson(value: JSONObject): String = canonicalValue(value).toString()

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> sanitizeJson(value)
        is JSONArray -> JSONArray().also { array ->
            for (index in 0 until value.length()) array.put(sanitizeValue(value.opt(index)))
        }
        is String -> safeSummary(value)
        else -> value
    }

    private fun canonicalValue(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { output ->
            value.keys().asSequence().toList().sorted().forEach { key ->
                output.put(key, canonicalValue(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().also { output ->
            for (index in 0 until value.length()) output.put(canonicalValue(value.opt(index)))
        }
        else -> value
    }
}
