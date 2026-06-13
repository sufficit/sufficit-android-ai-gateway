package com.sufficit.ai.gateway

import androidx.compose.runtime.Composable
import com.sufficit.ai.gateway.config.DeviceModelGuide
import com.sufficit.ai.gateway.history.TranscriptHistorySnapshot

data class ConfigPageState(
    val hasPermission: Boolean,
    val hasCameraPermission: Boolean,
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
    val cameraGestureStatus: String,
    val development: Boolean,
    val voiceChannelSkillEnabled: Boolean,
    val voiceChannelWakeTermsInput: String,
    val voiceChannelFollowUpSecondsInput: String,
    val voiceChannelIdlePromptSecondsInput: String,
    val assistantVoiceEnabled: Boolean,
    val assistantVoiceStyle: String,
    val assistantSpeechRateInput: String,
    val assistantPitchInput: String,
    val transcriptionMode: String,
    val deviceGuide: DeviceModelGuide?,
    val deviceModelLabel: String,
    val localModelName: String,
    val localModelExists: Boolean,
    val huggingFaceModelExists: Boolean?,
    val huggingFaceCheckInProgress: Boolean,
    val localModelDownloadInProgress: Boolean,
    val localModelDownloadStatus: String,
    val localModelDownloadProgress: Float,
    val localModelDownloadProgressLabel: String,
    val localModelOptions: List<LocalModelOption>,
    val localModelOptionsLoading: Boolean,
    val selectedModelInvalid: Boolean,
    val shouldOfferDownload: Boolean,
    val localExecutionMode: String,
    val localSystemInfo: String,
    val microphoneAutoSensitivityEnabled: Boolean,
    val microphoneGainInput: String,
    val transcriptionRepeatSuppressionInput: String,
    val colloquialNormalizationStrengthInput: String,
    val vadThresholdInput: String,
    val debugSpeechHoldMsInput: String,
    val debugMaxSpeechSegmentMsInput: String,
    val debugMinTranscriptionMsInput: String,
    val debugPhraseBreakSilenceMsInput: String,
    val screenMode: String,
    val screenHoldSecondsInput: String,
    val transcriptClearTimeoutSecsInput: String,
    val openClawAccumulationWindowSecsInput: String,
    val historySnapshot: TranscriptHistorySnapshot,
    val historyActionStatus: String,
    val settingsBackupStatus: String
)

data class ConfigPageActions(
    val onLocalEndpointChange: (String) -> Unit,
    val onOpenClawServerAddressChange: (String) -> Unit,
    val onOpenClawGatewayTokenChange: (String) -> Unit,
    val onOpenClawDeviceTokenChange: (String) -> Unit,
    val onOpenClawSessionKeyChange: (String) -> Unit,
    val onWhisperUrlChange: (String) -> Unit,
    val onRemoteModelChange: (String) -> Unit,
    val onWhisperAuthTokenChange: (String) -> Unit,
    val onAutoStartEnabledChange: (Boolean) -> Unit,
    val onCameraGestureEnabledChange: (Boolean) -> Unit,
    val onDevelopmentChange: (Boolean) -> Unit,
    val onVoiceChannelSkillEnabledChange: (Boolean) -> Unit,
    val onVoiceChannelWakeTermsChange: (String) -> Unit,
    val onVoiceChannelFollowUpSecondsChange: (String) -> Unit,
    val onVoiceChannelIdlePromptSecondsChange: (String) -> Unit,
    val onOpenClawAccumulationWindowSecsChange: (String) -> Unit,
    val onAssistantVoiceEnabledChange: (Boolean) -> Unit,
    val onAssistantVoiceStyleChange: (String) -> Unit,
    val onAssistantSpeechRateChange: (String) -> Unit,
    val onAssistantPitchChange: (String) -> Unit,
    val onTranscriptionModeChange: (String) -> Unit,
    val onLocalModelNameChange: (String) -> Unit,
    val onLocalExecutionModeChange: (String) -> Unit,
    val onMicrophoneAutoSensitivityEnabledChange: (Boolean) -> Unit,
    val onMicrophoneGainChange: (String) -> Unit,
    val onTranscriptionRepeatSuppressionChange: (String) -> Unit,
    val onColloquialNormalizationStrengthChange: (String) -> Unit,
    val onVadThresholdChange: (String) -> Unit,
    val onDebugSpeechHoldMsChange: (String) -> Unit,
    val onDebugMaxSpeechSegmentMsChange: (String) -> Unit,
    val onDebugMinTranscriptionMsChange: (String) -> Unit,
    val onDebugPhraseBreakSilenceMsChange: (String) -> Unit,
    val onScreenModeChange: (String) -> Unit,
    val onScreenHoldSecondsChange: (String) -> Unit,
    val onTranscriptClearTimeoutSecsChange: (String) -> Unit,
    val onOpenGestureDebug: () -> Unit,
    val onDownloadLocalModel: () -> Unit,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onExportHistory: () -> Unit,
    val onClearHistory: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onRequestCameraPermission: () -> Unit
)

data class ConfigPageSideEffectActions(
    val openGestureDebug: () -> Unit,
    val exportSettings: () -> Unit,
    val importSettings: () -> Unit,
    val exportHistory: () -> Unit,
    val clearHistory: () -> Unit,
    val requestPermission: () -> Unit,
    val requestCameraPermission: () -> Unit
)

enum class ConfigSectionDestination {
    HOME,
    GENERAL,
    OPENCLAW,
    TRANSCRIPTION,
    ASSISTANT_VOICE,
    SCREEN,
    HISTORY,
    DEBUG
}

