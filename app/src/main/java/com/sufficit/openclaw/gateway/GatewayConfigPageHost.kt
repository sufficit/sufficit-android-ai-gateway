package com.sufficit.openclaw.gateway

import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import com.sufficit.openclaw.gateway.config.DeviceModelGuide
import com.sufficit.openclaw.gateway.history.TranscriptHistorySnapshot
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun GatewayConfigPageHost(
    // Grouped settings state holder
    settingsState: GatewaySettingsState,
    // Permission state
    hasPermission: Boolean,
    hasCameraPermission: Boolean,
    // Runtime-derived
    cameraGestureStatus: String,
    // Aggregated state holders
    downloadState: GatewayDownloadState,
    modelState: GatewayModelState,
    historyState: GatewayHistoryState,
    // Derived/computed state
    localModelOptions: List<LocalModelOption>,
    selectedModelInvalid: Boolean,
    shouldOfferDownload: Boolean,
    localSystemInfo: String,
    deviceGuide: DeviceModelGuide?,
    historySnapshot: TranscriptHistorySnapshot,
    // Factory dependencies
    context: Context,
    uiScope: CoroutineScope,
    currentDownloadState: () -> GatewayDownloadState,
    currentModelState: () -> GatewayModelState,
    currentHistoryState: () -> GatewayHistoryState,
    // Side-effect launchers (owned by MainActivity)
    launchSettingsImport: ActivityResultLauncher<Array<String>>,
    requestMicrophonePermission: () -> Unit,
    requestCameraPermission: () -> Unit,
    openGestureDebug: () -> Unit,
    // State updaters
    updateDownloadState: (GatewayDownloadState) -> Unit,
    updateModelState: (GatewayModelState) -> Unit,
    updateHistoryState: (GatewayHistoryState) -> Unit,
    // Navigation
    destination: ConfigSectionDestination,
    onDestinationChange: (ConfigSectionDestination) -> Unit
) {
    ConfigPage(
        state = currentConfigPageState(
            hasPermission = hasPermission,
            hasCameraPermission = hasCameraPermission,
            localEndpointUrl = settingsState.localEndpointUrl,
            openClawServerAddress = settingsState.openClawServerAddress,
            openClawGatewayToken = settingsState.openClawGatewayToken,
            openClawDeviceToken = settingsState.openClawDeviceToken,
            openClawSessionKey = settingsState.openClawSessionKey,
            whisperUrl = settingsState.whisperUrl,
            remoteModel = settingsState.remoteModel,
            whisperAuthToken = settingsState.whisperAuthToken,
            autoStartEnabled = settingsState.autoStartEnabled,
            cameraGestureEnabled = settingsState.cameraGestureEnabled,
            cameraGestureStatus = cameraGestureStatus,
            voiceChannelSkillEnabled = settingsState.voiceChannelSkillEnabled,
            voiceChannelWakeTermsInput = settingsState.voiceChannelWakeTermsInput,
            voiceChannelFollowUpSecondsInput = settingsState.voiceChannelFollowUpSecondsInput,
            voiceChannelIdlePromptSecondsInput = settingsState.voiceChannelIdlePromptSecondsInput,
            assistantVoiceEnabled = settingsState.assistantVoiceEnabled,
            assistantVoiceStyle = settingsState.assistantVoiceStyle,
            assistantSpeechRateInput = settingsState.assistantSpeechRateInput,
            assistantPitchInput = settingsState.assistantPitchInput,
            development = settingsState.development,
            transcriptionMode = settingsState.transcriptionMode,
            deviceGuide = deviceGuide,
            deviceModelLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            localModelName = settingsState.localModelName,
            localModelExists = modelState.localModelExists,
            huggingFaceModelExists = modelState.huggingFaceModelExists,
            huggingFaceCheckInProgress = modelState.huggingFaceCheckInProgress,
            localModelDownloadInProgress = downloadState.inProgress,
            localModelDownloadStatus = downloadState.status,
            localModelDownloadProgress = downloadState.progress,
            localModelDownloadProgressLabel = downloadState.progressLabel,
            localModelOptions = localModelOptions,
            localModelOptionsLoading = modelState.localOptionsLoading,
            selectedModelInvalid = selectedModelInvalid,
            shouldOfferDownload = shouldOfferDownload,
            localExecutionMode = settingsState.localExecutionMode,
            localSystemInfo = localSystemInfo,
            microphoneAutoSensitivityEnabled = settingsState.microphoneAutoSensitivityEnabled,
            microphoneGainInput = settingsState.microphoneGainInput,
            transcriptionRepeatSuppressionInput = settingsState.transcriptionRepeatSuppressionInput,
            colloquialNormalizationStrengthInput = settingsState.colloquialNormalizationStrengthInput,
            vadThresholdInput = settingsState.vadThresholdInput,
            debugSpeechHoldMsInput = settingsState.debugSpeechHoldMsInput,
            debugMaxSpeechSegmentMsInput = settingsState.debugMaxSpeechSegmentMsInput,
            debugMinTranscriptionMsInput = settingsState.debugMinTranscriptionMsInput,
            debugPhraseBreakSilenceMsInput = settingsState.debugPhraseBreakSilenceMsInput,
            screenMode = settingsState.screenMode,
            screenHoldSecondsInput = settingsState.screenHoldSecondsInput,
            transcriptClearTimeoutSecsInput = settingsState.transcriptClearTimeoutSecsInput,
            openClawAccumulationWindowSecsInput = settingsState.openClawAccumulationWindowSecsInput,
            historySnapshot = historySnapshot,
            historyActionStatus = historyState.actionStatus,
            settingsBackupStatus = historyState.settingsBackupStatus
        ),
        actions = buildConfigPageActions(
            context = context,
            uiScope = uiScope,
            currentDownloadState = currentDownloadState,
            currentModelState = currentModelState,
            currentHistoryState = currentHistoryState,
            currentLocalModelName = { settingsState.localModelName },
            currentSettingsInputSnapshot = { settingsState.toSnapshot() },
            sideEffectActions = buildConfigPageSideEffectActions(
                context = context,
                currentHistoryState = currentHistoryState,
                updateHistoryState = updateHistoryState,
                currentSettingsInputSnapshot = { settingsState.toSnapshot() },
                launchSettingsImport = launchSettingsImport,
                requestMicrophonePermission = requestMicrophonePermission,
                requestCameraPermission = requestCameraPermission,
                openGestureDebug = openGestureDebug
            ),
            updateDownloadState = updateDownloadState,
            updateModelState = updateModelState,
            updateHistoryState = updateHistoryState,
            updateLocalEndpointUrl = { settingsState.localEndpointUrl = it },
            updateOpenClawServerAddress = { settingsState.openClawServerAddress = it },
            updateOpenClawGatewayToken = { settingsState.openClawGatewayToken = it },
            updateOpenClawDeviceToken = { settingsState.openClawDeviceToken = it },
            updateOpenClawSessionKey = { settingsState.openClawSessionKey = it },
            updateWhisperUrl = { settingsState.whisperUrl = it },
            updateRemoteModel = { settingsState.remoteModel = it },
            updateWhisperAuthToken = { settingsState.whisperAuthToken = it },
            updateAutoStartEnabled = { settingsState.autoStartEnabled = it },
            updateCameraGestureEnabled = { settingsState.cameraGestureEnabled = it },
            updateDevelopment = { settingsState.development = it },
            updateVoiceChannelSkillEnabled = { settingsState.voiceChannelSkillEnabled = it },
            updateVoiceChannelWakeTerms = { settingsState.voiceChannelWakeTermsInput = it },
            updateVoiceChannelFollowUpSeconds = { settingsState.voiceChannelFollowUpSecondsInput = it },
            updateVoiceChannelIdlePromptSeconds = { settingsState.voiceChannelIdlePromptSecondsInput = it },
            updateOpenClawAccumulationWindowSecs = { settingsState.openClawAccumulationWindowSecsInput = it },
            updateAssistantVoiceEnabled = { settingsState.assistantVoiceEnabled = it },
            updateAssistantVoiceStyle = { settingsState.assistantVoiceStyle = it },
            updateAssistantSpeechRate = { settingsState.assistantSpeechRateInput = it },
            updateAssistantPitch = { settingsState.assistantPitchInput = it },
            updateTranscriptionMode = { settingsState.transcriptionMode = it },
            updateLocalModelName = { settingsState.localModelName = it },
            resetDownloadState = {
                updateDownloadState(currentDownloadState().copy(status = "", progress = 0f, progressLabel = ""))
            },
            updateLocalExecutionMode = { settingsState.localExecutionMode = it },
            updateMicrophoneAutoSensitivityEnabled = { settingsState.microphoneAutoSensitivityEnabled = it },
            updateMicrophoneGain = { settingsState.microphoneGainInput = it },
            updateTranscriptionRepeatSuppression = { settingsState.transcriptionRepeatSuppressionInput = it },
            updateColloquialNormalizationStrength = { settingsState.colloquialNormalizationStrengthInput = it },
            updateVadThreshold = { settingsState.vadThresholdInput = it },
            updateDebugSpeechHoldMs = { settingsState.debugSpeechHoldMsInput = it },
            updateDebugMaxSpeechSegmentMs = { settingsState.debugMaxSpeechSegmentMsInput = it },
            updateDebugMinTranscriptionMs = { settingsState.debugMinTranscriptionMsInput = it },
            updateDebugPhraseBreakSilenceMs = { settingsState.debugPhraseBreakSilenceMsInput = it },
            updateScreenMode = { settingsState.screenMode = it },
            updateScreenHoldSeconds = { settingsState.screenHoldSecondsInput = it },
            updateTranscriptClearTimeoutSecs = { settingsState.transcriptClearTimeoutSecsInput = it }
        ),
        destination = destination,
        onDestinationChange = onDestinationChange
    )
}
