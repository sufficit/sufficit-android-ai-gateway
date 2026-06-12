package com.sufficit.openclaw.gateway.state

import com.sufficit.openclaw.gateway.GatewaySettingsInputSnapshot
import com.sufficit.openclaw.gateway.config.AssistantVoiceStyle
import com.sufficit.openclaw.gateway.config.GatewaySettings
import com.sufficit.openclaw.gateway.config.LocalExecutionMode
import com.sufficit.openclaw.gateway.config.ScreenMode
import com.sufficit.openclaw.gateway.config.TranscriptionMode
import java.io.File
import java.util.Locale

/**
 * Draft copy of the config form fields managed by MainActivity during the migration.
 *
 * Scope: UI-only, kept separate from persisted runtime settings.
 */
data class GatewaySettingsDraftState(
    val localEndpointUrl: String,
    val openClawServerAddress: String,
    val openClawGatewayToken: String,
    val openClawDeviceToken: String,
    val openClawSessionKey: String,
    val whisperUrl: String,
    val remoteModel: String,
    val whisperAuthToken: String,
    val autoStartEnabled: Boolean,
    val cameraGestureEnabled: Boolean,
    val voiceChannelSkillEnabled: Boolean,
    val voiceChannelWakeTermsInput: String,
    val voiceChannelFollowUpSecondsInput: String,
    val voiceChannelIdlePromptSecondsInput: String,
    val assistantVoiceEnabled: Boolean,
    val assistantVoiceStyle: String,
    val assistantSpeechRateInput: String,
    val assistantPitchInput: String,
    val transcriptionMode: String,
    val localModelName: String,
    val localExecutionMode: String,
    val development: Boolean,
    val microphoneAutoSensitivityEnabled: Boolean,
    val microphoneGainInput: String,
    val transcriptionRepeatSuppressionInput: String,
    val colloquialNormalizationStrengthInput: String,
    val vadThresholdInput: String,
    val debugSpeechHoldMsInput: String,
    val debugMaxSpeechSegmentMsInput: String,
    val debugMinTranscriptionMsInput: String,
    val debugPhraseBreakSilenceMsInput: String,
    val transcriptionTermsInput: String,
    val transcriptionDictionaryInput: String,
    val screenMode: String,
    val screenHoldSecondsInput: String,
    val transcriptClearTimeoutSecsInput: String,
    val openClawAccumulationWindowSecsInput: String
) {
    companion object {
        fun fromSettings(settings: GatewaySettings): GatewaySettingsDraftState {
            return GatewaySettingsDraftState(
                localEndpointUrl = settings.localEndpointUrl,
                openClawServerAddress = settings.openClawServerAddress,
                openClawGatewayToken = settings.openClawGatewayToken,
                openClawDeviceToken = settings.openClawDeviceToken,
                openClawSessionKey = settings.openClawSessionKey,
                whisperUrl = settings.whisperUrl,
                remoteModel = settings.remoteModel,
                whisperAuthToken = settings.whisperAuthToken,
                autoStartEnabled = settings.autoStartEnabled,
                cameraGestureEnabled = settings.cameraGestureEnabled,
                voiceChannelSkillEnabled = settings.voiceChannelSkillEnabled,
                voiceChannelWakeTermsInput = settings.voiceChannelWakeTerms,
                voiceChannelFollowUpSecondsInput = settings.voiceChannelFollowUpSeconds.toString(),
                voiceChannelIdlePromptSecondsInput = settings.voiceChannelIdlePromptSeconds.toString(),
                assistantVoiceEnabled = settings.assistantVoiceEnabled,
                assistantVoiceStyle = settings.assistantVoiceStyle.persistedValue,
                assistantSpeechRateInput = String.format(Locale.US, "%.2f", settings.assistantSpeechRate),
                assistantPitchInput = String.format(Locale.US, "%.2f", settings.assistantPitch),
                transcriptionMode = settings.transcriptionMode.persistedValue,
                localModelName = File(settings.localModelPath).name,
                localExecutionMode = settings.localExecutionMode.persistedValue,
                development = settings.development,
                microphoneAutoSensitivityEnabled = settings.microphoneAutoSensitivityEnabled,
                microphoneGainInput = String.format(Locale.US, "%.1f", settings.microphoneGain),
                transcriptionRepeatSuppressionInput = String.format(Locale.US, "%.2f", settings.transcriptionRepeatSuppression),
                colloquialNormalizationStrengthInput = String.format(Locale.US, "%.2f", settings.colloquialNormalizationStrength),
                vadThresholdInput = String.format(Locale.US, "%.3f", settings.vadThreshold),
                debugSpeechHoldMsInput = settings.debugSpeechHoldMs?.toString().orEmpty(),
                debugMaxSpeechSegmentMsInput = settings.debugMaxSpeechSegmentMs?.toString().orEmpty(),
                debugMinTranscriptionMsInput = settings.debugMinTranscriptionMs?.toString().orEmpty(),
                debugPhraseBreakSilenceMsInput = settings.debugPhraseBreakSilenceMs?.toString().orEmpty(),
                transcriptionTermsInput = settings.transcriptionTerms,
                transcriptionDictionaryInput = settings.transcriptionDictionary,
                screenMode = settings.screenMode.persistedValue,
                screenHoldSecondsInput = settings.screenHoldSeconds.toString(),
                transcriptClearTimeoutSecsInput = settings.transcriptClearTimeoutSecs.toString(),
                openClawAccumulationWindowSecsInput = settings.openClawAccumulationWindowSecs.toString()
            )
        }
    }

    fun toSettingsInputSnapshot(): GatewaySettingsInputSnapshot {
        return GatewaySettingsInputSnapshot(
            localEndpointUrl = localEndpointUrl,
            openClawServerAddress = openClawServerAddress,
            openClawGatewayToken = openClawGatewayToken,
            openClawDeviceToken = openClawDeviceToken,
            openClawSessionKey = openClawSessionKey,
            whisperUrl = whisperUrl,
            remoteModel = remoteModel,
            whisperAuthToken = whisperAuthToken,
            autoStartEnabled = autoStartEnabled,
            cameraGestureEnabled = cameraGestureEnabled,
            voiceChannelSkillEnabled = voiceChannelSkillEnabled,
            voiceChannelWakeTermsInput = voiceChannelWakeTermsInput,
            voiceChannelFollowUpSecondsInput = voiceChannelFollowUpSecondsInput,
            voiceChannelIdlePromptSecondsInput = voiceChannelIdlePromptSecondsInput,
            assistantVoiceEnabled = assistantVoiceEnabled,
            assistantVoiceStyleValue = assistantVoiceStyle,
            assistantSpeechRateInput = assistantSpeechRateInput,
            assistantPitchInput = assistantPitchInput,
            transcriptionModeValue = transcriptionMode,
            localModelName = localModelName,
            localExecutionModeValue = localExecutionMode,
            development = development,
            microphoneAutoSensitivityEnabled = microphoneAutoSensitivityEnabled,
            microphoneGainInput = microphoneGainInput,
            transcriptionRepeatSuppressionInput = transcriptionRepeatSuppressionInput,
            colloquialNormalizationStrengthInput = colloquialNormalizationStrengthInput,
            vadThresholdInput = vadThresholdInput,
            debugSpeechHoldMsInput = debugSpeechHoldMsInput,
            debugMaxSpeechSegmentMsInput = debugMaxSpeechSegmentMsInput,
            debugMinTranscriptionMsInput = debugMinTranscriptionMsInput,
            debugPhraseBreakSilenceMsInput = debugPhraseBreakSilenceMsInput,
            transcriptionTermsInput = transcriptionTermsInput,
            transcriptionDictionaryInput = transcriptionDictionaryInput,
            screenModeValue = screenMode,
            screenHoldSecondsInput = screenHoldSecondsInput,
            transcriptClearTimeoutSecsInput = transcriptClearTimeoutSecsInput,
            openClawAccumulationWindowSecsInput = openClawAccumulationWindowSecsInput
        )
    }

    fun withLocalModelName(value: String) = copy(localModelName = value)
}