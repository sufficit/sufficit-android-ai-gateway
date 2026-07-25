package com.sufficit.ai.gateway.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayChatRuntimeTest {

    @Before
    fun setUp() {
        GatewayChatRuntime.clearChat()
    }

    @After
    fun tearDown() {
        GatewayChatRuntime.clearChat()
    }

    @Test
    fun activityFollowsEveryGroupedUserMessageAndPersistsFailure() {
        val firstUserId = GatewayChatRuntime.appendChatMessage(ChatRole.USER, "primeiro trecho")
        val activityId = GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = 0L,
            dispatchedText = "primeiro trecho",
            state = ChatAgentActivityState.QUEUED,
            statusText = "Preparando"
        )
        val secondUserId = GatewayChatRuntime.appendChatMessage(ChatRole.USER, "segundo trecho")

        val reusedId = GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = activityId,
            dispatchedText = "primeiro trecho segundo trecho",
            state = ChatAgentActivityState.PROCESSING,
            statusText = "Processando"
        )

        assertEquals(activityId, reusedId)
        val active = GatewayChatRuntime.chatMessages().value.last()
        assertEquals(activityId, active.id)
        assertEquals(ChatAgentActivityState.PROCESSING, active.agentActivityState)
        assertEquals(listOf(firstUserId, secondUserId), active.deliverySourceMessageIds)

        assertTrue(
            GatewayChatRuntime.failAgentActivityMessage(
                activityId,
                "Tempo esgotado aguardando resposta."
            )
        )
        val failed = GatewayChatRuntime.chatMessages().value.last()
        assertEquals(activityId, failed.id)
        assertEquals(ChatAgentActivityState.FAILED, failed.agentActivityState)
        assertTrue(failed.text.contains("Tempo esgotado"))
    }

    @Test
    fun provisionalActivityIsRemovedOnlyAfterDefinitiveAssistantMessageExists() {
        GatewayChatRuntime.appendChatMessage(ChatRole.USER, "faça a ação")
        val activityId = GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = 0L,
            dispatchedText = "faça a ação",
            state = ChatAgentActivityState.PROCESSING,
            statusText = "Processando"
        )
        val replyId = GatewayChatRuntime.appendChatMessage(
            ChatRole.ASSISTANT,
            "A ação foi concluída."
        )

        GatewayChatRuntime.removeAgentActivityMessage(activityId)

        val messages = GatewayChatRuntime.chatMessages().value
        assertTrue(messages.none { it.id == activityId })
        assertEquals(replyId, messages.last().id)
        assertEquals(ChatRole.ASSISTANT, messages.last().role)
    }

    @Test
    fun aNewQueuedTurnDoesNotReplaceTheTurnAlreadyProcessing() {
        GatewayChatRuntime.appendChatMessage(ChatRole.USER, "ligue o computador")
        val firstActivityId = GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = 0L,
            dispatchedText = "ligue o computador",
            state = ChatAgentActivityState.QUEUED,
            statusText = "Preparando"
        )
        GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = firstActivityId,
            dispatchedText = "ligue o computador",
            state = ChatAgentActivityState.PROCESSING,
            statusText = "Processando"
        )

        GatewayChatRuntime.appendChatMessage(ChatRole.USER, "ligue o computador")
        val secondActivityId = GatewayChatRuntime.upsertAgentActivityMessage(
            existingId = firstActivityId,
            dispatchedText = "ligue o computador",
            state = ChatAgentActivityState.QUEUED,
            statusText = "Preparando"
        )

        assertTrue(firstActivityId != secondActivityId)
        assertEquals(
            firstActivityId,
            GatewayChatRuntime.findAgentActivityMessageId("ligue o computador")
        )
        assertEquals(secondActivityId, GatewayChatRuntime.chatMessages().value.last().id)
    }
}
