package com.sufficit.ai.gateway

import com.sufficit.ai.gateway.config.GatewaySettingsStore

/**
 * Parsed and range-clamped numeric fields from [GatewaySettingsInputSnapshot].
 * Pure, context-free: no I/O, no runtime-default lookups.
 */
data class GatewaySettingsNormalizedNumbers(
    val microphoneGain: Double,
    val assistantSpeechRate: Double,
    val assistantPitch: Double,
    val voiceChannelFollowUpSeconds: Int,
    val voiceChannelIdlePromptSeconds: Int,
    val colloquialNormalizationStrength: Double,
    val transcriptionRepeatSuppression: Double,
    val vadThreshold: Double,
    val debugSpeechHoldMs: Int?,
    val debugMaxSpeechSegmentMs: Int?,
    val debugMinTranscriptionMs: Int?,
    val debugPhraseBreakSilenceMs: Int?,
    val screenHoldSeconds: Int,
    val transcriptClearTimeoutSecs: Int,
    val openClawAccumulationWindowSecs: Int
)

fun normalizeSettingsNumbers(input: GatewaySettingsInputSnapshot): GatewaySettingsNormalizedNumbers {
    return GatewaySettingsNormalizedNumbers(
        microphoneGain = (input.microphoneGainInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_MICROPHONE_GAIN).coerceIn(1.0, 6.0),
        assistantSpeechRate = (input.assistantSpeechRateInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_ASSISTANT_SPEECH_RATE).coerceIn(0.6, 1.8),
        assistantPitch = (input.assistantPitchInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_ASSISTANT_PITCH).coerceIn(0.7, 1.4),
        voiceChannelFollowUpSeconds = (input.voiceChannelFollowUpSecondsInput.toIntOrNull()
            ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS).coerceIn(3, 60),
        voiceChannelIdlePromptSeconds = (input.voiceChannelIdlePromptSecondsInput.toIntOrNull()
            ?: GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS).coerceIn(30, 3600),
        colloquialNormalizationStrength = (input.colloquialNormalizationStrengthInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH).coerceIn(0.0, 1.0),
        transcriptionRepeatSuppression = (input.transcriptionRepeatSuppressionInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION).coerceIn(0.0, 1.0),
        vadThreshold = (input.vadThresholdInput.replace(',', '.').toDoubleOrNull()
            ?: GatewaySettingsStore.DEFAULT_VAD_THRESHOLD).coerceIn(0.001, 0.2),
        debugSpeechHoldMs = input.debugSpeechHoldMsInput.trim().toIntOrNull()?.takeIf { it > 0 },
        debugMaxSpeechSegmentMs = input.debugMaxSpeechSegmentMsInput.trim().toIntOrNull()?.takeIf { it > 0 },
        debugMinTranscriptionMs = input.debugMinTranscriptionMsInput.trim().toIntOrNull()?.takeIf { it > 0 },
        debugPhraseBreakSilenceMs = input.debugPhraseBreakSilenceMsInput.trim().toIntOrNull()?.takeIf { it > 0 },
        screenHoldSeconds = (input.screenHoldSecondsInput.toIntOrNull()
            ?: GatewaySettingsStore.DEFAULT_SCREEN_HOLD_SECONDS).coerceIn(1, 120),
        transcriptClearTimeoutSecs = (input.transcriptClearTimeoutSecsInput.toIntOrNull()
            ?: GatewaySettingsStore.DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS).coerceIn(0, 300),
        openClawAccumulationWindowSecs = (input.openClawAccumulationWindowSecsInput.toIntOrNull()
            ?: GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS).coerceIn(1, 10)
    )
}
