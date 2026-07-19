package com.sufficit.ai.gateway

import android.content.Context
import com.sufficit.ai.gateway.config.GatewaySettings
import com.sufficit.ai.gateway.config.GatewayConfigCatalog
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.AssistantVoiceStyle
import com.sufficit.ai.gateway.config.LocalExecutionMode
import com.sufficit.ai.gateway.config.ScreenMode
import com.sufficit.ai.gateway.config.TranscriptionMode
import java.io.File
import java.util.Locale

fun applySettingsToUi(
    settings: GatewaySettings,
    onLocalEndpointUrlChange: (String) -> Unit,
    onOpenClawServerAddressChange: (String) -> Unit,
    onOpenClawGatewayTokenChange: (String) -> Unit,
    onOpenClawDeviceTokenChange: (String) -> Unit,
    onOpenClawSessionKeyChange: (String) -> Unit,
    onWhisperUrlChange: (String) -> Unit,
    onRemoteModelChange: (String) -> Unit,
    onWhisperAuthTokenChange: (String) -> Unit,
    onAutoStartEnabledChange: (Boolean) -> Unit,
    onCameraGestureEnabledChange: (Boolean) -> Unit,
    onVoiceChannelSkillEnabledChange: (Boolean) -> Unit,
    onVoiceChannelWakeTermsChange: (String) -> Unit,
    onVoiceChannelFollowUpSecondsChange: (String) -> Unit,
    onVoiceChannelIdlePromptSecondsChange: (String) -> Unit,
    onAssistantVoiceEnabledChange: (Boolean) -> Unit,
    onAssistantVoiceStyleChange: (String) -> Unit,
    onAssistantSpeechRateChange: (String) -> Unit,
    onAssistantPitchChange: (String) -> Unit,
    onTranscriptionModeChange: (String) -> Unit,
    onLocalModelNameChange: (String) -> Unit,
    onLocalExecutionModeChange: (String) -> Unit,
    onDevelopmentChange: (Boolean) -> Unit,
    onMicrophoneAutoSensitivityEnabledChange: (Boolean) -> Unit,
    onMicrophoneGainChange: (String) -> Unit,
    onTranscriptionRepeatSuppressionChange: (String) -> Unit,
    onColloquialNormalizationStrengthChange: (String) -> Unit,
    onVadThresholdChange: (String) -> Unit,
    onDebugSpeechHoldMsChange: (String) -> Unit,
    onDebugMaxSpeechSegmentMsChange: (String) -> Unit,
    onDebugMinTranscriptionMsChange: (String) -> Unit,
    onDebugPhraseBreakSilenceMsChange: (String) -> Unit,
    onTranscriptionTermsChange: (String) -> Unit,
    onTranscriptionDictionaryChange: (String) -> Unit,
    onScreenModeChange: (String) -> Unit,
    onScreenHoldSecondsChange: (String) -> Unit,
    onTranscriptClearTimeoutSecsChange: (String) -> Unit,
    onOpenClawAccumulationWindowSecsChange: (String) -> Unit,
    onLocalModelDownloadStatusChange: (String) -> Unit,
    onLocalModelDownloadProgressChange: (Float) -> Unit,
    onLocalModelDownloadProgressLabelChange: (String) -> Unit
) {
    onLocalEndpointUrlChange(settings.localEndpointUrl)
    onOpenClawServerAddressChange(settings.openClawServerAddress)
    onOpenClawGatewayTokenChange(settings.openClawGatewayToken)
    onOpenClawDeviceTokenChange(settings.openClawDeviceToken)
    onOpenClawSessionKeyChange(settings.openClawSessionKey)
    onWhisperUrlChange(settings.whisperUrl)
    onRemoteModelChange(settings.remoteModel)
    onWhisperAuthTokenChange(settings.whisperAuthToken)
    onAutoStartEnabledChange(settings.autoStartEnabled)
    onCameraGestureEnabledChange(settings.cameraGestureEnabled)
    onVoiceChannelSkillEnabledChange(settings.voiceChannelSkillEnabled)
    onVoiceChannelWakeTermsChange(settings.voiceChannelWakeTerms)
    onVoiceChannelFollowUpSecondsChange(settings.voiceChannelFollowUpSeconds.toString())
    onVoiceChannelIdlePromptSecondsChange(settings.voiceChannelIdlePromptSeconds.toString())
    onAssistantVoiceEnabledChange(settings.assistantVoiceEnabled)
    onAssistantVoiceStyleChange(settings.assistantVoiceStyle.persistedValue)
    onAssistantSpeechRateChange(String.format(Locale.US, "%.2f", settings.assistantSpeechRate))
    onAssistantPitchChange(String.format(Locale.US, "%.2f", settings.assistantPitch))
    onTranscriptionModeChange(settings.transcriptionMode.persistedValue)
    onLocalModelNameChange(File(settings.localModelPath).name)
    onLocalExecutionModeChange(settings.localExecutionMode.persistedValue)
    onDevelopmentChange(settings.development)
    onMicrophoneAutoSensitivityEnabledChange(settings.microphoneAutoSensitivityEnabled)
    onMicrophoneGainChange(String.format(Locale.US, "%.1f", settings.microphoneGain))
    onTranscriptionRepeatSuppressionChange(String.format(Locale.US, "%.2f", settings.transcriptionRepeatSuppression))
    onColloquialNormalizationStrengthChange(String.format(Locale.US, "%.2f", settings.colloquialNormalizationStrength))
    onVadThresholdChange(String.format(Locale.US, "%.3f", settings.vadThreshold))
    onDebugSpeechHoldMsChange(settings.debugSpeechHoldMs?.toString().orEmpty())
    onDebugMaxSpeechSegmentMsChange(settings.debugMaxSpeechSegmentMs?.toString().orEmpty())
    onDebugMinTranscriptionMsChange(settings.debugMinTranscriptionMs?.toString().orEmpty())
    onDebugPhraseBreakSilenceMsChange(settings.debugPhraseBreakSilenceMs?.toString().orEmpty())
    onTranscriptionTermsChange(settings.transcriptionTerms)
    onTranscriptionDictionaryChange(settings.transcriptionDictionary)
    onScreenModeChange(settings.screenMode.persistedValue)
    onScreenHoldSecondsChange(settings.screenHoldSeconds.toString())
    onTranscriptClearTimeoutSecsChange(settings.transcriptClearTimeoutSecs.toString())
    onOpenClawAccumulationWindowSecsChange(settings.openClawAccumulationWindowSecs.toString())
    onLocalModelDownloadStatusChange("")
    onLocalModelDownloadProgressChange(0f)
    onLocalModelDownloadProgressLabelChange("")
}

