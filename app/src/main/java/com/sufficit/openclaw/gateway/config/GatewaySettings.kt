package com.sufficit.openclaw.gateway.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

enum class ScreenMode(val persistedValue: String) {
    ALWAYS_ON("always_on"),
    ALWAYS_OFF("always_off"),
    ACTIVITY("activity");

    companion object {
        fun fromPersistedValue(value: String?): ScreenMode {
            return entries.firstOrNull { it.persistedValue == value } ?: ACTIVITY
        }
    }
}

enum class TranscriptionMode(val persistedValue: String) {
    REMOTE("remote"),
    LOCAL("local");

    companion object {
        fun fromPersistedValue(value: String?): TranscriptionMode {
            if (value == "local_experimental") {
                return LOCAL
            }
            return entries.firstOrNull { it.persistedValue == value } ?: REMOTE
        }
    }
}

enum class LocalExecutionMode(val persistedValue: String) {
    CPU("cpu"),
    NNAPI("nnapi");

    companion object {
        fun fromPersistedValue(value: String?): LocalExecutionMode {
            if (value == "gpu") {
                return NNAPI
            }
            return entries.firstOrNull { it.persistedValue == value } ?: CPU
        }
    }
}

enum class AssistantVoiceStyle(val persistedValue: String) {
    SYSTEM("system"),
    FEMININE("feminine"),
    MASCULINE("masculine");

    companion object {
        fun fromPersistedValue(value: String?): AssistantVoiceStyle {
            return entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
        }
    }
}

data class GatewaySettings(
    val localEndpointUrl: String = GatewaySettingsStore.DEFAULT_LOCAL_ENDPOINT_URL,
    val openClawServerAddress: String = GatewaySettingsStore.DEFAULT_OPENCLAW_SERVER_ADDRESS,
    val openClawGatewayToken: String = GatewaySettingsStore.DEFAULT_OPENCLAW_GATEWAY_TOKEN,
    val openClawDeviceToken: String = GatewaySettingsStore.DEFAULT_OPENCLAW_DEVICE_TOKEN,
    val openClawSessionKey: String = GatewaySettingsStore.DEFAULT_OPENCLAW_SESSION_KEY,
    val whisperUrl: String = GatewaySettingsStore.DEFAULT_WHISPER_URL,
    val remoteModel: String = GatewaySettingsStore.DEFAULT_REMOTE_MODEL,
    val whisperAuthToken: String = "",
    val autoStartEnabled: Boolean = GatewaySettingsStore.DEFAULT_AUTO_START_ENABLED,
    val cameraGestureEnabled: Boolean = GatewaySettingsStore.DEFAULT_CAMERA_GESTURE_ENABLED,
    val voiceChannelSkillEnabled: Boolean = GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_SKILL_ENABLED,
    val voiceChannelWakeTerms: String = GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_WAKE_TERMS,
    val voiceChannelFollowUpSeconds: Int = GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS,
    val voiceChannelIdlePromptSeconds: Int = GatewaySettingsStore.DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS,
    val assistantVoiceEnabled: Boolean = GatewaySettingsStore.DEFAULT_ASSISTANT_VOICE_ENABLED,
    val assistantVoiceStyle: AssistantVoiceStyle = GatewaySettingsStore.DEFAULT_ASSISTANT_VOICE_STYLE,
    val assistantSpeechRate: Double = GatewaySettingsStore.DEFAULT_ASSISTANT_SPEECH_RATE,
    val assistantPitch: Double = GatewaySettingsStore.DEFAULT_ASSISTANT_PITCH,
    val transcriptionMode: TranscriptionMode = GatewaySettingsStore.DEFAULT_TRANSCRIPTION_MODE,
    val localModelPath: String = GatewaySettingsStore.DEFAULT_LOCAL_MODEL_PATH,
    val localExecutionMode: LocalExecutionMode = GatewaySettingsStore.DEFAULT_LOCAL_EXECUTION_MODE,
    val development: Boolean = GatewaySettingsStore.DEFAULT_DEVELOPMENT,
    val microphoneAutoSensitivityEnabled: Boolean = GatewaySettingsStore.DEFAULT_MICROPHONE_AUTO_SENSITIVITY_ENABLED,
    val microphoneGain: Double = GatewaySettingsStore.DEFAULT_MICROPHONE_GAIN,
    val transcriptionRepeatSuppression: Double = GatewaySettingsStore.DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION,
    val colloquialNormalizationStrength: Double = GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH,
    val vadThreshold: Double = GatewaySettingsStore.DEFAULT_VAD_THRESHOLD,
    val whisperVadFilter: Boolean = GatewaySettingsStore.DEFAULT_WHISPER_VAD_FILTER,
    val whisperConditionOnPreviousText: Boolean = GatewaySettingsStore.DEFAULT_WHISPER_CONDITION_ON_PREVIOUS_TEXT,
    val whisperNoSpeechThreshold: Double = GatewaySettingsStore.DEFAULT_WHISPER_NO_SPEECH_THRESHOLD,
    val whisperCompressionRatioThreshold: Double = GatewaySettingsStore.DEFAULT_WHISPER_COMPRESSION_RATIO_THRESHOLD,
    val whisperRepetitionPenalty: Double = GatewaySettingsStore.DEFAULT_WHISPER_REPETITION_PENALTY,
    val debugSpeechHoldMs: Int? = null,
    val debugMaxSpeechSegmentMs: Int? = null,
    val debugMinTranscriptionMs: Int? = null,
    val debugPhraseBreakSilenceMs: Int? = null,
    val transcriptionTerms: String = "",
    val transcriptionDictionary: String = "",
    val screenMode: ScreenMode = GatewaySettingsStore.DEFAULT_SCREEN_MODE,
    val screenHoldSeconds: Int = GatewaySettingsStore.DEFAULT_SCREEN_HOLD_SECONDS,
    val transcriptClearTimeoutSecs: Int = GatewaySettingsStore.DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS,
    val openClawAccumulationWindowSecs: Int = GatewaySettingsStore.DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS,
    // VAD / speech detection thresholds
    val noiseGateMultiplier: Double = GatewaySettingsStore.DEFAULT_NOISE_GATE_MULTIPLIER,
    val minSpeechRms: Double = GatewaySettingsStore.DEFAULT_MIN_SPEECH_RMS,
    val minSpeechPeakNormalized: Double = GatewaySettingsStore.DEFAULT_MIN_SPEECH_PEAK_NORMALIZED,
    val minSpeechCandidateFrames: Int = GatewaySettingsStore.DEFAULT_MIN_SPEECH_CANDIDATE_FRAMES,
    val maxTransientCrestFactor: Double = GatewaySettingsStore.DEFAULT_MAX_TRANSIENT_CREST_FACTOR,
    val minZeroCrossingRate: Double = GatewaySettingsStore.DEFAULT_MIN_ZERO_CROSSING_RATE,
    val maxZeroCrossingRate: Double = GatewaySettingsStore.DEFAULT_MAX_ZERO_CROSSING_RATE,
    // Ambient noise detection
    val ambientStabilityThreshold: Double = GatewaySettingsStore.DEFAULT_AMBIENT_STABILITY_THRESHOLD,
    val ambientGainStabilityThreshold: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_STABILITY_THRESHOLD,
    val ambientDynamicContrastMax: Double = GatewaySettingsStore.DEFAULT_AMBIENT_DYNAMIC_CONTRAST_MAX,
    val ambientRmsVarianceMax: Double = GatewaySettingsStore.DEFAULT_AMBIENT_RMS_VARIANCE_MAX,
    val ambientSpectrumMotionMax: Double = GatewaySettingsStore.DEFAULT_AMBIENT_SPECTRUM_MOTION_MAX,
    val ambientSpeechPenalty: Double = GatewaySettingsStore.DEFAULT_AMBIENT_SPEECH_PENALTY,
    val ambientDetectionHoldFrames: Int = GatewaySettingsStore.DEFAULT_AMBIENT_DETECTION_HOLD_FRAMES,
    val ambientDetectionReleaseFrames: Int = GatewaySettingsStore.DEFAULT_AMBIENT_DETECTION_RELEASE_FRAMES,
    val ambientSpeechOverrideDynamicContrast: Double = GatewaySettingsStore.DEFAULT_AMBIENT_SPEECH_OVERRIDE_DYNAMIC_CONTRAST,
    val ambientSpeechOverrideSpectrumMotion: Double = GatewaySettingsStore.DEFAULT_AMBIENT_SPEECH_OVERRIDE_SPECTRUM_MOTION,
    // Auto-gain control
    val ambientGainFactor: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_FACTOR,
    val ambientGainStabilityReduction: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_STABILITY_REDUCTION,
    val ambientGainMinGain: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_MIN_GAIN,
    val ambientGainSmoothingFast: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_SMOOTHING_FAST,
    val ambientGainSmoothingSlow: Double = GatewaySettingsStore.DEFAULT_AMBIENT_GAIN_SMOOTHING_SLOW
) {
    val openClawGatewayUrl: String
        get() = GatewaySettingsStore.buildGatewayUrl(openClawServerAddress)
}

class GatewaySettingsStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val seedSettings = GatewayConfigCatalog.ensureSeedLoaded(context)

    fun load(): GatewaySettings {
        val fileSettings = GatewayConfigCatalog.loadRuntime(context)
        if (fileSettings != null) {
            val normalized = normalizeLoadedSettings(fileSettings)
            if (normalized != fileSettings) {
                save(normalized)
            }
            return normalized
        }

        val migratedLegacy = loadFromLegacyPreferences()
        save(migratedLegacy)
        clearLegacyPreferences()
        return migratedLegacy
    }

    private fun loadFromLegacyPreferences(): GatewaySettings {
        val loadedModelPath = preferences.getString(KEY_LOCAL_MODEL_PATH, DEFAULT_LOCAL_MODEL_PATH)
            ?.trim()
            ?.ifEmpty { DEFAULT_LOCAL_MODEL_PATH }
            ?: DEFAULT_LOCAL_MODEL_PATH
        val defaultModelName = File(DEFAULT_LOCAL_MODEL_PATH).name
        val rawModelName = normalizeStoredModelName(loadedModelPath, defaultModelName)
        val loadedModelName = normalizeModelName(rawModelName, defaultModelName)
        val fixedModelPath = File(File(context.filesDir, "models"), loadedModelName).absolutePath
        Log.i(
            "GatewaySettingsStore",
            "load modelPath raw=$loadedModelPath, rawName=$rawModelName, normalizedName=$loadedModelName, fixed=$fixedModelPath"
        )
        val loadedExecutionMode = LocalExecutionMode.fromPersistedValue(
            preferences.getString(KEY_LOCAL_EXECUTION_MODE, DEFAULT_LOCAL_EXECUTION_MODE.persistedValue)
        )
        val loadedWhisperUrl = preferences.getString(KEY_WHISPER_URL, seedSettings.whisperUrl)
            ?.trim()
            ?.ifEmpty { seedSettings.whisperUrl }
            ?: seedSettings.whisperUrl
        val storedTranscriptionMode = TranscriptionMode.fromPersistedValue(
            preferences.getString(
                KEY_TRANSCRIPTION_MODE,
                DEFAULT_TRANSCRIPTION_MODE.persistedValue
            )
        )
        val loadedTranscriptionMode = resolveTranscriptionMode(
            storedMode = storedTranscriptionMode,
            whisperUrl = loadedWhisperUrl,
            localModelPath = fixedModelPath
        )
        val normalizedModelPath = fixedModelPath
        val normalizedExecutionMode = loadedExecutionMode
        val storedOpenClawSessionKey = preferences.getString(KEY_OPENCLAW_SESSION_KEY, DEFAULT_OPENCLAW_SESSION_KEY)
            ?.trim()
            ?.ifEmpty { DEFAULT_OPENCLAW_SESSION_KEY }
            ?: DEFAULT_OPENCLAW_SESSION_KEY
        val storedServerAddress = preferences.getString(KEY_OPENCLAW_SERVER_ADDRESS, null)?.trim()
        val normalizedServerAddress = if (storedServerAddress.isNullOrBlank()) {
            val oldUrl = preferences.getString(KEY_OPENCLAW_GATEWAY_URL, null)?.trim().orEmpty()
            extractServerAddress(if (oldUrl.isBlank()) DEFAULT_OPENCLAW_GATEWAY_URL else oldUrl)
        } else {
            normalizeOpenClawServerAddress(storedServerAddress)
        }
        val normalizedOpenClawSessionKey = normalizeOpenClawSessionKey(storedOpenClawSessionKey)
        if (
            normalizedOpenClawSessionKey != storedOpenClawSessionKey ||
            storedServerAddress.isNullOrBlank() ||
            normalizeOpenClawServerAddress(storedServerAddress) != storedServerAddress
        ) {
            Log.i(
                "GatewaySettingsStore",
                "migrating openclaw settings: server_address=$normalizedServerAddress, session_key=$storedOpenClawSessionKey -> $normalizedOpenClawSessionKey"
            )
        }

        return GatewaySettings(
            localEndpointUrl = preferences.getString(KEY_LOCAL_ENDPOINT_URL, seedSettings.localEndpointUrl)
                ?.trim()
                ?.ifEmpty { seedSettings.localEndpointUrl }
                ?: seedSettings.localEndpointUrl,
            openClawServerAddress = normalizedServerAddress,
            openClawGatewayToken = preferences.getString(KEY_OPENCLAW_GATEWAY_TOKEN, DEFAULT_OPENCLAW_GATEWAY_TOKEN)
                ?.trim()
                ?.ifEmpty { DEFAULT_OPENCLAW_GATEWAY_TOKEN }
                ?: DEFAULT_OPENCLAW_GATEWAY_TOKEN,
            openClawDeviceToken = preferences.getString(KEY_OPENCLAW_DEVICE_TOKEN, DEFAULT_OPENCLAW_DEVICE_TOKEN)
                ?.trim()
                ?.ifEmpty { DEFAULT_OPENCLAW_DEVICE_TOKEN }
                ?: DEFAULT_OPENCLAW_DEVICE_TOKEN,
            openClawSessionKey = normalizedOpenClawSessionKey,
            whisperUrl = loadedWhisperUrl,
            remoteModel = preferences.getString(KEY_REMOTE_MODEL, seedSettings.remoteModel)
                ?.trim()
                ?.ifEmpty { seedSettings.remoteModel }
                ?: seedSettings.remoteModel,
            whisperAuthToken = preferences.getString(
                KEY_WHISPER_AUTH_TOKEN,
                seedSettings.whisperAuthToken
            )
                ?.trim()
                ?.ifEmpty { seedSettings.whisperAuthToken }
                ?: seedSettings.whisperAuthToken,
            autoStartEnabled = preferences.getBoolean(
                KEY_AUTO_START_ENABLED,
                DEFAULT_AUTO_START_ENABLED
            ),
            cameraGestureEnabled = preferences.getBoolean(
                KEY_CAMERA_GESTURE_ENABLED,
                DEFAULT_CAMERA_GESTURE_ENABLED
            ),
            voiceChannelSkillEnabled = preferences.getBoolean(
                KEY_VOICE_CHANNEL_SKILL_ENABLED,
                DEFAULT_VOICE_CHANNEL_SKILL_ENABLED
            ),
            voiceChannelWakeTerms = preferences.getString(
                KEY_VOICE_CHANNEL_WAKE_TERMS,
                DEFAULT_VOICE_CHANNEL_WAKE_TERMS
            ) ?: DEFAULT_VOICE_CHANNEL_WAKE_TERMS,
            voiceChannelFollowUpSeconds = preferences.getInt(
                KEY_VOICE_CHANNEL_FOLLOW_UP_SECONDS,
                DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS
            ),
            voiceChannelIdlePromptSeconds = preferences.getInt(
                KEY_VOICE_CHANNEL_IDLE_PROMPT_SECONDS,
                DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS
            ),
            assistantVoiceEnabled = preferences.getBoolean(
                KEY_ASSISTANT_VOICE_ENABLED,
                DEFAULT_ASSISTANT_VOICE_ENABLED
            ),
            assistantVoiceStyle = AssistantVoiceStyle.fromPersistedValue(
                preferences.getString(
                    KEY_ASSISTANT_VOICE_STYLE,
                    DEFAULT_ASSISTANT_VOICE_STYLE.persistedValue
                )
            ),
            assistantSpeechRate = preferences.getFloat(
                KEY_ASSISTANT_SPEECH_RATE,
                DEFAULT_ASSISTANT_SPEECH_RATE.toFloat()
            ).toDouble(),
            assistantPitch = preferences.getFloat(
                KEY_ASSISTANT_PITCH,
                DEFAULT_ASSISTANT_PITCH.toFloat()
            ).toDouble(),
            transcriptionMode = loadedTranscriptionMode,
            localModelPath = normalizedModelPath,
            localExecutionMode = normalizedExecutionMode,
            development = preferences.getBoolean(KEY_DEVELOPMENT, DEFAULT_DEVELOPMENT),
            microphoneAutoSensitivityEnabled = preferences.getBoolean(
                KEY_MICROPHONE_AUTO_SENSITIVITY_ENABLED,
                DEFAULT_MICROPHONE_AUTO_SENSITIVITY_ENABLED
            ),
            microphoneGain = preferences.getFloat(
                KEY_MICROPHONE_GAIN,
                DEFAULT_MICROPHONE_GAIN.toFloat()
            ).toDouble(),
            transcriptionRepeatSuppression = preferences.getFloat(
                KEY_TRANSCRIPTION_REPEAT_SUPPRESSION,
                DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION.toFloat()
            ).toDouble(),
            colloquialNormalizationStrength = preferences.getFloat(
                KEY_COLLOQUIAL_NORMALIZATION_STRENGTH,
                DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH.toFloat()
            ).toDouble(),
            vadThreshold = preferences.getFloat(
                KEY_VAD_THRESHOLD,
                DEFAULT_VAD_THRESHOLD.toFloat()
            ).toDouble(),
            whisperVadFilter = preferences.getBoolean(
                KEY_WHISPER_VAD_FILTER,
                DEFAULT_WHISPER_VAD_FILTER
            ),
            whisperConditionOnPreviousText = preferences.getBoolean(
                KEY_WHISPER_CONDITION_ON_PREVIOUS_TEXT,
                DEFAULT_WHISPER_CONDITION_ON_PREVIOUS_TEXT
            ),
            whisperNoSpeechThreshold = preferences.getFloat(
                KEY_WHISPER_NO_SPEECH_THRESHOLD,
                DEFAULT_WHISPER_NO_SPEECH_THRESHOLD.toFloat()
            ).toDouble(),
            whisperCompressionRatioThreshold = preferences.getFloat(
                KEY_WHISPER_COMPRESSION_RATIO_THRESHOLD,
                DEFAULT_WHISPER_COMPRESSION_RATIO_THRESHOLD.toFloat()
            ).toDouble(),
            whisperRepetitionPenalty = preferences.getFloat(
                KEY_WHISPER_REPETITION_PENALTY,
                DEFAULT_WHISPER_REPETITION_PENALTY.toFloat()
            ).toDouble(),
            debugSpeechHoldMs = preferences.getInt(KEY_DEBUG_SPEECH_HOLD_MS, -1).takeIf { it > 0 },
            debugMaxSpeechSegmentMs = preferences.getInt(KEY_DEBUG_MAX_SPEECH_SEGMENT_MS, -1).takeIf { it > 0 },
            debugMinTranscriptionMs = preferences.getInt(KEY_DEBUG_MIN_TRANSCRIPTION_MS, -1).takeIf { it > 0 },
            debugPhraseBreakSilenceMs = preferences.getInt(KEY_DEBUG_PHRASE_BREAK_SILENCE_MS, -1).takeIf { it > 0 },
            transcriptionTerms = preferences.getString(KEY_TRANSCRIPTION_TERMS, "") ?: "",
            transcriptionDictionary = preferences.getString(KEY_TRANSCRIPTION_DICTIONARY, "") ?: "",
            screenMode = ScreenMode.fromPersistedValue(
                preferences.getString(KEY_SCREEN_MODE, DEFAULT_SCREEN_MODE.persistedValue)
            ),
            screenHoldSeconds = preferences.getInt(
                KEY_SCREEN_HOLD_SECONDS,
                DEFAULT_SCREEN_HOLD_SECONDS
            )
        ).also { normalized ->
            // Migra configuracoes antigas para o perfil local suportado atualmente.
            if (
                normalizedModelPath != loadedModelPath ||
                normalizedExecutionMode != loadedExecutionMode ||
                loadedTranscriptionMode != storedTranscriptionMode
            ) {
                Log.i(
                    "GatewaySettingsStore",
                    "migrating settings local_model_path=$loadedModelPath -> $normalizedModelPath, local_execution_mode=${loadedExecutionMode.persistedValue} -> ${normalizedExecutionMode.persistedValue}, transcription_mode=${storedTranscriptionMode.persistedValue} -> ${loadedTranscriptionMode.persistedValue}"
                )
                save(normalized)
            }
        }
    }

    private fun normalizeLoadedSettings(settings: GatewaySettings): GatewaySettings {
        val loadedModelPath = settings.localModelPath.trim().ifEmpty { DEFAULT_LOCAL_MODEL_PATH }
        val defaultModelName = File(DEFAULT_LOCAL_MODEL_PATH).name
        val rawModelName = normalizeStoredModelName(loadedModelPath, defaultModelName)
        val loadedModelName = normalizeModelName(rawModelName, defaultModelName)
        val fixedModelPath = File(File(context.filesDir, "models"), loadedModelName).absolutePath
        val loadedWhisperUrl = settings.whisperUrl.trim().ifEmpty { seedSettings.whisperUrl }
        val loadedTranscriptionMode = resolveTranscriptionMode(
            storedMode = settings.transcriptionMode,
            whisperUrl = loadedWhisperUrl,
            localModelPath = fixedModelPath
        )
        val normalizedServerAddress = normalizeOpenClawServerAddress(settings.openClawServerAddress)
        val normalizedOpenClawSessionKey = normalizeOpenClawSessionKey(settings.openClawSessionKey)

        return settings.copy(
            localEndpointUrl = settings.localEndpointUrl.trim().ifEmpty { seedSettings.localEndpointUrl },
            openClawServerAddress = normalizedServerAddress,
            openClawGatewayToken = settings.openClawGatewayToken.trim().ifEmpty { seedSettings.openClawGatewayToken },
            openClawDeviceToken = settings.openClawDeviceToken.trim().ifEmpty { seedSettings.openClawDeviceToken },
            openClawSessionKey = normalizedOpenClawSessionKey,
            whisperUrl = loadedWhisperUrl,
            remoteModel = settings.remoteModel.trim().ifEmpty { seedSettings.remoteModel },
            whisperAuthToken = settings.whisperAuthToken.trim().ifEmpty { seedSettings.whisperAuthToken },
            cameraGestureEnabled = settings.cameraGestureEnabled,
            transcriptionMode = loadedTranscriptionMode,
            localModelPath = fixedModelPath,
            whisperNoSpeechThreshold = settings.whisperNoSpeechThreshold.coerceIn(0.0, 1.0),
            whisperCompressionRatioThreshold = settings.whisperCompressionRatioThreshold.coerceIn(1.0, 5.0),
            whisperRepetitionPenalty = settings.whisperRepetitionPenalty.coerceIn(1.0, 2.0)
        )
    }

    private fun normalizeModelName(rawModelName: String, defaultModelName: String): String {
        val trimmed = rawModelName.trim()
        if (trimmed.isEmpty()) {
            return defaultModelName
        }

        val normalized = trimmed.lowercase()
        val migratedName = when (normalized) {
            // Modelo antigo usado nos primeiros testes, indisponivel no fluxo atual.
            "ggml-large-v3-turbo-q4_0.bin" -> defaultModelName
            "ggml-large-v3-turbo-q5_0.bin" -> defaultModelName
            else -> trimmed
        }

        val profile = migratedName.lowercase()
        if (LocalModelCatalog.findById(profile) != null) {
            return LocalModelCatalog.findById(profile)?.id ?: defaultModelName
        }
        val isSupportedProfile = profile.endsWith(".bin") ||
            profile.endsWith(".onnx") ||
            profile.endsWith(".ort")
        return if (isSupportedProfile) {
            migratedName
        } else {
            defaultModelName
        }
    }

    private fun normalizeStoredModelName(storedValue: String, defaultModelName: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) {
            return defaultModelName
        }

        val byPath = LocalModelCatalog.findByPath(trimmed)
        if (byPath != null) {
            return byPath.id
        }

        return File(trimmed).name.ifBlank { defaultModelName }
    }

    private fun normalizeOpenClawSessionKey(value: String): String {
        val normalized = value.trim().ifBlank { DEFAULT_OPENCLAW_SESSION_KEY }
        val androidIdentity = buildAndroidIdentity()
        val prefixedAndroidIdentity = prefixedAndroidIdentity()
        val legacyAndroidIdentity = legacyAndroidIdentity()
        return when {
            normalized.equals("android-room", ignoreCase = true) -> androidIdentity
            normalized.equals("android:room", ignoreCase = true) -> androidIdentity
            normalized.endsWith(":android-room", ignoreCase = true) ->
                normalized.replaceAfterLast(':', androidIdentity)
            normalized.endsWith(":android:room", ignoreCase = true) ->
                normalized.replaceAfterLast(':', androidIdentity)
            normalized.equals(prefixedAndroidIdentity, ignoreCase = true) -> androidIdentity
            normalized.endsWith(":$prefixedAndroidIdentity", ignoreCase = true) ->
                replaceTrailingIdentity(normalized, prefixedAndroidIdentity, androidIdentity)
            normalized.equals(legacyAndroidIdentity, ignoreCase = true) -> androidIdentity
            normalized.endsWith(":$legacyAndroidIdentity", ignoreCase = true) ->
                replaceTrailingIdentity(normalized, legacyAndroidIdentity, androidIdentity)
            normalized.substringAfterLast(':', "").startsWith("android-", ignoreCase = true) ->
                normalized.replaceAfterLast(':', canonicalizeAndroidIdentity(normalized.substringAfterLast(':')))
            else -> normalized
        }
    }

    private fun normalizeOpenClawServerAddress(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return seedSettings.openClawServerAddress
        if (normalized.contains("://")) return extractServerAddress(normalized)
        return normalized
    }

    private fun buildAndroidIdentity(): String {
        val manufacturerSlug = sanitizeSlug(Build.MANUFACTURER.orEmpty())
        val modelSlug = sanitizeSlug(Build.MODEL.orEmpty()).ifBlank { "android" }
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().trim().lowercase()
        val stableSuffix = androidId.ifBlank { "device" }
        return listOf(manufacturerSlug, modelSlug, stableSuffix)
            .filter { it.isNotBlank() }
            .joinToString(":")
            .replace(Regex(":+"), ":")
            .trim(':')
    }

    private fun prefixedAndroidIdentity(): String {
        return listOf("android", buildAndroidIdentity())
            .filter { it.isNotBlank() }
            .joinToString(":")
            .replace(Regex(":+"), ":")
            .trim(':')
    }

    private fun legacyAndroidIdentity(): String {
        val manufacturerSlug = sanitizeSlug(Build.MANUFACTURER.orEmpty())
        val modelSlug = sanitizeSlug(Build.MODEL.orEmpty()).ifBlank { "android" }
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().trim().lowercase()
        val stableSuffix = androidId.ifBlank { "device" }
        return listOf("android", manufacturerSlug, modelSlug, stableSuffix)
            .filter { it.isNotBlank() }
            .joinToString("-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun canonicalizeAndroidIdentity(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("android:", ignoreCase = true) ->
                trimmed.substringAfter(':').trim(':')
            !trimmed.startsWith("android-", ignoreCase = true) -> trimmed
            else -> {
            val pieces = value.split('-').filter { it.isNotBlank() }
            if (pieces.size < 4) {
                trimmed.removePrefix("android-").trim('-').replace(Regex("-+"), ":")
            } else {
                val manufacturer = pieces[1]
                val suffix = pieces.last()
                val model = pieces.subList(2, pieces.lastIndex).joinToString("-")
                listOf(manufacturer, model, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString(":")
            }
            }
        }
    }

    private fun replaceTrailingIdentity(sessionKey: String, previousIdentity: String, nextIdentity: String): String {
        val prefix = sessionKey.removeSuffix(previousIdentity).trimEnd(':')
        return if (prefix.isBlank()) nextIdentity else "$prefix:$nextIdentity"
    }

    private fun sanitizeSlug(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    fun save(settings: GatewaySettings) {
        val normalized = normalizeLoadedSettings(settings)
        GatewayConfigCatalog.saveRuntime(context, normalized)
    }

    private fun clearLegacyPreferences() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "gateway_settings"
        private const val KEY_LOCAL_ENDPOINT_URL = "local_endpoint_url"
        private const val KEY_OPENCLAW_GATEWAY_URL = "openclaw_gateway_url"
        private const val KEY_OPENCLAW_SERVER_ADDRESS = "openclaw_server_address"
        private const val KEY_OPENCLAW_GATEWAY_TOKEN = "openclaw_gateway_token"
        private const val KEY_OPENCLAW_DEVICE_TOKEN = "openclaw_device_token"
        private const val KEY_OPENCLAW_SESSION_KEY = "openclaw_session_key"
        private const val KEY_WHISPER_URL = "whisper_url"
        private const val KEY_REMOTE_MODEL = "remote_model"
        private const val KEY_WHISPER_AUTH_TOKEN = "whisper_auth_token"
        private const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        private const val KEY_CAMERA_GESTURE_ENABLED = "camera_gesture_enabled"
        private const val KEY_VOICE_CHANNEL_SKILL_ENABLED = "voice_channel_skill_enabled"
        private const val KEY_VOICE_CHANNEL_WAKE_TERMS = "voice_channel_wake_terms"
        private const val KEY_VOICE_CHANNEL_FOLLOW_UP_SECONDS = "voice_channel_follow_up_seconds"
        private const val KEY_VOICE_CHANNEL_IDLE_PROMPT_SECONDS = "voice_channel_idle_prompt_seconds"
        private const val KEY_ASSISTANT_VOICE_ENABLED = "assistant_voice_enabled"
        private const val KEY_ASSISTANT_VOICE_STYLE = "assistant_voice_style"
        private const val KEY_ASSISTANT_SPEECH_RATE = "assistant_speech_rate"
        private const val KEY_ASSISTANT_PITCH = "assistant_pitch"
        private const val KEY_TRANSCRIPTION_MODE = "transcription_mode"
        private const val KEY_LOCAL_MODEL_PATH = "local_model_path"
        private const val KEY_LOCAL_EXECUTION_MODE = "local_execution_mode"
        private const val KEY_DEVELOPMENT = "development"
        private const val KEY_MICROPHONE_AUTO_SENSITIVITY_ENABLED = "microphone_auto_sensitivity_enabled"
        private const val KEY_MICROPHONE_GAIN = "microphone_gain"
        private const val KEY_TRANSCRIPTION_REPEAT_SUPPRESSION = "transcription_repeat_suppression"
        private const val KEY_COLLOQUIAL_NORMALIZATION_STRENGTH = "colloquial_normalization_strength"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_WHISPER_VAD_FILTER = "whisper_vad_filter"
        private const val KEY_WHISPER_CONDITION_ON_PREVIOUS_TEXT = "whisper_condition_on_previous_text"
        private const val KEY_WHISPER_NO_SPEECH_THRESHOLD = "whisper_no_speech_threshold"
        private const val KEY_WHISPER_COMPRESSION_RATIO_THRESHOLD = "whisper_compression_ratio_threshold"
        private const val KEY_WHISPER_REPETITION_PENALTY = "whisper_repetition_penalty"
        private const val KEY_DEBUG_SPEECH_HOLD_MS = "debug_speech_hold_ms"
        private const val KEY_DEBUG_MAX_SPEECH_SEGMENT_MS = "debug_max_speech_segment_ms"
        private const val KEY_DEBUG_MIN_TRANSCRIPTION_MS = "debug_min_transcription_ms"
        private const val KEY_DEBUG_PHRASE_BREAK_SILENCE_MS = "debug_phrase_break_silence_ms"
        private const val KEY_TRANSCRIPTION_TERMS = "transcription_terms"
        private const val KEY_TRANSCRIPTION_DICTIONARY = "transcription_dictionary"
        private const val KEY_SCREEN_MODE = "screen_mode"
        private const val KEY_SCREEN_HOLD_SECONDS = "screen_hold_seconds"

        val DEFAULT_LOCAL_ENDPOINT_URL: String
            get() = GatewayConfigCatalog.currentSeed().localEndpointUrl
        const val LEGACY_WRONG_OPENCLAW_ENDPOINT_URL = "wss://your-openclaw-host.example.com/"
        val DEFAULT_OPENCLAW_GATEWAY_URL: String
            get() = "wss://${DEFAULT_OPENCLAW_SERVER_ADDRESS}/ws/android"
        val DEFAULT_OPENCLAW_SERVER_ADDRESS: String
            get() = GatewayConfigCatalog.currentSeed().openClawServerAddress

        fun buildGatewayUrl(serverAddress: String): String {
            val address = serverAddress.trim()
            if (address.isBlank()) return "wss://${DEFAULT_OPENCLAW_SERVER_ADDRESS}/ws/android"
            if (address.startsWith("wss://", ignoreCase = true) ||
                address.startsWith("ws://", ignoreCase = true)
            ) return address
            return "wss://$address/ws/android"
        }

        private fun extractServerAddress(url: String): String {
            return url.trim()
                .removePrefix("wss://")
                .removePrefix("ws://")
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .ifBlank { DEFAULT_OPENCLAW_SERVER_ADDRESS }
        }
        val DEFAULT_OPENCLAW_GATEWAY_TOKEN: String
            get() = GatewayConfigCatalog.currentSeed().openClawGatewayToken
        val DEFAULT_OPENCLAW_DEVICE_TOKEN: String
            get() = GatewayConfigCatalog.currentSeed().openClawDeviceToken
        val DEFAULT_OPENCLAW_SESSION_KEY: String
            get() = GatewayConfigCatalog.currentSeed().openClawSessionKey
        val DEFAULT_WHISPER_URL: String
            get() = GatewayConfigCatalog.currentSeed().whisperUrl
        val DEFAULT_REMOTE_MODEL: String
            get() = GatewayConfigCatalog.currentSeed().remoteModel
        val DEFAULT_AUTO_START_ENABLED: Boolean
            get() = GatewayConfigCatalog.currentSeed().autoStartEnabled
        val DEFAULT_CAMERA_GESTURE_ENABLED: Boolean
            get() = GatewayConfigCatalog.currentSeed().cameraGestureEnabled
        val DEFAULT_VOICE_CHANNEL_SKILL_ENABLED: Boolean
            get() = GatewayConfigCatalog.currentSeed().voiceChannelSkillEnabled
        val DEFAULT_VOICE_CHANNEL_WAKE_TERMS: String
            get() = GatewayConfigCatalog.currentSeed().voiceChannelWakeTerms
        val DEFAULT_VOICE_CHANNEL_FOLLOW_UP_SECONDS: Int
            get() = GatewayConfigCatalog.currentSeed().voiceChannelFollowUpSeconds
        val DEFAULT_VOICE_CHANNEL_IDLE_PROMPT_SECONDS: Int
            get() = GatewayConfigCatalog.currentSeed().voiceChannelIdlePromptSeconds
        val DEFAULT_ASSISTANT_VOICE_ENABLED: Boolean
            get() = GatewayConfigCatalog.currentSeed().assistantVoiceEnabled
        val DEFAULT_ASSISTANT_VOICE_STYLE: AssistantVoiceStyle
            get() = GatewayConfigCatalog.currentSeed().assistantVoiceStyle
        val DEFAULT_ASSISTANT_SPEECH_RATE: Double
            get() = GatewayConfigCatalog.currentSeed().assistantSpeechRate
        val DEFAULT_ASSISTANT_PITCH: Double
            get() = GatewayConfigCatalog.currentSeed().assistantPitch
        val DEFAULT_TRANSCRIPTION_MODE: TranscriptionMode
            get() = GatewayConfigCatalog.currentSeed().transcriptionMode
        val DEFAULT_LOCAL_MODEL_PATH: String
            get() = GatewayConfigCatalog.currentSeed().localModelPath
        val DEFAULT_LOCAL_EXECUTION_MODE: LocalExecutionMode
            get() = GatewayConfigCatalog.currentSeed().localExecutionMode
        val DEFAULT_DEVELOPMENT: Boolean
            get() = GatewayConfigCatalog.currentSeed().development
        val DEFAULT_MICROPHONE_AUTO_SENSITIVITY_ENABLED: Boolean
            get() = GatewayConfigCatalog.currentSeed().microphoneAutoSensitivityEnabled
        val DEFAULT_MICROPHONE_GAIN: Double
            get() = GatewayConfigCatalog.currentSeed().microphoneGain
        val DEFAULT_TRANSCRIPTION_REPEAT_SUPPRESSION: Double
            get() = GatewayConfigCatalog.currentSeed().transcriptionRepeatSuppression
        val DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH: Double
            get() = GatewayConfigCatalog.currentSeed().colloquialNormalizationStrength
        val DEFAULT_VAD_THRESHOLD: Double
            get() = GatewayConfigCatalog.currentSeed().vadThreshold
        val DEFAULT_WHISPER_VAD_FILTER: Boolean
            get() = GatewayConfigCatalog.currentSeed().whisperVadFilter
        val DEFAULT_WHISPER_CONDITION_ON_PREVIOUS_TEXT: Boolean
            get() = GatewayConfigCatalog.currentSeed().whisperConditionOnPreviousText
        val DEFAULT_WHISPER_NO_SPEECH_THRESHOLD: Double
            get() = GatewayConfigCatalog.currentSeed().whisperNoSpeechThreshold
        val DEFAULT_WHISPER_COMPRESSION_RATIO_THRESHOLD: Double
            get() = GatewayConfigCatalog.currentSeed().whisperCompressionRatioThreshold
        val DEFAULT_WHISPER_REPETITION_PENALTY: Double
            get() = GatewayConfigCatalog.currentSeed().whisperRepetitionPenalty
        val DEFAULT_SCREEN_MODE: ScreenMode
            get() = GatewayConfigCatalog.currentSeed().screenMode
        val DEFAULT_SCREEN_HOLD_SECONDS: Int
            get() = GatewayConfigCatalog.currentSeed().screenHoldSeconds
        val DEFAULT_TRANSCRIPT_CLEAR_TIMEOUT_SECS: Int
            get() = GatewayConfigCatalog.currentSeed().transcriptClearTimeoutSecs
        val DEFAULT_OPENCLAW_ACCUMULATION_WINDOW_SECS: Int
            get() = GatewayConfigCatalog.currentSeed().openClawAccumulationWindowSecs
        val DEFAULT_NOISE_GATE_MULTIPLIER: Double
            get() = GatewayConfigCatalog.currentSeed().noiseGateMultiplier
        val DEFAULT_MIN_SPEECH_RMS: Double
            get() = GatewayConfigCatalog.currentSeed().minSpeechRms
        val DEFAULT_MIN_SPEECH_PEAK_NORMALIZED: Double
            get() = GatewayConfigCatalog.currentSeed().minSpeechPeakNormalized
        val DEFAULT_MIN_SPEECH_CANDIDATE_FRAMES: Int
            get() = GatewayConfigCatalog.currentSeed().minSpeechCandidateFrames
        val DEFAULT_MAX_TRANSIENT_CREST_FACTOR: Double
            get() = GatewayConfigCatalog.currentSeed().maxTransientCrestFactor
        val DEFAULT_MIN_ZERO_CROSSING_RATE: Double
            get() = GatewayConfigCatalog.currentSeed().minZeroCrossingRate
        val DEFAULT_MAX_ZERO_CROSSING_RATE: Double
            get() = GatewayConfigCatalog.currentSeed().maxZeroCrossingRate
        val DEFAULT_AMBIENT_STABILITY_THRESHOLD: Double
            get() = GatewayConfigCatalog.currentSeed().ambientStabilityThreshold
        val DEFAULT_AMBIENT_GAIN_STABILITY_THRESHOLD: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainStabilityThreshold
        val DEFAULT_AMBIENT_DYNAMIC_CONTRAST_MAX: Double
            get() = GatewayConfigCatalog.currentSeed().ambientDynamicContrastMax
        val DEFAULT_AMBIENT_RMS_VARIANCE_MAX: Double
            get() = GatewayConfigCatalog.currentSeed().ambientRmsVarianceMax
        val DEFAULT_AMBIENT_SPECTRUM_MOTION_MAX: Double
            get() = GatewayConfigCatalog.currentSeed().ambientSpectrumMotionMax
        val DEFAULT_AMBIENT_SPEECH_PENALTY: Double
            get() = GatewayConfigCatalog.currentSeed().ambientSpeechPenalty
        val DEFAULT_AMBIENT_DETECTION_HOLD_FRAMES: Int
            get() = GatewayConfigCatalog.currentSeed().ambientDetectionHoldFrames
        val DEFAULT_AMBIENT_DETECTION_RELEASE_FRAMES: Int
            get() = GatewayConfigCatalog.currentSeed().ambientDetectionReleaseFrames
        val DEFAULT_AMBIENT_SPEECH_OVERRIDE_DYNAMIC_CONTRAST: Double
            get() = GatewayConfigCatalog.currentSeed().ambientSpeechOverrideDynamicContrast
        val DEFAULT_AMBIENT_SPEECH_OVERRIDE_SPECTRUM_MOTION: Double
            get() = GatewayConfigCatalog.currentSeed().ambientSpeechOverrideSpectrumMotion
        val DEFAULT_AMBIENT_GAIN_FACTOR: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainFactor
        val DEFAULT_AMBIENT_GAIN_STABILITY_REDUCTION: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainStabilityReduction
        val DEFAULT_AMBIENT_GAIN_MIN_GAIN: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainMinGain
        val DEFAULT_AMBIENT_GAIN_SMOOTHING_FAST: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainSmoothingFast
        val DEFAULT_AMBIENT_GAIN_SMOOTHING_SLOW: Double
            get() = GatewayConfigCatalog.currentSeed().ambientGainSmoothingSlow



        private fun resolveTranscriptionMode(
            storedMode: TranscriptionMode,
            whisperUrl: String,
            localModelPath: String
        ): TranscriptionMode {
            val normalizedWhisperUrl = whisperUrl.trim()
            val managedWhisperHost = extractHost(DEFAULT_WHISPER_URL)
            if (
                managedWhisperHost.isNotBlank() &&
                extractHost(normalizedWhisperUrl).equals(managedWhisperHost, ignoreCase = true)
            ) {
                return TranscriptionMode.REMOTE
            }
            return storedMode
        }

        private fun extractHost(url: String): String {
            return url.trim()
                .removePrefix("wss://")
                .removePrefix("ws://")
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .trim()
                .lowercase()
        }
    }
}
