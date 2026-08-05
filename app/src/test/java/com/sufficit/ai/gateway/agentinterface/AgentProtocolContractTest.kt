package com.sufficit.ai.gateway.agentinterface

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolContractTest {
    @Test
    fun awakenedTurnKeepsOnlyTheCanonicalWakeWordSignals() {
        val fixture = fixture("turn-awakened.json")
        val interaction = fixture.getJSONObject("interaction")
        val turn = AgentTurnEnvelope(
            turnId = fixture.getString("turnId"),
            text = fixture.getString("text"),
            interaction = AgentChannelContext(
                inputMode = AgentInputMode.fromWireValue(interaction.getString("inputMode")),
                awakened = interaction.getBoolean("awakened"),
                wakeWord = interaction.getString("wakeWord")
            )
        )

        val encoded = AgentProtocolCodec.encodeTurn(turn)
        val encodedInteraction = encoded.getJSONObject("interaction")

        assertTrue(encodedInteraction.getBoolean("awakened"))
        assertEquals("xuxu", encodedInteraction.getString("wakeWord"))
        assertFalse(encodedInteraction.has("wakeWordSessionActive"))
        assertFalse(encodedInteraction.has("directAddress"))
        assertEquals("android_mobile_chat", encodedInteraction.getString("surface"))
    }

    @Test
    fun voiceTurnCarriesAcousticAnalysisWithoutChangingWakeContract() {
        val encoded = AgentProtocolCodec.encodeTurn(
            AgentTurnEnvelope(
                turnId = "turn-audio-analysis",
                text = "Acenda a luz.",
                interaction = AgentChannelContext(
                    inputMode = AgentInputMode.VOICE,
                    awakened = true,
                    wakeWord = "xuxu",
                    multipleVoicesLikely = true,
                    detectedSpeakerCount = 2,
                    nonVerbalAudioEvents = listOf("music", "laughter"),
                    transcriptionReliabilityScore = 0.61,
                    transcriptionNoiseScore = 0.48,
                    transcriptionLanguageCode = "por",
                    transcriptionLanguageProbability = 0.97,
                    transcriptionAnalysisSources = listOf("elevenlabs_scribe_v2_realtime"),
                    transcriptionAvailableSignals = listOf("word_timestamps", "audio_events")
                )
            )
        )
        val interaction = encoded.getJSONObject("interaction")

        assertEquals(2, interaction.getInt("detectedSpeakerCount"))
        assertEquals("music", interaction.getJSONArray("nonVerbalAudioEvents").getString(0))
        assertEquals(0.61, interaction.getDouble("transcriptionReliabilityScore"), 0.001)
        assertEquals("xuxu", interaction.getString("wakeWord"))
        assertEquals(
            "elevenlabs_scribe_v2_realtime",
            interaction.getJSONArray("transcriptionAnalysisSources").getString(0)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun awakenedTurnRequiresTheDetectedWakeWord() {
        AgentChannelContext(
            inputMode = AgentInputMode.VOICE,
            awakened = true,
            wakeWord = null
        )
    }

    @Test
    fun canonicalReplySeparatesSpeechDetailsAndAttachment() {
        val reply = AgentProtocolCodec.decodeReply(fixture("canonical-reply-with-attachment.json"))
        val presentation = MobilePresentationPolicy.present(reply)

        assertEquals("Preparei o relatorio.", presentation.text)
        assertEquals("Preparei o relatorio.", presentation.speech)
        assertEquals("Os detalhes tecnicos permanecem apenas na tela.", presentation.details)
        assertEquals("relatorio.md", presentation.attachments.single().name)
        assertFalse(presentation.speech.contains("detalhes tecnicos"))
        assertTrue(presentation.shouldRenderAsReply)
    }

    @Test
    fun internalEventIsNeitherVisibleNorSpeakable() {
        val reply = AgentProtocolCodec.decodeReply(fixture("canonical-internal-event.json"))
        val event = RemoteAgentEvent.Reply(reply)
        val presentation = MobilePresentationPolicy.present(reply)

        assertEquals(AgentInternalEventType.CONTEXT_COMPACTION, reply.internalEvent)
        assertFalse(presentation.shouldRenderAsReply)
        assertEquals("", presentation.speech)
        assertFalse(InternalEventFilter.isSpeakable(event))
    }

    @Test
    fun ordinaryConversationAboutMemoryIsNotClassifiedAsInternal() {
        val reply = AgentProtocolCodec.decodeReply(
            JSONObject()
                .put("schemaVersion", 1)
                .put("type", "reply")
                .put(
                    "message",
                    JSONObject().put("text", "Sua memoria ajuda a lembrar o nome do dispositivo.")
                )
        )

        assertNull(reply.internalEvent)
        assertTrue(MobilePresentationPolicy.present(reply).shouldRenderAsReply)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedProtocolVersionFailsExplicitly() {
        AgentProtocolCodec.decodeReply(
            JSONObject()
                .put("schemaVersion", AgentProtocolVersion.CURRENT + 1)
                .put("message", JSONObject().put("text", "incompativel"))
        )
    }

    private fun fixture(name: String): JSONObject {
        val raw = requireNotNull(javaClass.getResource("/agent-protocol/$name")).readText()
        return JSONObject(raw)
    }
}
