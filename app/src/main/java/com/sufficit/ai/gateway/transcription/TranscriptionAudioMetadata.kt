package com.sufficit.ai.gateway.transcription

import kotlin.math.max

/**
 * Metadados acusticos associados a um trecho transcrito.
 *
 * [reliabilityScore] e uma heuristica do gateway, nao uma probabilidade
 * produzida pelo modelo de transcricao. Os nomes de eventos sao mantidos em
 * ingles para formar um contrato estavel com qualquer agente remoto.
 */
data class TranscriptionAudioMetadata(
    /** Backends/etapas que contribuíram para este envelope. */
    val analysisSources: List<String> = emptyList(),
    /** Sinais efetivamente disponíveis, não promessas do provedor. */
    val availableSignals: List<String> = emptyList(),
    val languageCode: String? = null,
    val languageProbability: Double? = null,
    val speakerIds: List<String> = emptyList(),
    val detectedSpeakerCount: Int? = null,
    val nonVerbalEvents: List<String> = emptyList(),
    val noiseScore: Double? = null,
    val reliabilityScore: Double? = null,
    val reliabilitySource: String? = null,
    val richAnalysisPerformed: Boolean = false
) {
    val speakerCount: Int?
        get() = maxOf(
            detectedSpeakerCount ?: 0,
            speakerIds.distinct().size
        ).takeIf { it > 0 }

    fun merge(other: TranscriptionAudioMetadata): TranscriptionAudioMetadata =
        TranscriptionAudioMetadata(
            analysisSources = (analysisSources + other.analysisSources).normalizedDistinct(),
            availableSignals = (availableSignals + other.availableSignals).normalizedDistinct(),
            languageCode = other.languageCode ?: languageCode,
            languageProbability = other.languageProbability ?: languageProbability,
            speakerIds = (speakerIds + other.speakerIds).normalizedDistinct(),
            detectedSpeakerCount = maxIntNullable(detectedSpeakerCount, other.detectedSpeakerCount),
            nonVerbalEvents = (nonVerbalEvents + other.nonVerbalEvents).normalizedDistinct(),
            noiseScore = maxNullable(noiseScore, other.noiseScore),
            reliabilityScore = other.reliabilityScore ?: reliabilityScore,
            reliabilitySource = other.reliabilitySource ?: reliabilitySource,
            richAnalysisPerformed = richAnalysisPerformed || other.richAnalysisPerformed
        )

    private fun maxNullable(first: Double?, second: Double?): Double? = when {
        first == null -> second
        second == null -> first
        else -> max(first, second)
    }

    private fun maxIntNullable(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun List<String>.normalizedDistinct(): List<String> =
        map(String::trim).filter(String::isNotBlank).distinct()
}

/** Nomes estaveis para o contrato enviado ao OpenClaw. */
object TranscriptionSignal {
    const val LANGUAGE_DETECTION = "language_detection"
    const val LANGUAGE_PROBABILITY = "language_probability"
    const val WORD_TIMESTAMPS = "word_timestamps"
    const val SPEAKER_DIARIZATION = "speaker_diarization"
    const val AUDIO_EVENTS = "audio_events"
    const val PARTIAL_TRANSCRIPT = "partial_transcript"
    const val LOCAL_VOICE_HEURISTICS = "local_voice_heuristics"
    const val LOCAL_NOISE_HEURISTIC = "local_noise_heuristic"
}

/**
 * Converte sinais locais/remotos em uma confiabilidade operacional para o
 * pre-agente. Nao tenta representar a confianca linguistica do Scribe.
 */
object TranscriptionReliabilityPolicy {
    fun score(
        multipleVoicesLikely: Boolean,
        noiseScore: Double?,
        voicedRatio: Double?,
        languageProbability: Double?
    ): Double {
        var score = 0.96
        if (multipleVoicesLikely) score -= 0.24
        score -= (noiseScore ?: 0.0).coerceIn(0.0, 1.0) * 0.34

        val voiced = voicedRatio?.coerceIn(0.0, 1.0)
        if (voiced != null && voiced < 0.25) {
            score -= (0.25 - voiced) * 0.48
        }

        // Probabilidade de idioma e apenas um sinal auxiliar. Ela nunca vira
        // diretamente a confianca da transcricao.
        val language = languageProbability?.coerceIn(0.0, 1.0)
        if (language != null && language < 0.75) {
            score -= (0.75 - language) * 0.20
        }
        return score.coerceIn(0.05, 0.99)
    }
}
