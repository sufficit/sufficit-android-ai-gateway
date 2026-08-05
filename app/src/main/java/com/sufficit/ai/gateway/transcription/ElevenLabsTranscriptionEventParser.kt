package com.sufficit.ai.gateway.transcription

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Parser isolado para os eventos Realtime e para a resposta Scribe v2. */
internal object ElevenLabsTranscriptionEventParser {
    fun metadata(
        event: JSONObject,
        richAnalysisPerformed: Boolean = false,
        source: String = "elevenlabs_scribe_v2",
        signals: List<String> = listOf(
            TranscriptionSignal.LANGUAGE_DETECTION,
            TranscriptionSignal.LANGUAGE_PROBABILITY
        )
    ): TranscriptionAudioMetadata {
        val words = event.optJSONArray("words")
            ?: event.optJSONArray("timestamps")
            ?: JSONArray()
        val speakerIds = linkedSetOf<String>()
        val nonVerbalEvents = linkedSetOf<String>()

        for (index in 0 until words.length()) {
            val item = words.optJSONObject(index) ?: continue
            item.optString("speaker_id")
                .trim()
                .takeIf(String::isNotBlank)
                ?.let(speakerIds::add)
            if (item.optString("type").equals("audio_event", ignoreCase = true)) {
                canonicalAudioEvent(item.optString("text"))
                    ?.let(nonVerbalEvents::add)
            }
        }

        val languageCode = event.optString("language_code")
            .trim()
            .ifBlank {
                event.optJSONObject("language")?.optString("code")?.trim().orEmpty()
            }
            .ifBlank { null }
        val languageProbability = event.optionalDouble("language_probability")
            ?: event.optJSONObject("language")?.optionalDouble("probability")

        return TranscriptionAudioMetadata(
            analysisSources = listOf(source),
            availableSignals = signals,
            languageCode = languageCode,
            languageProbability = languageProbability,
            speakerIds = speakerIds.toList(),
            detectedSpeakerCount = speakerIds.size.takeIf { it > 0 },
            nonVerbalEvents = nonVerbalEvents.toList(),
            richAnalysisPerformed = richAnalysisPerformed
        )
    }

    internal fun canonicalAudioEvent(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
            .trim('[', ']', '(', ')', '<', '>')
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        if (normalized.isBlank()) return null
        return EVENT_ALIASES[normalized] ?: normalized
    }

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless(Double::isNaN)
    }

    private val EVENT_ALIASES = mapOf(
        "background_music" to "music",
        "musica" to "music",
        "musica_de_fundo" to "music",
        "background_sound" to "background_noise",
        "noise" to "background_noise",
        "ruido" to "background_noise",
        "risos" to "laughter",
        "riso" to "laughter",
        "aplausos" to "applause",
        "passos" to "footsteps",
        "tosse" to "cough",
        "espirro" to "sneeze",
        "choro" to "crying",
        "suspiro" to "sigh"
    )
}
