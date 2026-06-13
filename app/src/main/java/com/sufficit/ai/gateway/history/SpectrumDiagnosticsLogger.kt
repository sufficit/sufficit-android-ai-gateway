package com.sufficit.ai.gateway.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal data class SpectrumDiagnosticsEntry(
    val occurredAt: Instant,
    val rawRms: Double,
    val adjustedRms: Double,
    val noiseFloorRms: Double,
    val dynamicContrast: Double,
    val rmsVariance: Double,
    val spectrumMotion: Double,
    val stabilityScore: Double,
    val ambientNoiseDetected: Boolean,
    val ambientNoiseKind: String?,
    val speechLikeRaw: Boolean,
    val speechLikeEffective: Boolean,
    val dynamicSpeechOverride: Boolean,
    val shouldCompensateAmbientNoise: Boolean,
    val shouldBlockAsAmbientNoise: Boolean,
    val dynamicMicrophoneGain: Double,
    val zeroCrossingRate: Double,
    val peakNormalized: Double,
    val spectrumTail: List<Double>
)

internal object SpectrumDiagnosticsLogger {
    private const val DIRECTORY_NAME = "history"
    private const val FILE_NAME = "spectrum-diagnostics.jsonl"
    private val fileLock = Any()

    fun diagnosticsFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, FILE_NAME)
    }

    fun append(context: Context, entry: SpectrumDiagnosticsEntry) {
        synchronized(fileLock) {
            val file = diagnosticsFile(context)
            file.appendText(entry.toJson().toString() + "\n")
        }
    }

    private fun SpectrumDiagnosticsEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("occurredAt", occurredAt.toString())
            put("rawRms", rawRms)
            put("adjustedRms", adjustedRms)
            put("noiseFloorRms", noiseFloorRms)
            put("dynamicContrast", dynamicContrast)
            put("rmsVariance", rmsVariance)
            put("spectrumMotion", spectrumMotion)
            put("stabilityScore", stabilityScore)
            put("ambientNoiseDetected", ambientNoiseDetected)
            put("ambientNoiseKind", ambientNoiseKind)
            put("speechLikeRaw", speechLikeRaw)
            put("speechLikeEffective", speechLikeEffective)
            put("dynamicSpeechOverride", dynamicSpeechOverride)
            put("shouldCompensateAmbientNoise", shouldCompensateAmbientNoise)
            put("shouldBlockAsAmbientNoise", shouldBlockAsAmbientNoise)
            put("dynamicMicrophoneGain", dynamicMicrophoneGain)
            put("zeroCrossingRate", zeroCrossingRate)
            put("peakNormalized", peakNormalized)
            put("spectrumTail", JSONArray().apply {
                spectrumTail.forEach { put(it) }
            })
        }
    }
}
