package com.sufficit.ai.gateway.config

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class DeviceModelRecommendation(
    val transcriptionMode: String,
    val localModelId: String?,
    val localExecutionMode: String?,
    val remoteModel: String?,
    val status: String,
    val experienceLevel: String,
    val summary: String
)

data class DeviceModelGuide(
    val id: String,
    val displayName: String,
    val manufacturerContains: List<String>,
    val modelContains: List<String>,
    val hardwareContains: List<String>,
    val notes: String,
    val recommendations: List<DeviceModelRecommendation>
) {
    fun matchesCurrentDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val model = Build.MODEL.orEmpty().lowercase(Locale.ROOT)
        val hardware = Build.HARDWARE.orEmpty().lowercase(Locale.ROOT)

        val manufacturerMatch = manufacturerContains.isEmpty() ||
            manufacturerContains.any { manufacturer.contains(it.lowercase(Locale.ROOT)) }
        val modelMatch = modelContains.isEmpty() ||
            modelContains.any { token -> model.contains(token.lowercase(Locale.ROOT)) }
        val hardwareMatch = hardwareContains.isEmpty() ||
            hardwareContains.any { token -> hardware.contains(token.lowercase(Locale.ROOT)) }

        return manufacturerMatch && (modelMatch || hardwareMatch)
    }

    fun findRecommendation(
        transcriptionMode: String,
        localModelId: String,
        localExecutionMode: String,
        remoteModel: String
    ): DeviceModelRecommendation? {
        val normalizedMode = transcriptionMode.trim().lowercase(Locale.ROOT)
        val normalizedLocalModel = localModelId.trim().lowercase(Locale.ROOT)
        val normalizedLocalExecution = localExecutionMode.trim().lowercase(Locale.ROOT)
        val normalizedRemoteModel = remoteModel.trim().lowercase(Locale.ROOT)

        return recommendations.firstOrNull { recommendation ->
            recommendation.transcriptionMode.equals(normalizedMode, ignoreCase = true) &&
                when (normalizedMode) {
                    "local" -> {
                        recommendation.localModelId.orEmpty().equals(normalizedLocalModel, ignoreCase = true) &&
                            recommendation.localExecutionMode.orEmpty().equals(normalizedLocalExecution, ignoreCase = true)
                    }
                    "remote" -> {
                        recommendation.remoteModel.orEmpty().equals(normalizedRemoteModel, ignoreCase = true)
                    }
                    else -> false
                }
        }
    }
}

object DeviceModelGuideCatalog {
    private const val ASSET_NAME = "device-model-guides.json"

    fun load(context: Context): List<DeviceModelGuide> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                parseGuides(JSONObject(reader.readText()))
            }
        }.getOrDefault(emptyList())
    }

    fun matchCurrentDevice(context: Context): DeviceModelGuide? {
        return load(context).firstOrNull { it.matchesCurrentDevice() }
    }

    private fun parseGuides(root: JSONObject): List<DeviceModelGuide> {
        val devices = root.optJSONArray("devices") ?: JSONArray()
        return buildList {
            for (index in 0 until devices.length()) {
                val item = devices.optJSONObject(index) ?: continue
                add(
                    DeviceModelGuide(
                        id = item.optString("id").trim(),
                        displayName = item.optString("displayName").trim(),
                        manufacturerContains = item.optJSONArray("manufacturerContains").toStringList(),
                        modelContains = item.optJSONArray("modelContains").toStringList(),
                        hardwareContains = item.optJSONArray("hardwareContains").toStringList(),
                        notes = item.optString("notes").trim(),
                        recommendations = item.optJSONArray("recommendations").toRecommendations()
                    )
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index).trim())
            }
        }.filter { it.isNotBlank() }
    }

    private fun JSONArray?.toRecommendations(): List<DeviceModelRecommendation> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    DeviceModelRecommendation(
                        transcriptionMode = item.optString("transcriptionMode").trim(),
                        localModelId = item.optString("localModelId").trim().ifBlank { null },
                        localExecutionMode = item.optString("localExecutionMode").trim().ifBlank { null },
                        remoteModel = item.optString("remoteModel").trim().ifBlank { null },
                        status = item.optString("status").trim(),
                        experienceLevel = item.optString("experienceLevel").trim(),
                        summary = item.optString("summary").trim()
                    )
                )
            }
        }
    }
}