fun currentConfigPageState(
    hasPermission: Boolean,
    hasCameraPermission: Boolean,
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
    cameraGestureStatus: String,
    voiceChannelSkillEnabled: Boolean,
    voiceChannelWakeTermsInput: String,
    voiceChannelFollowUpSecondsInput: String,
    voiceChannelIdlePromptSecondsInput: String,
    assistantVoiceEnabled: Boolean,
    assistantVoiceStyle: String,
    assistantSpeechRateInput: String,
    assistantPitchInput: String,
    development: Boolean,
    transcriptionMode: String,
    deviceGuide: DeviceModelGuide?,
    deviceModelLabel: String,
    localModelName: String,
    localModelExists: Boolean,
    huggingFaceModelExists: Boolean?,
    huggingFaceCheckInProgress: Boolean,
    localModelDownloadInProgress: Boolean,
    localModelDownloadStatus: String,
    localModelDownloadProgress: Float,
    localModelDownloadProgressLabel: String,
    localModelOptions: List<LocalModelOption>,
    localModelOptionsLoading: Boolean,
    selectedModelInvalid: Boolean,
    shouldOfferDownload: Boolean,
    localExecutionMode: String,
    localSystemInfo: String,
    microphoneAutoSensitivityEnabled: Boolean,
    microphoneGainInput: String,
    transcriptionRepeatSuppressionInput: String,
    colloquialNormalizationStrengthInput: String,
    vadThresholdInput: String,
    debugSpeechHoldMsInput: String,
    debugMaxSpeechSegmentMsInput: String,
    debugMinTranscriptionMsInput: String,
    debugPhraseBreakSilenceMsInput: String,
    screenMode: String,
    screenHoldSecondsInput: String,
    transcriptClearTimeoutSecsInput: String,
    openClawAccumulationWindowSecsInput: String,
    historySnapshot: TranscriptHistorySnapshot,
    historyActionStatus: String,
    settingsBackupStatus: String
): ConfigPageState {
    return ConfigPageState(
        hasPermission = hasPermission,
        hasCameraPermission = hasCameraPermission,
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
        cameraGestureStatus = cameraGestureStatus,
        development = development,
        voiceChannelSkillEnabled = voiceChannelSkillEnabled,
        voiceChannelWakeTermsInput = voiceChannelWakeTermsInput,
        voiceChannelFollowUpSecondsInput = voiceChannelFollowUpSecondsInput,
        voiceChannelIdlePromptSecondsInput = voiceChannelIdlePromptSecondsInput,
        assistantVoiceEnabled = assistantVoiceEnabled,
        assistantVoiceStyle = assistantVoiceStyle,
        assistantSpeechRateInput = assistantSpeechRateInput,
        assistantPitchInput = assistantPitchInput,
        transcriptionMode = transcriptionMode,
        deviceGuide = deviceGuide,
        deviceModelLabel = deviceModelLabel,
        localModelName = localModelName,
        localModelExists = localModelExists,
        huggingFaceModelExists = huggingFaceModelExists,
        huggingFaceCheckInProgress = huggingFaceCheckInProgress,
        localModelDownloadInProgress = localModelDownloadInProgress,
        localModelDownloadStatus = localModelDownloadStatus,
        localModelDownloadProgress = localModelDownloadProgress,
        localModelDownloadProgressLabel = localModelDownloadProgressLabel,
        localModelOptions = localModelOptions,
        localModelOptionsLoading = localModelOptionsLoading,
        selectedModelInvalid = selectedModelInvalid,
        shouldOfferDownload = shouldOfferDownload,
        localExecutionMode = localExecutionMode,
        localSystemInfo = localSystemInfo,
        microphoneAutoSensitivityEnabled = microphoneAutoSensitivityEnabled,
        microphoneGainInput = microphoneGainInput,
        transcriptionRepeatSuppressionInput = transcriptionRepeatSuppressionInput,
        colloquialNormalizationStrengthInput = colloquialNormalizationStrengthInput,
        vadThresholdInput = vadThresholdInput,
        debugSpeechHoldMsInput = debugSpeechHoldMsInput,
        debugMaxSpeechSegmentMsInput = debugMaxSpeechSegmentMsInput,
        debugMinTranscriptionMsInput = debugMinTranscriptionMsInput,
        debugPhraseBreakSilenceMsInput = debugPhraseBreakSilenceMsInput,
        screenMode = screenMode,
        screenHoldSecondsInput = screenHoldSecondsInput,
        transcriptClearTimeoutSecsInput = transcriptClearTimeoutSecsInput,
        openClawAccumulationWindowSecsInput = openClawAccumulationWindowSecsInput,
        historySnapshot = historySnapshot,
        historyActionStatus = historyActionStatus,
        settingsBackupStatus = settingsBackupStatus
    )
}

@Composable
fun ConfigPage(
    state: ConfigPageState,
    actions: ConfigPageActions,
    destination: ConfigSectionDestination,
    onDestinationChange: (ConfigSectionDestination) -> Unit
) {
    when (destination) {
        ConfigSectionDestination.HOME -> ConfigHubPage(
            state = state,
            onOpenSection = onDestinationChange
        )
        ConfigSectionDestination.GENERAL -> ConfigGeneralSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.OPENCLAW -> ConfigOpenClawSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.TRANSCRIPTION -> ConfigTranscriptionSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.ASSISTANT_VOICE -> ConfigAssistantVoiceSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.SCREEN -> ConfigScreenSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.HISTORY -> ConfigHistorySectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
        ConfigSectionDestination.DEBUG -> ConfigDebugSectionPage(
            state = state,
            actions = actions,
            onBack = { onDestinationChange(ConfigSectionDestination.HOME) }
        )
    }
}
