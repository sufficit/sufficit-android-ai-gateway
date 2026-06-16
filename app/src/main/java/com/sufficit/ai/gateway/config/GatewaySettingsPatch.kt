package com.sufficit.ai.gateway.config

import org.json.JSONObject

data class GatewaySettingsPatchResult(
    val settings: GatewaySettings,
    val appliedKeys: List<String>,
    val ignoredKeys: List<String>,
    val requiresCaptureRestart: Boolean,
    val requiresReconnect: Boolean,
    val requiresTtsRefresh: Boolean,
    val requiresApiRestart: Boolean = false
)

private val API_RESTART_PATCH_KEYS = setOf(
    "apiEnabled", "apiPort", "apiBindAllInterfaces", "apiToken"
)

private val CAPTURE_RESTART_PATCH_KEYS = setOf(
    "localEndpointUrl",
    "whisperUrl",
    "remoteModel",
    "whisperAuthToken",
    "transcriptionMode",
    "localModelPath",
    "localExecutionMode",
    "cameraGestureEnabled",
    "microphoneAutoSensitivityEnabled",
    "microphoneGain",
    "transcriptionRepeatSuppression",
    "colloquialNormalizationStrength",
    "vadThreshold",
    "whisperVadFilter",
    "whisperConditionOnPreviousText",
    "whisperNoSpeechThreshold",
    "whisperCompressionRatioThreshold",
    "whisperRepetitionPenalty",
    "debugSpeechHoldMs",
    "debugMaxSpeechSegmentMs",
    "debugMinTranscriptionMs",
    "debugPhraseBreakSilenceMs",
    "transcriptionTerms",
    "transcriptionDictionary",
    "noiseGateMultiplier",
    "minSpeechRms",
    "minSpeechPeakNormalized",
    "minSpeechCandidateFrames",
    "maxTransientCrestFactor",
    "minZeroCrossingRate",
    "maxZeroCrossingRate",
    "ambientStabilityThreshold",
    "ambientGainStabilityThreshold",
    "ambientDynamicContrastMax",
    "ambientRmsVarianceMax",
    "ambientSpectrumMotionMax",
    "ambientSpeechPenalty",
    "ambientDetectionHoldFrames",
    "ambientDetectionReleaseFrames",
    "ambientSpeechOverrideDynamicContrast",
    "ambientSpeechOverrideSpectrumMotion",
    "ambientGainFactor",
    "ambientGainStabilityReduction",
    "ambientGainMinGain",
    "ambientGainSmoothingFast",
    "ambientGainSmoothingSlow"
)

private val OPENCLAW_RECONNECT_PATCH_KEYS = setOf(
    "openClawServerAddress",
    "openClawGatewayToken",
    "openClawDeviceToken",
    "openClawSessionKey"
)

private val TTS_REFRESH_PATCH_KEYS = setOf(
    "assistantVoiceEnabled",
    "assistantVoiceStyle",
    "assistantSpeechRate",
    "assistantPitch"
)

/**
 * Converts the structured sectioned config format (v1) into the flat canonical key map
 * expected by [applyWebSocketSettingsPatch].
 *
 * Only the sectioned format is supported. No legacy flat-key compat.
 * See: android-config-format.instructions.md
 */
