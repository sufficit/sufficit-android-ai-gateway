package com.sufficit.ai.gateway.transcription

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsTranscriptionEventParserTest {
    @Test
    fun parsesLanguageSpeakersAndEnglishAudioEvents() {
        val event = JSONObject()
            .put("language_code", "por")
            .put("language_probability", 0.94)
            .put("words", JSONArray().apply {
                put(JSONObject().put("type", "word").put("text", "ola").put("speaker_id", "speaker_0"))
                put(JSONObject().put("type", "word").put("text", "oi").put("speaker_id", "speaker_1"))
                put(JSONObject().put("type", "audio_event").put("text", "[Laughter]"))
                put(JSONObject().put("type", "audio_event").put("text", "[MÚSICA DE FUNDO]"))
            })

        val metadata = ElevenLabsTranscriptionEventParser.metadata(event, richAnalysisPerformed = true)

        assertEquals("por", metadata.languageCode)
        assertEquals(0.94, metadata.languageProbability ?: 0.0, 0.001)
        assertEquals(2, metadata.speakerCount)
        assertEquals(listOf("laughter", "music"), metadata.nonVerbalEvents)
        assertEquals(listOf("elevenlabs_scribe_v2"), metadata.analysisSources)
        assertTrue(metadata.availableSignals.contains(TranscriptionSignal.LANGUAGE_DETECTION))
        assertTrue(metadata.richAnalysisPerformed)
    }

    @Test
    fun reliabilityDropsWithNoiseAndMultipleSpeakers() {
        val clean = TranscriptionReliabilityPolicy.score(
            multipleVoicesLikely = false,
            noiseScore = 0.05,
            voicedRatio = 0.65,
            languageProbability = 0.98
        )
        val difficult = TranscriptionReliabilityPolicy.score(
            multipleVoicesLikely = true,
            noiseScore = 0.80,
            voicedRatio = 0.15,
            languageProbability = 0.60
        )

        assertTrue(difficult < clean)
        assertTrue(difficult in 0.05..0.99)
    }
}
