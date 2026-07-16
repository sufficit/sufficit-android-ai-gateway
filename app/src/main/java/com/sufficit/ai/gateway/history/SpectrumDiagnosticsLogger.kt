package com.sufficit.ai.gateway.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private const val FILE_BASE_NAME = "spectrum-diagnostics"
    private const val FILE_EXTENSION = ".jsonl"
    private val fileLock = Any()

    // Rotacao: novo arquivo quando tamanho exceder MAX_BYTES ou a cada
    // ROTATION_HOURS horas. Sem cap absoluto: limpa arquivos antigos
    // apenas quando atingirem MAX_ROTATED_FILES.
    private const val MAX_BYTES = 20 * 1024 * 1024L // 20MB
    private const val ROTATION_HOURS = 24L
    private const val MAX_ROTATED_FILES = 7

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    private fun diagnosticsFile(context: Context, timestamp: String? = null): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val ts = timestamp ?: dateTimeFormatter.format(Instant.now())
        return File(directory, "$FILE_BASE_NAME-$ts$FILE_EXTENSION")
    }

    private fun getLatestDiagnosticsFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }

        // Encontra o arquivo mais recente com o padrao de nome
        val files = directory.listFiles { _, name ->
            name.startsWith(FILE_BASE_NAME) && name.endsWith(FILE_EXTENSION)
        }?.sortedByDescending { it.lastModified() }

        return if (files != null && files.isNotEmpty()) {
            files[0]
        } else {
            diagnosticsFile(context)
        }
    }

    private fun needsRotation(file: File): Boolean {
        if (!file.exists()) return false

        // Rotacao por tamanho
        if (file.length() >= MAX_BYTES) return true

        // Rotacao por tempo (se arquivo for mais antigo que ROTATION_HOURS)
        val ageHours = (System.currentTimeMillis() - file.lastModified()) / (1000 * 60 * 60)
        return ageHours >= ROTATION_HOURS
    }

    private fun rotateIfNeeded(context: Context, currentFile: File) {
        if (!needsRotation(currentFile)) return

        // Renomeia arquivo atual com timestamp
        val timestamp = dateTimeFormatter.format(Instant.ofEpochMilli(currentFile.lastModified()))
        val rotatedFile = File(currentFile.parentFile, "$FILE_BASE_NAME-$timestamp$FILE_EXTENSION")

        if (currentFile.renameTo(rotatedFile)) {
            // Limpa arquivos antigos mantendo apenas os MAX_ROTATED_FILES mais recentes
            pruneRotatedFiles(context)
        }
    }

    private fun pruneRotatedFiles(context: Context) {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        val files = directory.listFiles { _, name ->
            name.startsWith(FILE_BASE_NAME) && name.endsWith(FILE_EXTENSION)
        }?.sortedByDescending { it.lastModified() }

        if (files != null && files.size > MAX_ROTATED_FILES) {
            // Remove arquivos excedentes (mais antigos)
            files.drop(MAX_ROTATED_FILES).forEach { it.delete() }
        }
    }

    fun append(context: Context, entry: SpectrumDiagnosticsEntry) {
        synchronized(fileLock) {
            val currentFile = getLatestDiagnosticsFile(context)
            rotateIfNeeded(context, currentFile)

            val file = getLatestDiagnosticsFile(context)
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