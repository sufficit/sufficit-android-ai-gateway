package com.sufficit.ai.gateway.config

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.time.Instant

private const val SETTINGS_BACKUP_SCHEMA = "openclaw-android-settings"
private const val SETTINGS_BACKUP_VERSION = 1

fun GatewaySettings.toJson(): JSONObject {
    return JSONObject().apply {
        put("general", JSONObject().apply {
            put("autoStart", autoStartEnabled)
            put("cameraGestureEnabled", cameraGestureEnabled)
            put("development", development)
        })
        put("connection", JSONObject().apply {
            put("serverAddress", openClawServerAddress)
            put("gatewayToken", openClawGatewayToken)
            put("deviceToken", openClawDeviceToken)
            put("sessionKey", openClawSessionKey)
            put("localEndpointUrl", localEndpointUrl)
        })
        put("transcription", JSONObject().apply {
            put("mode", transcriptionMode.persistedValue)
            put("remoteModel", remoteModel)
            put("whisperUrl", whisperUrl)
            put("whisperToken", whisperAuthToken)
            put("localModel", localModelPath)
            put("localExecution", localExecutionMode.persistedValue)
            put("terms", transcriptionTerms)
            put("dictionary", transcriptionDictionary)
            put("repeatSuppression", transcriptionRepeatSuppression)
            put("colloquialNormalization", colloquialNormalizationStrength)
        })
        put("whisper", JSONObject().apply {
            put("vadFilter", whisperVadFilter)
            put("conditionOnPreviousText", whisperConditionOnPreviousText)
            put("noSpeechThreshold", whisperNoSpeechThreshold)
            put("compressionRatioThreshold", whisperCompressionRatioThreshold)
            put("repetitionPenalty", whisperRepetitionPenalty)
        })
        put("audio", JSONObject().apply {
            put("vadThreshold", vadThreshold)
            put("autoSensitivity", microphoneAutoSensitivityEnabled)
            put("gain", microphoneGain)
            put("noiseGateMultiplier", noiseGateMultiplier)
            put("minSpeechRms", minSpeechRms)
            put("minSpeechPeakNormalized", minSpeechPeakNormalized)
            put("minSpeechCandidateFrames", minSpeechCandidateFrames)
            put("maxTransientCrestFactor", maxTransientCrestFactor)
            put("minZeroCrossingRate", minZeroCrossingRate)
            put("maxZeroCrossingRate", maxZeroCrossingRate)
            put("ambientStabilityThreshold", ambientStabilityThreshold)
            put("ambientGainStabilityThreshold", ambientGainStabilityThreshold)
            put("ambientDynamicContrastMax", ambientDynamicContrastMax)
            put("ambientRmsVarianceMax", ambientRmsVarianceMax)
            put("ambientSpectrumMotionMax", ambientSpectrumMotionMax)
            put("ambientSpeechPenalty", ambientSpeechPenalty)
            put("ambientDetectionHoldFrames", ambientDetectionHoldFrames)
            put("ambientDetectionReleaseFrames", ambientDetectionReleaseFrames)
            put("ambientSpeechOverrideDynamicContrast", ambientSpeechOverrideDynamicContrast)
            put("ambientSpeechOverrideSpectrumMotion", ambientSpeechOverrideSpectrumMotion)
            put("ambientGainFactor", ambientGainFactor)
            put("ambientGainStabilityReduction", ambientGainStabilityReduction)
            put("ambientGainMinGain", ambientGainMinGain)
            put("ambientGainSmoothingFast", ambientGainSmoothingFast)
            put("ambientGainSmoothingSlow", ambientGainSmoothingSlow)
        })
        put("assistant", JSONObject().apply {
            put("voiceEnabled", assistantVoiceEnabled)
            put("voiceStyle", assistantVoiceStyle.persistedValue)
            put("speechRate", assistantSpeechRate)
            put("pitch", assistantPitch)
        })
        put("voiceChannel", JSONObject().apply {
            put("enabled", voiceChannelSkillEnabled)
            put("wakeTerms", voiceChannelWakeTerms)
            put("followUpSecs", voiceChannelFollowUpSeconds)
            put("idlePromptSecs", voiceChannelIdlePromptSeconds)
            put("accumulationWindowSecs", openClawAccumulationWindowSecs)
        })
        put("screen", JSONObject().apply {
            put("mode", screenMode.persistedValue)
            put("holdSeconds", screenHoldSeconds)
        })
        put("card", JSONObject().apply {
            put("clearTimeoutSecs", transcriptClearTimeoutSecs)
        })
        val hasDebug = debugSpeechHoldMs != null || debugMaxSpeechSegmentMs != null ||
            debugMinTranscriptionMs != null || debugPhraseBreakSilenceMs != null
        if (hasDebug) {
            put("debug", JSONObject().apply {
                put("speechHoldMs", debugSpeechHoldMs ?: JSONObject.NULL)
                put("maxSpeechSegmentMs", debugMaxSpeechSegmentMs ?: JSONObject.NULL)
                put("minTranscriptionMs", debugMinTranscriptionMs ?: JSONObject.NULL)
                put("phraseBreakSilenceMs", debugPhraseBreakSilenceMs ?: JSONObject.NULL)
            })
        }
    }
}

fun GatewaySettings.toConfigJson(): JSONObject {
    return JSONObject().apply {
        put("schema", SETTINGS_BACKUP_SCHEMA)
        put("version", SETTINGS_BACKUP_VERSION)
        put("updatedAtUtc", Instant.now().toString())
        put("settings", toJson())
    }
}

fun GatewaySettings.toBackupJson(): JSONObject {
    return toConfigJson().apply {
        put("exportedAtUtc", Instant.now().toString())
    }
}

fun importGatewaySettingsFromJson(
    currentSettings: GatewaySettings,
    root: JSONObject
): GatewaySettingsPatchResult {
    val payload = root.optJSONObject("settings") ?: root
    return currentSettings.applyWebSocketSettingsPatch(flattenSectionedJson(payload))
}

fun exportGatewaySettingsBackup(
    context: Context,
    settings: GatewaySettings
): File {
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val target = File(exportDir, GatewayConfigCatalog.CONFIG_ASSET_NAME)
    GatewayConfigCatalog.saveRuntime(context, settings)
    target.writeText(settings.toBackupJson().toString(2))
    return target
}

fun shareGatewaySettingsBackup(
    context: Context,
    settings: GatewaySettings
): Boolean {
    val exported = exportGatewaySettingsBackup(context, settings)
    val exportUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        exported
    )
    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, exportUri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Configuracao AI Gateway")
        putExtra(android.content.Intent.EXTRA_TEXT, "Backup JSON das configuracoes do AI Gateway.")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(shareIntent, "Exportar configuracao JSON").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
    return true
}

fun readGatewaySettingsBackup(
    context: Context,
    uri: Uri,
    currentSettings: GatewaySettings
): Result<GatewaySettingsPatchResult> {
    return runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Nao foi possivel ler o arquivo JSON selecionado.")
        val parsed = JSONObject(raw)
        val schema = parsed.optString("schema").trim()
        if (schema.isNotBlank() && !schema.equals(SETTINGS_BACKUP_SCHEMA, ignoreCase = true)) {
            error("Arquivo JSON nao reconhecido como backup de configuracao do AI Gateway.")
        }
        importGatewaySettingsFromJson(currentSettings, parsed)
    }
}