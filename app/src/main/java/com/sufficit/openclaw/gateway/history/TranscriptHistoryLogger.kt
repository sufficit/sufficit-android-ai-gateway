package com.sufficit.openclaw.gateway.history

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
    private const val FILE_NAME = "transcript-history.csv"
    private const val HEADER =
        "datetime,backend,model,gender,emotion,same_speaker_probability,voice_learning_progress,phrase\n"

    private val fileLock = Any()

    fun historyFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, FILE_NAME)
    }

    fun append(context: Context, entry: TranscriptHistoryEntry) {
        synchronized(fileLock) {
            val file = historyFile(context)
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
            val file = historyFile(context)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun snapshot(context: Context): TranscriptHistorySnapshot {
        val file = historyFile(context)
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
        val source = historyFile(context)
        if (!source.exists() || source.length() == 0L) {
            return null
        }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val target = File(exportDir, "openclaw-transcript-history-$timestamp.csv")
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun csv(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
