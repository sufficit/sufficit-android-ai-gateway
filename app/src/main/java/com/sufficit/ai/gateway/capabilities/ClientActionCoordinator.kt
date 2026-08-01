package com.sufficit.ai.gateway.capabilities

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ClientActionState(val terminal: Boolean) {
    QUEUED(false),
    VALIDATING(false),
    WAITING_PERMISSION(false),
    EXECUTING(false),
    VERIFYING(false),
    SUCCEEDED(true),
    UNVERIFIED(true),
    FAILED(true),
    TIMED_OUT(true),
    CANCELED(true),
    DENIED(true)
}

data class ClientActionUpdate(
    val callId: String,
    val tool: String,
    val state: ClientActionState,
    val summary: String,
    val retryable: Boolean = false,
    val error: String? = null,
    val atEpochMs: Long
)

/**
 * Dono unico do ciclo de vida das acoes solicitadas pelo agente. O registro
 * valida e despacha; o coordenador correlaciona pelo callId e publica estados.
 */
class ClientActionCoordinator(
    private val registry: ClientCapabilityRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = DEFAULT_SCHEDULER,
    private val onUpdate: (ClientActionUpdate) -> Unit = {}
) {
    private val updates = ConcurrentHashMap<String, ClientActionUpdate>()

    fun submit(request: AgentClientActionRequest): ClientActionUpdate {
        val correlated = request.copy(callId = request.callId ?: UUID.randomUUID().toString())
        val callId = requireNotNull(correlated.callId)
        updates[callId]?.let { existing ->
            if (!existing.state.terminal) return existing
        }
        publish(correlated, ClientActionState.QUEUED, "Acao recebida.")
        publish(correlated, ClientActionState.VALIDATING, "Validando ${correlated.tool}.")
        var executingUpdate: ClientActionUpdate? = null
        return when (val result = registry.dispatch(correlated) { descriptor ->
            executingUpdate = publish(
                correlated,
                ClientActionState.EXECUTING,
                "Executando ${descriptor.name}."
            )
            if (descriptor.completionMode == ClientCapabilityCompletionMode.ASYNCHRONOUS) {
                schedule(descriptor.timeoutMs) {
                    transition(
                        callId = callId,
                        state = ClientActionState.TIMED_OUT,
                        summary = "${descriptor.name} excedeu o tempo limite.",
                        retryable = true,
                        error = "timeout"
                    )
                }
            }
        }) {
            is CapabilityDispatchResult.Dispatched -> if (
                result.descriptor.completionMode == ClientCapabilityCompletionMode.SYNCHRONOUS
            ) {
                publish(
                    correlated,
                    ClientActionState.SUCCEEDED,
                    "${result.descriptor.name} executada."
                )
            } else {
                current(callId) ?: requireNotNull(executingUpdate)
            }
            is CapabilityDispatchResult.Invalid -> publish(
                correlated,
                ClientActionState.FAILED,
                result.reason,
                error = result.reason
            )
            is CapabilityDispatchResult.Unavailable -> {
                val reason = result.availability.reason
                    ?: "Capacidade ${result.availability.status.wireValue}."
                val state = when (result.availability.status) {
                    CapabilityAvailabilityStatus.PERMISSION_REQUIRED -> ClientActionState.WAITING_PERMISSION
                    CapabilityAvailabilityStatus.DISABLED_BY_USER -> ClientActionState.DENIED
                    else -> ClientActionState.FAILED
                }
                publish(
                    correlated,
                    state,
                    reason,
                    retryable = result.availability.retryAfterSeconds != null,
                    error = reason
                )
            }
            is CapabilityDispatchResult.Failed -> {
                val reason = result.error.message ?: "Falha desconhecida."
                publish(correlated, ClientActionState.FAILED, reason, error = reason)
            }
            is CapabilityDispatchResult.Unknown -> publish(
                correlated,
                ClientActionState.FAILED,
                "Capacidade desconhecida: ${result.requestedName}.",
                error = "unknown_capability"
            )
        }
    }

    fun transition(
        callId: String,
        state: ClientActionState,
        summary: String,
        retryable: Boolean = false,
        error: String? = null
    ): ClientActionUpdate? {
        val current = updates[callId] ?: return null
        if (current.state.terminal) return current
        val update = ClientActionUpdate(
            callId = callId,
            tool = current.tool,
            state = state,
            summary = summary,
            retryable = retryable,
            error = error,
            atEpochMs = clock()
        )
        updates[callId] = update
        onUpdate(update)
        return update
    }

    fun current(callId: String): ClientActionUpdate? = updates[callId]

    fun isCanceled(callId: String): Boolean =
        updates[callId]?.state == ClientActionState.CANCELED

    fun cancel(callId: String, reason: String = "Acao cancelada pelo usuario."): ClientActionUpdate? =
        transition(
            callId = callId,
            state = ClientActionState.CANCELED,
            summary = reason,
            retryable = true,
            error = "canceled"
        )

    fun cancelActive(reason: String = "Acoes interrompidas pelo usuario."): Int {
        val active = updates.values.filter { !it.state.terminal }
        active.forEach { cancel(it.callId, reason) }
        return active.size
    }

    private fun publish(
        request: AgentClientActionRequest,
        state: ClientActionState,
        summary: String,
        retryable: Boolean = false,
        error: String? = null
    ): ClientActionUpdate {
        val update = ClientActionUpdate(
            callId = requireNotNull(request.callId),
            tool = request.tool,
            state = state,
            summary = summary,
            retryable = retryable,
            error = error,
            atEpochMs = clock()
        )
        updates[update.callId] = update
        onUpdate(update)
        return update
    }

    companion object {
        private val TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "client-action-timeouts").apply { isDaemon = true }
        }
        private val DEFAULT_SCHEDULER: (Long, () -> Unit) -> Unit = { delayMs, action ->
            TIMEOUT_EXECUTOR.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }
    }
}
