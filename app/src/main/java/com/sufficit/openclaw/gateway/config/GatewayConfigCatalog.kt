package com.sufficit.openclaw.gateway.config

import android.content.Context
import org.json.JSONObject
import java.io.File

object GatewayConfigCatalog {
    const val CONFIG_ASSET_NAME = "config.json"
    private const val CONFIG_FILE_NAME = "config.json"

    @Volatile
    private var cachedSeed: GatewaySettings? = null

    private val fileLock = Any()

    fun ensureSeedLoaded(context: Context): GatewaySettings {
        cachedSeed?.let { return it }
        return synchronized(this) {
            cachedSeed ?: loadSeed(context).also { cachedSeed = it }
        }
    }

    fun currentSeed(): GatewaySettings {
        return cachedSeed ?: bootstrapFallback()
    }

    fun configFile(context: Context): File {
        return File(context.filesDir, CONFIG_FILE_NAME)
    }

    fun loadRuntime(context: Context): GatewaySettings? {
        val file = configFile(context)
        if (!file.exists() || file.length() <= 0L) {
            return null
        }
        val seed = ensureSeedLoaded(context)
        return runCatching {
            val parsed = JSONObject(file.readText())
            importGatewaySettingsFromJson(seed, parsed).settings
        }.getOrNull()
    }

    fun saveRuntime(context: Context, settings: GatewaySettings): File {
        val file = configFile(context)
        synchronized(fileLock) {
            file.parentFile?.mkdirs()
            file.writeText(settings.toConfigJson().toString(2))
        }
        return file
    }

    private fun loadSeed(context: Context): GatewaySettings {
        return runCatching {
            context.assets.open(CONFIG_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                val parsed = JSONObject(reader.readText())
                importGatewaySettingsFromJson(bootstrapFallback(), parsed).settings
            }
        }.getOrElse {
            bootstrapFallback()
        }
    }

    // Config basico auto-gerado na primeira inicializacao quando nao ha
    // config.json em assets/ nem runtime salvo. SEM credenciais: host e
    // tokens ficam em branco e sao preenchidos pelo usuario na tela de
    // configuracao (ou via config.json proprio em assets/, ver
    // config.example.json). Nunca colocar credenciais reais aqui — o codigo
    // e publico.
    private fun bootstrapFallback(): GatewaySettings {
        return GatewaySettings(
            localEndpointUrl = "ws://192.168.0.10:8787/room-audio",
            openClawServerAddress = "",
            openClawGatewayToken = "",
            openClawDeviceToken = "",
            openClawSessionKey = "",
            whisperUrl = "",
            remoteModel = "large-v3-turbo",
            whisperAuthToken = "",
            autoStartEnabled = true,
            cameraGestureEnabled = true,
            voiceChannelSkillEnabled = true,
            voiceChannelWakeTerms = "xuxu\nxu xu\nchuchu\nchu chu\nopenclaw\nopen claw\nopen crowd\nclaw\npinklau\naudiodestino\naudio destino\nassistente\nagente",
            voiceChannelFollowUpSeconds = 12,
            voiceChannelIdlePromptSeconds = 300,
            assistantVoiceEnabled = true,
            assistantVoiceStyle = AssistantVoiceStyle.FEMININE,
            assistantSpeechRate = 1.15,
            assistantPitch = 1.0,
            transcriptionMode = TranscriptionMode.REMOTE,
            localModelPath = "/data/user/0/com.sufficit.openclaw.gateway/files/models/sherpa-whisper-tiny",
            localExecutionMode = LocalExecutionMode.NNAPI,
            development = false,
            microphoneAutoSensitivityEnabled = true,
            microphoneGain = 1.0,
            transcriptionRepeatSuppression = 0.72,
            colloquialNormalizationStrength = 0.35,
            vadThreshold = 0.015,
            whisperVadFilter = true,
            whisperConditionOnPreviousText = false,
            whisperNoSpeechThreshold = 0.72,
            whisperCompressionRatioThreshold = 2.2,
            whisperRepetitionPenalty = 1.12,
            debugSpeechHoldMs = null,
            debugMaxSpeechSegmentMs = null,
            debugMinTranscriptionMs = null,
            debugPhraseBreakSilenceMs = null,
            transcriptionTerms = "",
            transcriptionDictionary = "",
            screenMode = ScreenMode.ACTIVITY,
            screenHoldSeconds = 4,
            transcriptClearTimeoutSecs = 30,
            openClawAccumulationWindowSecs = 4,
            noiseGateMultiplier = 1.8,
            minSpeechRms = 0.010,
            minSpeechPeakNormalized = 0.035,
            minSpeechCandidateFrames = 3,
            maxTransientCrestFactor = 5.8,
            minZeroCrossingRate = 0.015,
            maxZeroCrossingRate = 0.24,
            ambientStabilityThreshold = 0.66,
            ambientGainStabilityThreshold = 0.35,
            ambientDynamicContrastMax = 0.050,
            ambientRmsVarianceMax = 0.22,
            ambientSpectrumMotionMax = 0.060,
            ambientSpeechPenalty = 0.20,
            ambientDetectionHoldFrames = 6,
            ambientDetectionReleaseFrames = 4,
            ambientSpeechOverrideDynamicContrast = 0.050,
            ambientSpeechOverrideSpectrumMotion = 0.090,
            ambientGainFactor = 0.58,
            ambientGainStabilityReduction = 0.22,
            ambientGainMinGain = 0.55,
            ambientGainSmoothingFast = 0.40,
            ambientGainSmoothingSlow = 0.14
        )
    }
}