// selectedModelOption and loadLocalModelOptions moved to GatewayModelOptionsSupport.kt

data class GatewayDownloadState(
    val inProgress: Boolean,
    val status: String,
    val progress: Float,
    val progressLabel: String,
    val optionsRefreshTick: Int
)

data class GatewayHistoryState(
    val refreshTick: Int,
    val actionStatus: String,
    val settingsBackupStatus: String
)

data class GatewayUiState(
    val configDestination: ConfigSectionDestination,
    val lastBackPressedAt: Long
)

data class GatewayModelState(
    val localOptionsLoading: Boolean,
    val localModelExists: Boolean,
    val huggingFaceModelExists: Boolean?,
    val huggingFaceCheckInProgress: Boolean
)

data class GatewaySettingsInputSnapshot(
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
    val assistantVoiceStyleValue: String,
    val assistantSpeechRateInput: String,
    val assistantPitchInput: String,
    val transcriptionModeValue: String,
    val localModelName: String,
    val localExecutionModeValue: String,
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
    val screenModeValue: String,
    val screenHoldSecondsInput: String,
    val transcriptClearTimeoutSecsInput: String,
    val openClawAccumulationWindowSecsInput: String
)

fun buildSettings(
    context: Context,
    input: GatewaySettingsInputSnapshot
): GatewaySettings {
    val runtimeDefaults = GatewayConfigCatalog.ensureSeedLoaded(context)
    val runtimeCurrent = GatewayConfigCatalog.loadRuntime(context)
    val numbers = normalizeSettingsNumbers(input)

    val resolvedWhisperAuthToken = input.whisperAuthToken.trim().ifBlank {
        runtimeCurrent?.whisperAuthToken?.trim()?.ifBlank { runtimeDefaults.whisperAuthToken }
            ?: runtimeDefaults.whisperAuthToken
    }

    return GatewaySettings(
        localEndpointUrl = input.localEndpointUrl.ifBlank { runtimeDefaults.localEndpointUrl },
        openClawServerAddress = input.openClawServerAddress.ifBlank { runtimeDefaults.openClawServerAddress },
        openClawGatewayToken = input.openClawGatewayToken.ifBlank { GatewaySettingsStore.DEFAULT_OPENCLAW_GATEWAY_TOKEN },
        openClawDeviceToken = input.openClawDeviceToken.ifBlank { GatewaySettingsStore.DEFAULT_OPENCLAW_DEVICE_TOKEN },
        openClawSessionKey = input.openClawSessionKey.ifBlank { GatewaySettingsStore.DEFAULT_OPENCLAW_SESSION_KEY },
        whisperUrl = input.whisperUrl.ifBlank { runtimeDefaults.whisperUrl },
        remoteModel = input.remoteModel.ifBlank { runtimeDefaults.remoteModel },
        whisperAuthToken = resolvedWhisperAuthToken,
        autoStartEnabled = input.autoStartEnabled,
        cameraGestureEnabled = input.cameraGestureEnabled,
        voiceChannelSkillEnabled = input.voiceChannelSkillEnabled,
        voiceChannelWakeTerms = input.voiceChannelWakeTermsInput.trim().ifBlank {
            GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_WAKE_TERMS
        },
        voiceChannelFollowUpSeconds = numbers.voiceChannelFollowUpSeconds,
        voiceChannelIdlePromptSeconds = numbers.voiceChannelIdlePromptSeconds,
        assistantVoiceEnabled = input.assistantVoiceEnabled,
        assistantVoiceStyle = AssistantVoiceStyle.fromPersistedValue(input.assistantVoiceStyleValue),
        assistantSpeechRate = numbers.assistantSpeechRate,
        assistantPitch = numbers.assistantPitch,
        transcriptionMode = TranscriptionMode.fromPersistedValue(input.transcriptionModeValue),
        localModelPath = resolveLocalModelTarget(
            context = context,
            modelName = input.localModelName.ifBlank { File(GatewaySettingsStore.DEFAULT_LOCAL_MODEL_PATH).name }
        ).absolutePath,
        localExecutionMode = LocalExecutionMode.fromPersistedValue(input.localExecutionModeValue),
        development = input.development,
        microphoneAutoSensitivityEnabled = input.microphoneAutoSensitivityEnabled,
        microphoneGain = numbers.microphoneGain,
        transcriptionRepeatSuppression = numbers.transcriptionRepeatSuppression,
        colloquialNormalizationStrength = numbers.colloquialNormalizationStrength,
        vadThreshold = numbers.vadThreshold,
        whisperVadFilter = runtimeCurrent?.whisperVadFilter ?: runtimeDefaults.whisperVadFilter,
        whisperConditionOnPreviousText = runtimeCurrent?.whisperConditionOnPreviousText
            ?: runtimeDefaults.whisperConditionOnPreviousText,
        whisperNoSpeechThreshold = (runtimeCurrent?.whisperNoSpeechThreshold
            ?: runtimeDefaults.whisperNoSpeechThreshold).coerceIn(0.0, 1.0),
        whisperCompressionRatioThreshold = (runtimeCurrent?.whisperCompressionRatioThreshold
            ?: runtimeDefaults.whisperCompressionRatioThreshold).coerceIn(1.0, 5.0),
        whisperRepetitionPenalty = (runtimeCurrent?.whisperRepetitionPenalty
            ?: runtimeDefaults.whisperRepetitionPenalty).coerceIn(1.0, 2.0),
        debugSpeechHoldMs = numbers.debugSpeechHoldMs,
        debugMaxSpeechSegmentMs = numbers.debugMaxSpeechSegmentMs,
        debugMinTranscriptionMs = numbers.debugMinTranscriptionMs,
        debugPhraseBreakSilenceMs = numbers.debugPhraseBreakSilenceMs,
        transcriptionTerms = input.transcriptionTermsInput.trim(),
        transcriptionDictionary = input.transcriptionDictionaryInput.trim(),
        screenMode = ScreenMode.fromPersistedValue(input.screenModeValue),
        screenHoldSeconds = numbers.screenHoldSeconds,
        transcriptClearTimeoutSecs = numbers.transcriptClearTimeoutSecs,
        openClawAccumulationWindowSecs = numbers.openClawAccumulationWindowSecs,
        // Campos da API nao sao gerenciados por este snapshot da UI principal
        // (tem secao propria). Preserva o que ja esta persistido para o save
        // de normalizacao nao zerar a config da API.
        apiEnabled = runtimeCurrent?.apiEnabled ?: GatewaySettingsStore.DEFAULT_API_ENABLED,
        apiPort = runtimeCurrent?.apiPort ?: GatewaySettingsStore.DEFAULT_API_PORT,
        apiBindAllInterfaces = runtimeCurrent?.apiBindAllInterfaces ?: GatewaySettingsStore.DEFAULT_API_BIND_ALL_INTERFACES,
        apiToken = runtimeCurrent?.apiToken ?: GatewaySettingsStore.DEFAULT_API_TOKEN,
        // userId nao e gerenciado pelo snapshot da UI principal (secao
        // propria); preserva o persistido.
        openClawUserId = runtimeCurrent?.openClawUserId ?: GatewaySettingsStore.DEFAULT_OPENCLAW_USER_ID
    )
}

