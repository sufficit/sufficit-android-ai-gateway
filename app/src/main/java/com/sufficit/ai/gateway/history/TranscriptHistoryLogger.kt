package com.sufficit.ai.gateway.history

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TranscriptHistoryEntry(
    val occurredAt: Instant,
    val backend: String,
    val model: String,
    val gender: String?,
    val emotion: String?,
    val sameSpeakerProbability: Double?,
    val voiceLearningProgress: Double?,
    val phrase: String
)

data class TranscriptHistorySnapshot(
    val file: File,
    val entryCount: Int,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long
)

object TranscriptHistoryLogger {
    private const val DIRECTORY_NAME = "history"
    private const val FILE_BASE_NAME = "transcript-history"
    private const val FILE_EXTENSION = ".csv"
    private const val HEADER =
        "datetime,backend,model,gender,emotion,same_speaker_probability,voice_learning_progress,phrase\n"

    // Rotacao: novo arquivo quando tamanho exceder MAX_BYTES ou a cada
    // ROTATION_HOURS horas. Sem cap absoluto: limpa arquivos antigos
    // apenas quando atingirem MAX_ROTATED_FILES.
    private const val MAX_BYTES = 10 * 1024 * 1024L // 10MB
    private const val ROTATION_HOURS = 24L
    private const val MAX_ROTATED_FILES = 7

    private val fileLock = Any()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    private fun historyFile(context: Context, timestamp: Long? = null): File {
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

    fun append(context: Context, entry: TranscriptHistoryEntry) {
        synchronized(fileLock) {
            val currentFile = getLatestHistoryFile(context)
            rotateIfNeeded(context, currentFile)

            val file = getLatestHistoryFile(context)
            if (!file.exists() || file.length() == 0L) {
                file.writeText(HEADER)
            }
            file.appendText(
                buildString {
                    append(csv(entry.occurredAt.toString()))
                    append(',')
                    append(csv(entry.backend))
                    append(',')
                    append(csv(entry.model))
                    append(',')
                    append(csv(entry.gender.orEmpty()))
                    append(',')
                    append(csv(entry.emotion.orEmpty()))
                    append(',')
                    append(csv(entry.sameSpeakerProbability?.let { "%.4f".format(Locale.US, it) }.orEmpty()))
                    append(',')
                    append(csv(entry.voiceLearningProgress?.let { "%.4f".format(Locale.US, it) }.orEmpty()))
                    append(',')
                    append(csv(entry.phrase))
                    append('\n')
                }
            )
        }
    }

    fun clear(context: Context) {
        synchronized(fileLock) {
            val directory = File(context.filesDir, DIRECTORY_NAME)
            directory.listFiles { _, name ->
                name.startsWith(FILE_BASE_NAME) && name.endsWith(FILE_EXTENSION)
            }?.forEach { it.delete() }
        }
    }

    fun snapshot(context: Context): TranscriptHistorySnapshot {
        val file = getLatestHistoryFile(context)
        if (!file.exists()) {
            return TranscriptHistorySnapshot(
                file = file,
                entryCount = 0,
                sizeBytes = 0L,
                lastModifiedEpochMs = 0L
            )
        }

        val entryCount = file.useLines { lines ->
            lines.drop(1).count { it.isNotBlank() }
        }

        return TranscriptHistorySnapshot(
            file = file,
            entryCount = entryCount,
            sizeBytes = file.length(),
            lastModifiedEpochMs = file.lastModified()
        )
    }

    fun exportCopy(context: Context): File? {
        val source = getLatestHistoryFile(context)
        if (!source.exists() || source.length() == 0L) {
            return null
        }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = dateTimeFormatter.format(Instant.now())
        val target = File(exportDir, "openclaw-transcript-history-$timestamp.csv")
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun csv(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }
}