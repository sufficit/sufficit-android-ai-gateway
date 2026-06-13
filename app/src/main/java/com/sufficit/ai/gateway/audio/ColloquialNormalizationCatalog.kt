package com.sufficit.ai.gateway.audio

import android.content.Context
import android.util.Log

internal data class ColloquialNormalizationRule(
    val minStrength: Double,
    val pattern: String,
    val replacement: String
)

internal object ColloquialNormalizationCatalog {
    private const val TAG = "ColloquialNormalization"
    private const val ASSET_NAME = "colloquial-normalization-safe.txt"

    @Volatile
    private var cachedRules: List<ColloquialNormalizationRule>? = null

    fun load(context: Context): List<ColloquialNormalizationRule> {
        cachedRules?.let { return it }

        return synchronized(this) {
            cachedRules?.let { return@synchronized it }

            val parsed = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .mapNotNull(::parseRule)
                        .toList()
                }
            }.getOrElse { ex ->
                Log.e(TAG, "Falha ao carregar $ASSET_NAME", ex)
                emptyList()
            }

            cachedRules = parsed
            parsed
        }
    }

    private fun parseRule(line: String): ColloquialNormalizationRule? {
        val strengthSeparator = line.indexOf('|')
        val mappingSeparator = line.indexOf("=>")
        if (strengthSeparator <= 0 || mappingSeparator <= strengthSeparator) {
            return null
        }

        val minStrength = line.substring(0, strengthSeparator).trim().toDoubleOrNull() ?: return null
        val pattern = line.substring(strengthSeparator + 1, mappingSeparator).trim()
        val replacement = line.substring(mappingSeparator + 2).trim()

        if (pattern.isBlank() || replacement.isBlank()) {
            return null
        }

        return ColloquialNormalizationRule(
            minStrength = minStrength,
            pattern = pattern,
            replacement = replacement
        )
    }
}
