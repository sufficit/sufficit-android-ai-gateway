package com.sufficit.openclaw.gateway.audio

internal object TranscriptWindowing {
    private const val maxTranscriptChars = 1_200
    private const val maxWordOverlap = 8

    fun mergeCurrentTranscript(
        current: String,
        incoming: String,
        repeatSuppression: Double
    ): String {
        val existing = current.trim()
        val fresh = incoming.trim()
        if (fresh.isBlank()) {
            return existing
        }
        if (existing.isBlank()) {
            return fresh.takeLast(maxTranscriptChars)
        }

        val normalizedExisting = normalizeTranscriptForMatch(existing)
        val normalizedFresh = normalizeTranscriptForMatch(fresh)

        if (normalizedExisting == normalizedFresh) {
            return existing.takeLast(maxTranscriptChars)
        }
        if (
            normalizedExisting.contains(normalizedFresh) ||
            normalizedFresh.contains(normalizedExisting)
        ) {
            return if (normalizedFresh.length <= normalizedExisting.length) {
                existing.takeLast(maxTranscriptChars)
            } else {
                fresh.takeLast(maxTranscriptChars)
            }
        }

        val existingWords = existing.splitWhitespace()
        val freshWords = fresh.splitWhitespace()
        val maxOverlap = minOf(existingWords.size, freshWords.size, maxWordOverlap)
        val minOverlap = minimumOverlapWords(repeatSuppression)

        for (overlap in maxOverlap downTo minOverlap) {
            val suffix = existingWords.takeLast(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            val prefix = freshWords.take(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            if (suffix == prefix) {
                val merged = buildString {
                    append(existing)
                    append(' ')
                    append(freshWords.drop(overlap).joinToString(" "))
                }.trim()
                return merged.takeLast(maxTranscriptChars)
            }
        }

        return "$existing $fresh".trim().takeLast(maxTranscriptChars)
    }

    fun shouldAdvanceTranscriptWindow(
        current: String,
        incoming: String,
        phraseAdvanceReady: Boolean,
        repeatSuppression: Double
    ): Boolean {
        val existing = current.trim()
        val fresh = incoming.trim()
        if (existing.isBlank() || fresh.isBlank()) {
            return false
        }
        if (phraseAdvanceReady) {
            return true
        }

        val normalizedExisting = normalizeTranscriptForMatch(existing)
        val normalizedFresh = normalizeTranscriptForMatch(fresh)
        if (normalizedExisting.isBlank() || normalizedFresh.isBlank()) {
            return false
        }
        if (
            normalizedExisting.contains(normalizedFresh) ||
            normalizedFresh.contains(normalizedExisting)
        ) {
            return false
        }

        val existingWords = existing.splitWhitespace()
        val freshWords = fresh.splitWhitespace()
        val maxOverlap = minOf(existingWords.size, freshWords.size, maxWordOverlap)
        val minOverlap = minimumOverlapWords(repeatSuppression)
        for (overlap in maxOverlap downTo minOverlap) {
            val suffix = existingWords.takeLast(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            val prefix = freshWords.take(overlap).joinToString(" ") { normalizeTranscriptForMatch(it) }
            if (suffix == prefix) {
                return false
            }
        }

        return freshWords.size >= minimumFreshWords(repeatSuppression)
    }

    private fun minimumOverlapWords(repeatSuppression: Double): Int {
        return when {
            repeatSuppression >= 0.85 -> 5
            repeatSuppression >= 0.65 -> 4
            repeatSuppression >= 0.40 -> 3
            else -> 2
        }
    }

    private fun minimumFreshWords(repeatSuppression: Double): Int {
        return when {
            repeatSuppression >= 0.75 -> 3
            else -> 2
        }
    }

    private fun normalizeTranscriptForMatch(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.splitWhitespace(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
