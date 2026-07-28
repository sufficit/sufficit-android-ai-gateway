package com.sufficit.ai.gateway.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawReplyEnvelopeTest {
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
}
