package com.sufficit.openclaw.gateway.history

import android.content.Context
import com.sufficit.openclaw.gateway.audio.VoiceSignature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

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
    private const val FILE_NAME = "speaker-continuity-history.jsonl"
    private val fileLock = Any()

    fun historyFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, FILE_NAME)
    }

    fun append(
        context: Context,
        entry: SpeakerContinuityHistoryEntry
    ) {
        synchronized(fileLock) {
            val file = historyFile(context)
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
        val file = historyFile(context)
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