package com.sufficit.ai.gateway.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawReplyEnvelopeTest {
    @Test
    fun legacyJsonReplyPreservesVisibleAndSpokenText() {
        val reply = parseFixture("legacy-normal-reply.json")

        assertEquals("A luz foi acesa.", reply.replyText)
        assertEquals("A luz foi acesa.", reply.spokenReplyText)
        assertTrue(reply.shouldSpeak)
        assertNull(reply.internalEvent)
        assertEquals("A luz foi acesa.", reply.canonicalReply?.content?.text)
        assertEquals(0.98, reply.confidence ?: 0.0, 0.001)
    }

    @Test
    fun detailsAndActionsRemainSeparateFromSpeech() {
        val reply = parseFixture("legacy-details-action-reply.json")

        assertEquals("O resultado tecnico deve permanecer apenas na tela.", reply.detailsText)
        assertFalse(reply.spokenReplyText.contains("resultado tecnico"))
        assertEquals(1, reply.actions.size)
        assertEquals("wakeonlan", reply.actions.single().optString("tool"))
        assertEquals("call-fixture-001", reply.actions.single().optString("callId"))
    }

    @Test
    fun jsonCompactionCannotOverrideTheLocalSpeechGuard() {
        val reply = parseFixture("legacy-internal-compaction.json")

        assertEquals(OpenClawInternalEvent.CONTEXT_COMPACTION, reply.internalEvent)
        assertEquals(
            com.sufficit.ai.gateway.agentinterface.AgentInternalEventType.CONTEXT_COMPACTION,
            reply.canonicalReply?.internalEvent
        )
        assertTrue(reply.isSystemInfo)
        assertFalse(reply.shouldSpeak)
        assertEquals("", reply.spokenReplyText)
    }

    @Test
    fun remoteErrorIsKeptOutOfTheSpokenPayload() {
        val reply = parseFixture("legacy-agent-error.json")

        assertEquals("upstream_timeout", reply.errorText)
        assertEquals("", reply.spokenReplyText)
    }

    @Test
    fun compactionWithEmojiAndMultipleLinesIsAlwaysAnInternalSystemEvent() {
        val raw = """
            🧹 Compacting context (17 messages) so I can continue without losing history…
            ✅ Context compacted (4,888 → 22,240 tokens). Continuing from where I left off.
        """.trimIndent()

        val reply = OpenClawReplyEnvelopeParser.parse(raw, uncertainPrefix = "[?]")

        assertEquals(OpenClawInternalEvent.CONTEXT_COMPACTION, reply.internalEvent)
        assertTrue(reply.isSystemInfo)
        assertFalse(reply.shouldSpeak)
        assertEquals("", reply.spokenReplyText)
        assertEquals("Contexto interno do agente compactado.", reply.replyText)
        assertTrue(reply.tags.contains("internal_context_compaction"))
    }

    @Test
    fun ordinaryReplyMentioningContextIsNotHidden() {
        val reply = OpenClawReplyEnvelopeParser.parse(
            "Vou compactar o contexto da explicacao em tres pontos.",
            uncertainPrefix = "[?]"
        )

        assertNull(reply.internalEvent)
        assertFalse(reply.isSystemInfo)
        assertTrue(reply.shouldSpeak)
    }

    private fun parseFixture(name: String): OpenClawReplyEnvelope {
        val raw = requireNotNull(javaClass.getResource("/agent-protocol/$name"))
            .readText()
        return OpenClawReplyEnvelopeParser.parse(raw, uncertainPrefix = "[?]")
    }
}
