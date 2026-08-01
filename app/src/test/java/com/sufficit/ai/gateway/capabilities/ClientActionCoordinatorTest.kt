package com.sufficit.ai.gateway.capabilities

import com.sufficit.ai.gateway.agentinterface.AgentClientActionRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientActionCoordinatorTest {
    @Test
    fun synchronousActionAlwaysEndsAndPublishesItsLifecycle() {
        val observed = mutableListOf<ClientActionState>()
        val registry = ClientCapabilityRegistry(
            listOf(
                ClientCapabilityDescriptor(
                    name = "fixture",
                    description = "fixture",
                    inputSchema = JSONObject().put("type", "object"),
                    timeoutMs = 1_000L,
                    execute = {}
                )
            )
        )
        val coordinator = ClientActionCoordinator(registry, clock = { 10L }) {
            observed += it.state
        }

        val final = coordinator.submit(request("fixture", "call-1"))

        assertEquals(ClientActionState.SUCCEEDED, final.state)
        assertTrue(final.state.terminal)
        assertEquals(
            listOf(
                ClientActionState.QUEUED,
                ClientActionState.VALIDATING,
                ClientActionState.EXECUTING,
                ClientActionState.SUCCEEDED
            ),
            observed
        )
    }

    @Test
    fun asynchronousActionRemainsExecutingUntilAHandlerCompletesIt() {
        val descriptor = ClientCapabilityDescriptor(
            name = "async_fixture",
            description = "fixture",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 1_000L,
            completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
            execute = {}
        )
        val coordinator = ClientActionCoordinator(ClientCapabilityRegistry(listOf(descriptor)))

        val executing = coordinator.submit(request("async_fixture", "call-2"))
        assertEquals(ClientActionState.EXECUTING, executing.state)
        assertFalse(executing.state.terminal)

        val completed = coordinator.transition(
            "call-2",
            ClientActionState.UNVERIFIED,
            "Pacote enviado, sem confirmacao.",
            retryable = true
        )
        assertEquals(ClientActionState.UNVERIFIED, completed?.state)
        assertTrue(completed?.retryable == true)
    }

    @Test
    fun duplicateInFlightCallIdDoesNotExecuteTwice() {
        var executions = 0
        val descriptor = ClientCapabilityDescriptor(
            name = "async_fixture",
            description = "fixture",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 1_000L,
            completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
            execute = { executions++ }
        )
        val coordinator = ClientActionCoordinator(ClientCapabilityRegistry(listOf(descriptor)))

        coordinator.submit(request("async_fixture", "same-call"))
        coordinator.submit(request("async_fixture", "same-call"))

        assertEquals(1, executions)
    }

    @Test
    fun cancelActiveMarksEveryInFlightActionAsCanceled() {
        val descriptor = ClientCapabilityDescriptor(
            name = "async_fixture",
            description = "fixture",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 1_000L,
            completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
            execute = {}
        )
        val coordinator = ClientActionCoordinator(ClientCapabilityRegistry(listOf(descriptor)))
        coordinator.submit(request("async_fixture", "call-a"))
        coordinator.submit(request("async_fixture", "call-b"))

        assertEquals(2, coordinator.cancelActive())
        assertTrue(coordinator.isCanceled("call-a"))
        assertTrue(coordinator.isCanceled("call-b"))
    }

    @Test
    fun asynchronousActionTimesOutThroughTheCoordinator() {
        var timeoutAction: (() -> Unit)? = null
        val descriptor = ClientCapabilityDescriptor(
            name = "slow_fixture",
            description = "fixture",
            inputSchema = JSONObject().put("type", "object"),
            timeoutMs = 25L,
            completionMode = ClientCapabilityCompletionMode.ASYNCHRONOUS,
            execute = {}
        )
        val coordinator = ClientActionCoordinator(
            registry = ClientCapabilityRegistry(listOf(descriptor)),
            schedule = { delay, action ->
                assertEquals(25L, delay)
                timeoutAction = action
            }
        )

        coordinator.submit(request("slow_fixture", "call-timeout"))
        timeoutAction?.invoke()

        assertEquals(
            ClientActionState.TIMED_OUT,
            coordinator.current("call-timeout")?.state
        )
    }

    private fun request(tool: String, callId: String) = AgentClientActionRequest(
        tool = tool,
        callId = callId,
        arguments = JSONObject(),
        raw = JSONObject().put("tool", tool).put("callId", callId)
    )
}