fun flattenSectionedJson(root: JSONObject): JSONObject {
    val flat = JSONObject()

    fun copyKey(src: JSONObject, srcKey: String, dstKey: String = srcKey) {
        if (src.has(srcKey)) flat.put(dstKey, src.opt(srcKey))
    }

    root.optJSONObject("general")?.let { s ->
        copyKey(s, "autoStart", "autoStartEnabled")
        copyKey(s, "cameraGestureEnabled")
        copyKey(s, "development")
    }
    root.optJSONObject("connection")?.let { s ->
        copyKey(s, "serverAddress", "openClawServerAddress")
        copyKey(s, "gatewayToken", "openClawGatewayToken")
        copyKey(s, "deviceToken", "openClawDeviceToken")
        copyKey(s, "sessionKey", "openClawSessionKey")
        copyKey(s, "userId", "openClawUserId")
        copyKey(s, "localEndpointUrl")
    }
    root.optJSONObject("transcription")?.let { s ->
        copyKey(s, "mode", "transcriptionMode")
        copyKey(s, "remoteModel")
        copyKey(s, "whisperUrl")
        copyKey(s, "whisperToken", "whisperAuthToken")
        copyKey(s, "localModel", "localModelPath")
        copyKey(s, "localExecution", "localExecutionMode")
        copyKey(s, "terms", "transcriptionTerms")
        copyKey(s, "dictionary", "transcriptionDictionary")
        copyKey(s, "repeatSuppression", "transcriptionRepeatSuppression")
        copyKey(s, "colloquialNormalization", "colloquialNormalizationStrength")
    }
    root.optJSONObject("whisper")?.let { s ->
        copyKey(s, "vadFilter", "whisperVadFilter")
        copyKey(s, "conditionOnPreviousText", "whisperConditionOnPreviousText")
        copyKey(s, "noSpeechThreshold", "whisperNoSpeechThreshold")
        copyKey(s, "compressionRatioThreshold", "whisperCompressionRatioThreshold")
        copyKey(s, "repetitionPenalty", "whisperRepetitionPenalty")
    }
    root.optJSONObject("audio")?.let { s ->
        copyKey(s, "vadThreshold")
        copyKey(s, "autoSensitivity", "microphoneAutoSensitivityEnabled")
        copyKey(s, "gain", "microphoneGain")
        copyKey(s, "noiseGateMultiplier")
        copyKey(s, "minSpeechRms")
        copyKey(s, "minSpeechPeakNormalized")
        copyKey(s, "minSpeechCandidateFrames")
        copyKey(s, "maxTransientCrestFactor")
        copyKey(s, "minZeroCrossingRate")
        copyKey(s, "maxZeroCrossingRate")
        copyKey(s, "ambientStabilityThreshold")
        copyKey(s, "ambientGainStabilityThreshold")
        copyKey(s, "ambientDynamicContrastMax")
        copyKey(s, "ambientRmsVarianceMax")
        copyKey(s, "ambientSpectrumMotionMax")
        copyKey(s, "ambientSpeechPenalty")
        copyKey(s, "ambientDetectionHoldFrames")
        copyKey(s, "ambientDetectionReleaseFrames")
        copyKey(s, "ambientSpeechOverrideDynamicContrast")
        copyKey(s, "ambientSpeechOverrideSpectrumMotion")
        copyKey(s, "ambientGainFactor")
        copyKey(s, "ambientGainStabilityReduction")
        copyKey(s, "ambientGainMinGain")
        copyKey(s, "ambientGainSmoothingFast")
        copyKey(s, "ambientGainSmoothingSlow")
    }
    root.optJSONObject("assistant")?.let { s ->
        copyKey(s, "voiceEnabled", "assistantVoiceEnabled")
        copyKey(s, "voiceStyle", "assistantVoiceStyle")
        copyKey(s, "speechRate", "assistantSpeechRate")
        copyKey(s, "pitch", "assistantPitch")
    }
    root.optJSONObject("voiceChannel")?.let { s ->
        copyKey(s, "enabled", "voiceChannelSkillEnabled")
        copyKey(s, "wakeTerms", "voiceChannelWakeTerms")
        copyKey(s, "followUpSecs", "voiceChannelFollowUpSeconds")
        copyKey(s, "idlePromptSecs", "voiceChannelIdlePromptSeconds")
        copyKey(s, "accumulationWindowSecs", "openClawAccumulationWindowSecs")
    }
    root.optJSONObject("screen")?.let { s ->
        copyKey(s, "mode", "screenMode")
        copyKey(s, "holdSeconds", "screenHoldSeconds")
    }
    root.optJSONObject("card")?.let { s ->
        copyKey(s, "clearTimeoutSecs", "transcriptClearTimeoutSecs")
    }
    root.optJSONObject("api")?.let { s ->
        copyKey(s, "enabled", "apiEnabled")
        copyKey(s, "port", "apiPort")
        copyKey(s, "bindAllInterfaces", "apiBindAllInterfaces")
        copyKey(s, "token", "apiToken")
    }
    root.optJSONObject("debug")?.let { s ->
        copyKey(s, "speechHoldMs", "debugSpeechHoldMs")
        copyKey(s, "maxSpeechSegmentMs", "debugMaxSpeechSegmentMs")
        copyKey(s, "minTranscriptionMs", "debugMinTranscriptionMs")
        copyKey(s, "phraseBreakSilenceMs", "debugPhraseBreakSilenceMs")
    }

    return flat
}

