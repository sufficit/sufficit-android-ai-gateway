package com.sufficit.openclaw.gateway

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.sufficit.openclaw.gateway.config.GatewaySettings
import java.io.File
import java.util.Locale

class GatewaySettingsState(
    localEndpointUrl: String,
    openClawServerAddress: String,
    openClawGatewayToken: String,
    openClawDeviceToken: String,
    openClawSessionKey: String,
    whisperUrl: String,
    remoteModel: String,
    whisperAuthToken: String,
    autoStartEnabled: Boolean,
    cameraGestureEnabled: Boolean,
    voiceChannelSkillEnabled: Boolean,
    voiceChannelWakeTermsInput: String,
    voiceChannelFollowUpSecondsInput: String,
    voiceChannelIdlePromptSecondsInput: String,
    assistantVoiceEnabled: Boolean,
    assistantVoiceStyle: String,
    assistantSpeechRateInput: String,
    assistantPitchInput: String,
    transcriptionMode: String,
    localModelName: String,
    localExecutionMode: String,
    development: Boolean,
    microphoneAutoSensitivityEnabled: Boolean,
    microphoneGainInput: String,
    transcriptionRepeatSuppressionInput: String,
    colloquialNormalizationStrengthInput: String,
    vadThresholdInput: String,
    debugSpeechHoldMsInput: String,
    debugMaxSpeechSegmentMsInput: String,
    debugMinTranscriptionMsInput: String,
    debugPhraseBreakSilenceMsInput: String,
    transcriptionTermsInput: String,
    transcriptionDictionaryInput: String,
    screenMode: String,
    screenHoldSecondsInput: String,
    transcriptClearTimeoutSecsInput: String,
    openClawAccumulationWindowSecsInput: String
) {
    var localEndpointUrl by mutableStateOf(localEndpointUrl)
    var openClawServerAddress by mutableStateOf(openClawServerAddress)
    var openClawGatewayToken by mutableStateOf(openClawGatewayToken)
    var openClawDeviceToken by mutableStateOf(openClawDeviceToken)
    var openClawSessionKey by mutableStateOf(openClawSessionKey)
    var whisperUrl by mutableStateOf(whisperUrl)
    var remoteModel by mutableStateOf(remoteModel)
    var whisperAuthToken by mutableStateOf(whisperAuthToken)
    var autoStartEnabled by mutableStateOf(autoStartEnabled)
    var cameraGestureEnabled by mutableStateOf(cameraGestureEnabled)
    var voiceChannelSkillEnabled by mutableStateOf(voiceChannelSkillEnabled)
    var voiceChannelWakeTermsInput by mutableStateOf(voiceChannelWakeTermsInput)
    var voiceChannelFollowUpSecondsInput by mutableStateOf(voiceChannelFollowUpSecondsInput)
    var voiceChannelIdlePromptSecondsInput by mutableStateOf(voiceChannelIdlePromptSecondsInput)
    var assistantVoiceEnabled by mutableStateOf(assistantVoiceEnabled)
    var assistantVoiceStyle by mutableStateOf(assistantVoiceStyle)
    var assistantSpeechRateInput by mutableStateOf(assistantSpeechRateInput)
    var assistantPitchInput by mutableStateOf(assistantPitchInput)
    var transcriptionMode by mutableStateOf(transcriptionMode)
    var localModelName by mutableStateOf(localModelName)
    var localExecutionMode by mutableStateOf(localExecutionMode)
    var development by mutableStateOf(development)
    var microphoneAutoSensitivityEnabled by mutableStateOf(microphoneAutoSensitivityEnabled)
    var microphoneGainInput by mutableStateOf(microphoneGainInput)
    var transcriptionRepeatSuppressionInput by mutableStateOf(transcriptionRepeatSuppressionInput)
    var colloquialNormalizationStrengthInput by mutableStateOf(colloquialNormalizationStrengthInput)
    var vadThresholdInput by mutableStateOf(vadThresholdInput)
    var debugSpeechHoldMsInput by mutableStateOf(debugSpeechHoldMsInput)
    var debugMaxSpeechSegmentMsInput by mutableStateOf(debugMaxSpeechSegmentMsInput)
    var debugMinTranscriptionMsInput by mutableStateOf(debugMinTranscriptionMsInput)
    var debugPhraseBreakSilenceMsInput by mutableStateOf(debugPhraseBreakSilenceMsInput)
    var transcriptionTermsInput by mutableStateOf(transcriptionTermsInput)
    var transcriptionDictionaryInput by mutableStateOf(transcriptionDictionaryInput)
    var screenMode by mutableStateOf(screenMode)
    var screenHoldSecondsInput by mutableStateOf(screenHoldSecondsInput)
    var transcriptClearTimeoutSecsInput by mutableStateOf(transcriptClearTimeoutSecsInput)
    var openClawAccumulationWindowSecsInput by mutableStateOf(openClawAccumulationWindowSecsInput)

    fun toSnapshot(): GatewaySettingsInputSnapshot = currentSettingsInputSnapshot(
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

    fun applyFrom(settings: GatewaySettings) {
        localEndpointUrl = settings.localEndpointUrl
        openClawServerAddress = settings.openClawServerAddress
        openClawGatewayToken = settings.openClawGatewayToken
        openClawDeviceToken = settings.openClawDeviceToken
        openClawSessionKey = settings.openClawSessionKey
        whisperUrl = settings.whisperUrl
        remoteModel = settings.remoteModel
        whisperAuthToken = settings.whisperAuthToken
        autoStartEnabled = settings.autoStartEnabled
        cameraGestureEnabled = settings.cameraGestureEnabled
        voiceChannelSkillEnabled = settings.voiceChannelSkillEnabled
        voiceChannelWakeTermsInput = settings.voiceChannelWakeTerms
        voiceChannelFollowUpSecondsInput = settings.voiceChannelFollowUpSeconds.toString()
        voiceChannelIdlePromptSecondsInput = settings.voiceChannelIdlePromptSeconds.toString()
        assistantVoiceEnabled = settings.assistantVoiceEnabled
        assistantVoiceStyle = settings.assistantVoiceStyle.persistedValue
        assistantSpeechRateInput = String.format(Locale.US, "%.2f", settings.assistantSpeechRate)
        assistantPitchInput = String.format(Locale.US, "%.2f", settings.assistantPitch)
        transcriptionMode = settings.transcriptionMode.persistedValue
        localModelName = File(settings.localModelPath).name
        localExecutionMode = settings.localExecutionMode.persistedValue
        development = settings.development
        microphoneAutoSensitivityEnabled = settings.microphoneAutoSensitivityEnabled
        microphoneGainInput = String.format(Locale.US, "%.1f", settings.microphoneGain)
        transcriptionRepeatSuppressionInput = String.format(Locale.US, "%.2f", settings.transcriptionRepeatSuppression)
        colloquialNormalizationStrengthInput = String.format(Locale.US, "%.2f", settings.colloquialNormalizationStrength)
        vadThresholdInput = String.format(Locale.US, "%.3f", settings.vadThreshold)
        debugSpeechHoldMsInput = settings.debugSpeechHoldMs?.toString().orEmpty()
        debugMaxSpeechSegmentMsInput = settings.debugMaxSpeechSegmentMs?.toString().orEmpty()
        debugMinTranscriptionMsInput = settings.debugMinTranscriptionMs?.toString().orEmpty()
        debugPhraseBreakSilenceMsInput = settings.debugPhraseBreakSilenceMs?.toString().orEmpty()
        transcriptionTermsInput = settings.transcriptionTerms
        transcriptionDictionaryInput = settings.transcriptionDictionary
        screenMode = settings.screenMode.persistedValue
        screenHoldSecondsInput = settings.screenHoldSeconds.toString()
        transcriptClearTimeoutSecsInput = settings.transcriptClearTimeoutSecs.toString()
        openClawAccumulationWindowSecsInput = settings.openClawAccumulationWindowSecs.toString()
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Saver = listSaver<GatewaySettingsState, Any?>(
            save = { s ->
                listOf(
                    s.localEndpointUrl, s.openClawServerAddress, s.openClawGatewayToken,
                    s.openClawDeviceToken, s.openClawSessionKey, s.whisperUrl,
                    s.remoteModel, s.whisperAuthToken, s.autoStartEnabled,
                    s.cameraGestureEnabled, s.voiceChannelSkillEnabled,
                    s.voiceChannelWakeTermsInput, s.voiceChannelFollowUpSecondsInput,
                    s.voiceChannelIdlePromptSecondsInput, s.assistantVoiceEnabled,
                    s.assistantVoiceStyle, s.assistantSpeechRateInput, s.assistantPitchInput,
                    s.transcriptionMode, s.localModelName, s.localExecutionMode,
                    s.development, s.microphoneAutoSensitivityEnabled, s.microphoneGainInput,
                    s.transcriptionRepeatSuppressionInput, s.colloquialNormalizationStrengthInput,
                    s.vadThresholdInput, s.debugSpeechHoldMsInput, s.debugMaxSpeechSegmentMsInput,
                    s.debugMinTranscriptionMsInput, s.debugPhraseBreakSilenceMsInput,
                    s.transcriptionTermsInput, s.transcriptionDictionaryInput,
                    s.screenMode, s.screenHoldSecondsInput, s.transcriptClearTimeoutSecsInput,
                    s.openClawAccumulationWindowSecsInput
                )
            },
            restore = { l ->
                GatewaySettingsState(
                    localEndpointUrl = l[0] as String,
                    openClawServerAddress = l[1] as String,
                    openClawGatewayToken = l[2] as String,
                    openClawDeviceToken = l[3] as String,
                    openClawSessionKey = l[4] as String,
                    whisperUrl = l[5] as String,
                    remoteModel = l[6] as String,
                    whisperAuthToken = l[7] as String,
                    autoStartEnabled = l[8] as Boolean,
                    cameraGestureEnabled = l[9] as Boolean,
                    voiceChannelSkillEnabled = l[10] as Boolean,
                    voiceChannelWakeTermsInput = l[11] as String,
                    voiceChannelFollowUpSecondsInput = l[12] as String,
                    voiceChannelIdlePromptSecondsInput = l[13] as String,
                    assistantVoiceEnabled = l[14] as Boolean,
                    assistantVoiceStyle = l[15] as String,
                    assistantSpeechRateInput = l[16] as String,
                    assistantPitchInput = l[17] as String,
                    transcriptionMode = l[18] as String,
                    localModelName = l[19] as String,
                    localExecutionMode = l[20] as String,
                    development = l[21] as Boolean,
                    microphoneAutoSensitivityEnabled = l[22] as Boolean,
                    microphoneGainInput = l[23] as String,
                    transcriptionRepeatSuppressionInput = l[24] as String,
                    colloquialNormalizationStrengthInput = l[25] as String,
                    vadThresholdInput = l[26] as String,
                    debugSpeechHoldMsInput = l[27] as String,
                    debugMaxSpeechSegmentMsInput = l[28] as String,
                    debugMinTranscriptionMsInput = l[29] as String,
                    debugPhraseBreakSilenceMsInput = l[30] as String,
                    transcriptionTermsInput = l[31] as String,
                    transcriptionDictionaryInput = l[32] as String,
                    screenMode = l[33] as String,
                    screenHoldSecondsInput = l[34] as String,
                    transcriptClearTimeoutSecsInput = l[35] as String,
                    openClawAccumulationWindowSecsInput = l[36] as String
                )
            }
        )
    }
}

@Composable
fun rememberGatewaySettingsState(initial: GatewaySettings): GatewaySettingsState {
    return rememberSaveable(saver = GatewaySettingsState.Saver) {
        GatewaySettingsState(
            localEndpointUrl = initial.localEndpointUrl,
            openClawServerAddress = initial.openClawServerAddress,
            openClawGatewayToken = initial.openClawGatewayToken,
            openClawDeviceToken = initial.openClawDeviceToken,
            openClawSessionKey = initial.openClawSessionKey,
            whisperUrl = initial.whisperUrl,
            remoteModel = initial.remoteModel,
            whisperAuthToken = initial.whisperAuthToken,
            autoStartEnabled = initial.autoStartEnabled,
            cameraGestureEnabled = initial.cameraGestureEnabled,
            voiceChannelSkillEnabled = initial.voiceChannelSkillEnabled,
            voiceChannelWakeTermsInput = initial.voiceChannelWakeTerms,
            voiceChannelFollowUpSecondsInput = initial.voiceChannelFollowUpSeconds.toString(),
            voiceChannelIdlePromptSecondsInput = initial.voiceChannelIdlePromptSeconds.toString(),
            assistantVoiceEnabled = initial.assistantVoiceEnabled,
            assistantVoiceStyle = initial.assistantVoiceStyle.persistedValue,
            assistantSpeechRateInput = String.format(Locale.US, "%.2f", initial.assistantSpeechRate),
            assistantPitchInput = String.format(Locale.US, "%.2f", initial.assistantPitch),
            transcriptionMode = initial.transcriptionMode.persistedValue,
            localModelName = File(initial.localModelPath).name,
            localExecutionMode = initial.localExecutionMode.persistedValue,
            development = initial.development,
            microphoneAutoSensitivityEnabled = initial.microphoneAutoSensitivityEnabled,
            microphoneGainInput = String.format(Locale.US, "%.1f", initial.microphoneGain),
            transcriptionRepeatSuppressionInput = String.format(Locale.US, "%.2f", initial.transcriptionRepeatSuppression),
            colloquialNormalizationStrengthInput = String.format(Locale.US, "%.2f", initial.colloquialNormalizationStrength),
            vadThresholdInput = String.format(Locale.US, "%.3f", initial.vadThreshold),
            debugSpeechHoldMsInput = initial.debugSpeechHoldMs?.toString().orEmpty(),
            debugMaxSpeechSegmentMsInput = initial.debugMaxSpeechSegmentMs?.toString().orEmpty(),
            debugMinTranscriptionMsInput = initial.debugMinTranscriptionMs?.toString().orEmpty(),
            debugPhraseBreakSilenceMsInput = initial.debugPhraseBreakSilenceMs?.toString().orEmpty(),
            transcriptionTermsInput = initial.transcriptionTerms,
            transcriptionDictionaryInput = initial.transcriptionDictionary,
            screenMode = initial.screenMode.persistedValue,
            screenHoldSecondsInput = initial.screenHoldSeconds.toString(),
            transcriptClearTimeoutSecsInput = initial.transcriptClearTimeoutSecs.toString(),
            openClawAccumulationWindowSecsInput = initial.openClawAccumulationWindowSecs.toString()
        )
    }
}
