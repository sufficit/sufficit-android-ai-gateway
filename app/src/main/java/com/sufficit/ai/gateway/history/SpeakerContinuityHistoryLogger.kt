package com.sufficit.ai.gateway.history

import android.content.Context
import com.sufficit.ai.gateway.audio.VoiceSignature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SpeakerContinuityHistoryEntry(
    val occurredAt: Instant,
    val decision: String,
    val rawProbability: Double?,
    val adjustedProbability: Double?,
    val sampleCount: Int,
    val mismatchStreak: Int,
    val anchorConfidence: Double,
    val anchor: VoiceSignature?,
    val current: VoiceSignature?
)

internal object SpeakerContinuityHistoryLogger {
    private const val DIRECTORY_NAME = "history"
    private const val FILE_BASE_NAME = "speaker-continuity-history"
    private const val FILE_EXTENSION = ".jsonl"
    private val fileLock = Any()

    // Rotacao: novo arquivo quando tamanho exceder MAX_BYTES ou a cada
    // ROTATION_HOURS horas. Sem cap absoluto: limpa arquivos antigos
    // apenas quando atingirem MAX_ROTATED_FILES.
    private const val MAX_BYTES = 15 * 1024 * 1024L // 15MB
    private const val ROTATION_HOURS = 24L
    private const val MAX_ROTATED_FILES = 7

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    private fun historyFile(context: Context, timestamp: String? = null): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val ts = timestamp ?: dateTimeFormatter.format(Instant.now())
        return File(directory, "$FILE_BASE_NAME-$ts$FILE_EXTENSION")
    }

    private fun getLatestHistoryFile(context: Context): File {
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
            historyFile(context)
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

    fun append(
        context: Context,
        entry: SpeakerContinuityHistoryEntry
    ) {
        synchronized(fileLock) {
            val currentFile = getLatestHistoryFile(context)
            rotateIfNeeded(context, currentFile)

            val file = getLatestHistoryFile(context)
            file.appendText(entry.toJson().toString() + "\n")
        }
    }

    fun buildMetadataSummary(
        context: Context,
        limit: Int = 4
    ): JSONObject? {
        val recent = recentEntries(context, limit)
        if (recent.isEmpty()) {
            return null
        }
        return JSONObject().apply {
            put("recentCount", recent.size)
            put(
                "recent",
                JSONArray().apply {
                    recent.forEach { put(it.toJson()) }
                }
            )
        }
    }

    private fun recentEntries(
        context: Context,
        limit: Int
    ): List<SpeakerContinuityHistoryEntry> {
        val file = getLatestHistoryFile(context)
        if (!file.exists() || file.length() <= 0L) {
            return emptyList()
        }
        val lines = file.readLines().asReversed()
        val output = mutableListOf<SpeakerContinuityHistoryEntry>()
        for (line in lines) {
            if (line.isBlank()) {
                continue
            }
            runCatching {
                fromJson(JSONObject(line))
            }.getOrNull()?.let {
                output += it
            }
            if (output.size >= limit) {
                break
            }
        }
        return output.reversed()
    }

    private fun SpeakerContinuityHistoryEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("occurredAt", occurredAt.toString())
            put("decision", decision)
            put("rawProbability", rawProbability)
            put("adjustedProbability", adjustedProbability)
            put("sampleCount", sampleCount)
            put("mismatchStreak", mismatchStreak)
            put("anchorConfidence", anchorConfidence)
            put("anchor", anchor?.toJson())
            put("current", current?.toJson())
        }
    }

    private fun VoiceSignature.toJson(): JSONObject {
        return JSONObject().apply {
            put("pitchMeanHz", pitchMeanHz)
            put("pitchStdHz", pitchStdHz)
            put("energyMean", energyMean)
            put("voicedRatio", voicedRatio)
        }
    }

    private fun fromJson(json: JSONObject): SpeakerContinuityHistoryEntry {
        return SpeakerContinuityHistoryEntry(
            occurredAt = Instant.parse(json.getString("occurredAt")),
            decision = json.optString("decision").trim(),
            rawProbability = json.optDouble("rawProbability").takeIf { json.has("rawProbability") },
            adjustedProbability = json.optDouble("adjustedProbability").takeIf { json.has("adjustedProbability") },
            sampleCount = json.optInt("sampleCount"),
            mismatchStreak = json.optInt("mismatchStreak"),
            anchorConfidence = json.optDouble("anchorConfidence"),
            anchor = json.optJSONObject("anchor")?.toVoiceSignature(),
            current = json.optJSONObject("current")?.toVoiceSignature()
        )
    }

    private fun JSONObject.toVoiceSignature(): VoiceSignature {
        return VoiceSignature(
            pitchMeanHz = optDouble("pitchMeanHz").takeIf { has("pitchMeanHz") },
            pitchStdHz = optDouble("pitchStdHz"),
            energyMean = optDouble("energyMean"),
            voicedRatio = optDouble("voicedRatio")
        )
    }
}