fun GatewaySettings.applyWebSocketSettingsPatch(patch: JSONObject?): GatewaySettingsPatchResult {
    if (patch == null || patch.length() <= 0) {
        return GatewaySettingsPatchResult(
            settings = this,
            appliedKeys = emptyList(),
            ignoredKeys = emptyList(),
            requiresCaptureRestart = false,
            requiresReconnect = false,
            requiresTtsRefresh = false
        )
    }

    var updated = this
    val applied = linkedSetOf<String>()
    val ignored = linkedSetOf<String>()

    fun rawValue(key: String): Any? {
        if (!patch.has(key) || patch.isNull(key)) {
            return null
        }
        return patch.opt(key)
    }

    fun stringValue(key: String): String? {
        return when (val value = rawValue(key)) {
            is String -> value.trim()
            is Number, is Boolean -> value.toString().trim()
            else -> null
        }
    }

    fun booleanValue(key: String): Boolean? {
        return when (val value = rawValue(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on", "sim" -> true
                "false", "0", "no", "off", "nao", "não" -> false
                else -> null
            }
            else -> null
        }
    }

    fun doubleValue(key: String): Double? {
        return when (val value = rawValue(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    fun intValue(key: String): Int? {
        return when (val value = rawValue(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    fun applyIfChanged(key: String, next: GatewaySettings) {
        if (next != updated) {
            updated = next
            applied += key
        }
    }

    fun parseAssistantVoiceStyle(key: String): AssistantVoiceStyle? {
        val normalized = stringValue(key)?.lowercase() ?: return null
        return AssistantVoiceStyle.entries.firstOrNull {
            it.persistedValue.equals(normalized, ignoreCase = true)
        }
    }

    fun parseTranscriptionMode(key: String): TranscriptionMode? {
        val normalized = stringValue(key)?.lowercase() ?: return null
        return when (normalized) {
            "remote" -> TranscriptionMode.REMOTE
            "local", "local_experimental" -> TranscriptionMode.LOCAL
            else -> null
        }
    }

    fun parseLocalExecutionMode(key: String): LocalExecutionMode? {
        val normalized = stringValue(key)?.lowercase() ?: return null
        return when (normalized) {
            "cpu" -> LocalExecutionMode.CPU
            "nnapi", "gpu" -> LocalExecutionMode.NNAPI
            else -> null
        }
    }

    fun parseScreenMode(key: String): ScreenMode? {
        val normalized = stringValue(key)?.lowercase() ?: return null
        return ScreenMode.entries.firstOrNull {
            it.persistedValue.equals(normalized, ignoreCase = true)
        }
    }

    val keys = mutableListOf<String>()
    val iterator = patch.keys()
    while (iterator.hasNext()) {
        keys += iterator.next()
    }

    for (key in keys) {
        when (key) {
            "localEndpointUrl" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(localEndpointUrl = value))
            }
            "openClawServerAddress" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(openClawServerAddress = value))
            }
            "openClawGatewayToken" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(openClawGatewayToken = value))
            }
            "openClawDeviceToken" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(openClawDeviceToken = value))
            }
            "openClawSessionKey" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(openClawSessionKey = value))
            }
            "openClawUserId" -> {
                // Aceita vazio (limpar o vinculo) — nao usa isBlank guard.
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(openClawUserId = value))
            }
            "whisperUrl" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(whisperUrl = value))
            }
            "remoteModel" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(remoteModel = value))
            }
            "whisperAuthToken" -> {
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(whisperAuthToken = value))
            }
            "autoStartEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(autoStartEnabled = value))
            }
            "cameraGestureEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(cameraGestureEnabled = value))
            }
            "voiceChannelSkillEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(voiceChannelSkillEnabled = value))
            }
            "voiceChannelWakeTerms" -> {
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(voiceChannelWakeTerms = value))
            }
            "voiceChannelFollowUpSeconds" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(voiceChannelFollowUpSeconds = value.coerceIn(3, 60)))
            }
            "voiceChannelIdlePromptSeconds" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(voiceChannelIdlePromptSeconds = value.coerceIn(30, 1800)))
            }
            "assistantVoiceEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(assistantVoiceEnabled = value))
            }
            "assistantVoiceStyle" -> {
                val value = parseAssistantVoiceStyle(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(assistantVoiceStyle = value))
            }
            "assistantSpeechRate" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(assistantSpeechRate = value.coerceIn(0.6, 1.8)))
            }
            "assistantPitch" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(assistantPitch = value.coerceIn(0.7, 1.4)))
            }
            "transcriptionMode" -> {
                val value = parseTranscriptionMode(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(transcriptionMode = value))
            }
            "localModelPath" -> {
                val value = stringValue(key)
                if (value == null || value.isBlank()) ignored += key else applyIfChanged(key, updated.copy(localModelPath = value))
            }
            "localExecutionMode" -> {
                val value = parseLocalExecutionMode(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(localExecutionMode = value))
            }
            "development" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(development = value))
            }
            "microphoneAutoSensitivityEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(microphoneAutoSensitivityEnabled = value))
            }
            "microphoneGain" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(microphoneGain = value.coerceIn(1.0, 6.0)))
            }
            "transcriptionRepeatSuppression" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(transcriptionRepeatSuppression = value.coerceIn(0.0, 1.0)))
            }
            "colloquialNormalizationStrength" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(colloquialNormalizationStrength = value.coerceIn(0.0, 1.0)))
            }
            "vadThreshold" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(vadThreshold = value.coerceIn(0.001, 0.05)))
            }
            "whisperVadFilter" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(whisperVadFilter = value))
            }
            "whisperConditionOnPreviousText" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(whisperConditionOnPreviousText = value))
            }
            "whisperNoSpeechThreshold" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(whisperNoSpeechThreshold = value.coerceIn(0.0, 1.0)))
            }
            "whisperCompressionRatioThreshold" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(whisperCompressionRatioThreshold = value.coerceIn(1.0, 5.0)))
            }
            "whisperRepetitionPenalty" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(whisperRepetitionPenalty = value.coerceIn(1.0, 2.0)))
            }
            "debugSpeechHoldMs" -> {
                if (patch.isNull(key)) {
                    applyIfChanged(key, updated.copy(debugSpeechHoldMs = null))
                } else {
                    val value = intValue(key)
                    if (value == null) ignored += key else applyIfChanged(key, updated.copy(debugSpeechHoldMs = value.coerceIn(100, 1200)))
                }
            }
            "debugMaxSpeechSegmentMs" -> {
                if (patch.isNull(key)) {
                    applyIfChanged(key, updated.copy(debugMaxSpeechSegmentMs = null))
                } else {
                    val value = intValue(key)
                    if (value == null) ignored += key else applyIfChanged(key, updated.copy(debugMaxSpeechSegmentMs = value.coerceIn(300, 5000)))
                }
            }
            "debugMinTranscriptionMs" -> {
                if (patch.isNull(key)) {
                    applyIfChanged(key, updated.copy(debugMinTranscriptionMs = null))
                } else {
                    val value = intValue(key)
                    if (value == null) ignored += key else applyIfChanged(key, updated.copy(debugMinTranscriptionMs = value.coerceIn(100, 3000)))
                }
            }
            "debugPhraseBreakSilenceMs" -> {
                if (patch.isNull(key)) {
                    applyIfChanged(key, updated.copy(debugPhraseBreakSilenceMs = null))
                } else {
                    val value = intValue(key)
                    if (value == null) ignored += key else applyIfChanged(key, updated.copy(debugPhraseBreakSilenceMs = value.coerceIn(500, 4000)))
                }
            }
            "transcriptionTerms" -> {
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(transcriptionTerms = value))
            }
            "transcriptionDictionary" -> {
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(transcriptionDictionary = value))
            }
            "screenMode" -> {
                val value = parseScreenMode(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(screenMode = value))
            }
            "screenHoldSeconds" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(screenHoldSeconds = value.coerceIn(1, 30)))
            }
            "transcriptClearTimeoutSecs" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(transcriptClearTimeoutSecs = value.coerceIn(0, 300)))
            }
            "openClawAccumulationWindowSecs" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(openClawAccumulationWindowSecs = value.coerceIn(1, 10)))
            }
            "noiseGateMultiplier" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(noiseGateMultiplier = value.coerceIn(0.5, 5.0)))
            }
            "minSpeechRms" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(minSpeechRms = value.coerceIn(0.001, 0.10)))
            }
            "minSpeechPeakNormalized" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(minSpeechPeakNormalized = value.coerceIn(0.001, 0.20)))
            }
            "minSpeechCandidateFrames" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(minSpeechCandidateFrames = value.coerceIn(1, 10)))
            }
            "maxTransientCrestFactor" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(maxTransientCrestFactor = value.coerceIn(1.0, 20.0)))
            }
            "minZeroCrossingRate" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(minZeroCrossingRate = value.coerceIn(0.001, 0.10)))
            }
            "maxZeroCrossingRate" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(maxZeroCrossingRate = value.coerceIn(0.10, 0.50)))
            }
            "ambientStabilityThreshold" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientStabilityThreshold = value.coerceIn(0.1, 1.0)))
            }
            "ambientGainStabilityThreshold" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainStabilityThreshold = value.coerceIn(0.1, 1.0)))
            }
            "ambientDynamicContrastMax" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientDynamicContrastMax = value.coerceIn(0.005, 0.20)))
            }
            "ambientRmsVarianceMax" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientRmsVarianceMax = value.coerceIn(0.01, 1.0)))
            }
            "ambientSpectrumMotionMax" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientSpectrumMotionMax = value.coerceIn(0.01, 0.50)))
            }
            "ambientSpeechPenalty" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientSpeechPenalty = value.coerceIn(0.0, 0.80)))
            }
            "ambientDetectionHoldFrames" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientDetectionHoldFrames = value.coerceIn(1, 30)))
            }
            "ambientDetectionReleaseFrames" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientDetectionReleaseFrames = value.coerceIn(1, 30)))
            }
            "ambientSpeechOverrideDynamicContrast" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientSpeechOverrideDynamicContrast = value.coerceIn(0.005, 0.20)))
            }
            "ambientSpeechOverrideSpectrumMotion" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientSpeechOverrideSpectrumMotion = value.coerceIn(0.01, 0.50)))
            }
            "ambientGainFactor" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainFactor = value.coerceIn(0.1, 1.0)))
            }
            "ambientGainStabilityReduction" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainStabilityReduction = value.coerceIn(0.0, 0.80)))
            }
            "ambientGainMinGain" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainMinGain = value.coerceIn(0.1, 2.0)))
            }
            "ambientGainSmoothingFast" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainSmoothingFast = value.coerceIn(0.05, 1.0)))
            }
            "ambientGainSmoothingSlow" -> {
                val value = doubleValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(ambientGainSmoothingSlow = value.coerceIn(0.01, 0.50)))
            }
            "apiEnabled" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(apiEnabled = value))
            }
            "apiPort" -> {
                val value = intValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(apiPort = value.coerceIn(1024, 65535)))
            }
            "apiBindAllInterfaces" -> {
                val value = booleanValue(key)
                if (value == null) ignored += key else applyIfChanged(key, updated.copy(apiBindAllInterfaces = value))
            }
            "apiToken" -> {
                val value = stringValue(key) ?: ""
                applyIfChanged(key, updated.copy(apiToken = value))
            }
            else -> ignored += key
        }
    }

    return GatewaySettingsPatchResult(
        settings = updated,
        appliedKeys = applied.toList(),
        ignoredKeys = ignored.toList(),
        requiresCaptureRestart = applied.any { it in CAPTURE_RESTART_PATCH_KEYS },
        requiresReconnect = applied.any { it in OPENCLAW_RECONNECT_PATCH_KEYS },
        requiresTtsRefresh = applied.any { it in TTS_REFRESH_PATCH_KEYS },
        requiresApiRestart = applied.any { it in API_RESTART_PATCH_KEYS }
    )
}