package com.sufficit.openclaw.gateway

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun buildConfigPageActions(
    context: Context,
    uiScope: CoroutineScope,
    currentDownloadState: () -> GatewayDownloadState,
    currentModelState: () -> GatewayModelState,
    currentHistoryState: () -> GatewayHistoryState,
    currentLocalModelName: () -> String,
    currentSettingsInputSnapshot: () -> GatewaySettingsInputSnapshot,
    sideEffectActions: ConfigPageSideEffectActions,
    updateDownloadState: (GatewayDownloadState) -> Unit,
    updateModelState: (GatewayModelState) -> Unit,
    updateHistoryState: (GatewayHistoryState) -> Unit,
    updateLocalEndpointUrl: (String) -> Unit,
    updateOpenClawServerAddress: (String) -> Unit,
    updateOpenClawGatewayToken: (String) -> Unit,
    updateOpenClawDeviceToken: (String) -> Unit,
    updateOpenClawSessionKey: (String) -> Unit,
    updateWhisperUrl: (String) -> Unit,
    updateRemoteModel: (String) -> Unit,
    updateWhisperAuthToken: (String) -> Unit,
    updateAutoStartEnabled: (Boolean) -> Unit,
    updateCameraGestureEnabled: (Boolean) -> Unit,
    updateDevelopment: (Boolean) -> Unit,
    updateVoiceChannelSkillEnabled: (Boolean) -> Unit,
    updateVoiceChannelWakeTerms: (String) -> Unit,
    updateVoiceChannelFollowUpSeconds: (String) -> Unit,
    updateVoiceChannelIdlePromptSeconds: (String) -> Unit,
    updateOpenClawAccumulationWindowSecs: (String) -> Unit,
    updateAssistantVoiceEnabled: (Boolean) -> Unit,
    updateAssistantVoiceStyle: (String) -> Unit,
    updateAssistantSpeechRate: (String) -> Unit,
    updateAssistantPitch: (String) -> Unit,
    updateTranscriptionMode: (String) -> Unit,
    updateLocalModelName: (String) -> Unit,
    resetDownloadState: () -> Unit,
    updateLocalExecutionMode: (String) -> Unit,
    updateMicrophoneAutoSensitivityEnabled: (Boolean) -> Unit,
    updateMicrophoneGain: (String) -> Unit,
    updateTranscriptionRepeatSuppression: (String) -> Unit,
    updateColloquialNormalizationStrength: (String) -> Unit,
    updateVadThreshold: (String) -> Unit,
    updateDebugSpeechHoldMs: (String) -> Unit,
    updateDebugMaxSpeechSegmentMs: (String) -> Unit,
    updateDebugMinTranscriptionMs: (String) -> Unit,
    updateDebugPhraseBreakSilenceMs: (String) -> Unit,
    updateScreenMode: (String) -> Unit,
    updateScreenHoldSeconds: (String) -> Unit,
    updateTranscriptClearTimeoutSecs: (String) -> Unit
): ConfigPageActions {
    return ConfigPageActions(
        onLocalEndpointChange = updateLocalEndpointUrl,
        onOpenClawServerAddressChange = updateOpenClawServerAddress,
        onOpenClawGatewayTokenChange = updateOpenClawGatewayToken,
        onOpenClawDeviceTokenChange = updateOpenClawDeviceToken,
        onOpenClawSessionKeyChange = updateOpenClawSessionKey,
        onWhisperUrlChange = updateWhisperUrl,
        onRemoteModelChange = updateRemoteModel,
        onWhisperAuthTokenChange = updateWhisperAuthToken,
        onAutoStartEnabledChange = updateAutoStartEnabled,
        onCameraGestureEnabledChange = updateCameraGestureEnabled,
        onDevelopmentChange = updateDevelopment,
        onVoiceChannelSkillEnabledChange = updateVoiceChannelSkillEnabled,
        onVoiceChannelWakeTermsChange = updateVoiceChannelWakeTerms,
        onVoiceChannelFollowUpSecondsChange = updateVoiceChannelFollowUpSeconds,
        onVoiceChannelIdlePromptSecondsChange = updateVoiceChannelIdlePromptSeconds,
        onOpenClawAccumulationWindowSecsChange = updateOpenClawAccumulationWindowSecs,
        onAssistantVoiceEnabledChange = updateAssistantVoiceEnabled,
        onAssistantVoiceStyleChange = updateAssistantVoiceStyle,
        onAssistantSpeechRateChange = updateAssistantSpeechRate,
        onAssistantPitchChange = updateAssistantPitch,
        onTranscriptionModeChange = updateTranscriptionMode,
        onLocalModelNameChange = {
            updateLocalModelName(it)
            resetDownloadState()
        },
        onLocalExecutionModeChange = updateLocalExecutionMode,
        onMicrophoneAutoSensitivityEnabledChange = updateMicrophoneAutoSensitivityEnabled,
        onMicrophoneGainChange = updateMicrophoneGain,
        onTranscriptionRepeatSuppressionChange = updateTranscriptionRepeatSuppression,
        onColloquialNormalizationStrengthChange = updateColloquialNormalizationStrength,
        onVadThresholdChange = updateVadThreshold,
        onDebugSpeechHoldMsChange = updateDebugSpeechHoldMs,
        onDebugMaxSpeechSegmentMsChange = updateDebugMaxSpeechSegmentMs,
        onDebugMinTranscriptionMsChange = updateDebugMinTranscriptionMs,
        onDebugPhraseBreakSilenceMsChange = updateDebugPhraseBreakSilenceMs,
        onScreenModeChange = updateScreenMode,
        onScreenHoldSecondsChange = updateScreenHoldSeconds,
        onTranscriptClearTimeoutSecsChange = updateTranscriptClearTimeoutSecs,
        onOpenGestureDebug = sideEffectActions.openGestureDebug,
        onDownloadLocalModel = {
            val downloadState = currentDownloadState()
            val modelState = currentModelState()
            val localModelName = currentLocalModelName()

            if (downloadState.inProgress || modelState.huggingFaceCheckInProgress || modelState.huggingFaceModelExists != true) {
                return@ConfigPageActions
            }

            val normalizedName = localModelName.trim()
            if (normalizedName.isBlank()) {
                resetDownloadState()
                return@ConfigPageActions
            }

            updateDownloadState(
                downloadState.copy(
                    inProgress = true,
                    status = "Baixando modelo...",
                    progress = 0f,
                    progressLabel = "0%"
                )
            )

            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    downloadModelFromHuggingFace(
                        context = context,
                        modelName = normalizedName
                    ) { downloadedBytes, totalBytes ->
                        val progress = if (totalBytes > 0L) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val label = if (totalBytes > 0L) {
                            val percent = (progress * 100f).toInt()
                            "$percent% (${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)})"
                        } else {
                            "${formatBytes(downloadedBytes)} baixados"
                        }
                        updateDownloadState(
                            currentDownloadState().copy(
                                progress = progress,
                                progressLabel = label
                            )
                        )
                    }
                }

                val failureMessage = result.exceptionOrNull()?.message ?: "erro desconhecido"
                if (result.isSuccess) {
                    updateModelState(currentModelState().copy(localModelExists = true))
                    val freshDownloadState = currentDownloadState()
                    updateDownloadState(
                        freshDownloadState.copy(
                            progress = 1f,
                            progressLabel = "100%",
                            status = "Download concluido.",
                            optionsRefreshTick = freshDownloadState.optionsRefreshTick + 1
                        )
                    )
                } else {
                    updateDownloadState(
                        currentDownloadState().copy(
                            status = "Falha no download: $failureMessage"
                        )
                    )
                }

                updateDownloadState(currentDownloadState().copy(inProgress = false))
            }
        },
        onExportSettings = sideEffectActions.exportSettings,
        onImportSettings = sideEffectActions.importSettings,
        onExportHistory = sideEffectActions.exportHistory,
        onClearHistory = sideEffectActions.clearHistory,
        onRequestPermission = sideEffectActions.requestPermission,
        onRequestCameraPermission = sideEffectActions.requestCameraPermission
    )
}



