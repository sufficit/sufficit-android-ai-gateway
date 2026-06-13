package com.sufficit.ai.gateway.audio

import com.sufficit.ai.gateway.config.GatewaySettings
import java.text.Normalizer

data class VoiceChannelSkillDecision(
    val shouldDispatch: Boolean,
    val routedText: String,
    val reason: String,
    val matchedWakeTerm: String? = null,
    val isDirectAddress: Boolean = false,
    val shouldAskForWakeConfirmation: Boolean = false,
    val secondsSinceDirectAddress: Long? = null,
    val shouldResetConversationContext: Boolean = false,
    val forceVoiceReplyByDefault: Boolean = false
)

object VoiceChannelSkill {
    fun evaluate(
        phrase: String,
        settings: GatewaySettings,
        conversationUntilEpochMs: Long,
        lastDirectAddressEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): VoiceChannelSkillDecision {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) {
            return VoiceChannelSkillDecision(
                shouldDispatch = false,
                routedText = "",
                reason = "blank",
                secondsSinceDirectAddress = secondsSince(lastDirectAddressEpochMs, nowEpochMs)
            )
        }

        if (!settings.voiceChannelSkillEnabled) {
            return VoiceChannelSkillDecision(
                shouldDispatch = true,
                routedText = trimmed,
                reason = "skill_disabled",
                isDirectAddress = true,
                secondsSinceDirectAddress = 0L,
                shouldResetConversationContext = true,
                forceVoiceReplyByDefault = true
            )
        }

        val normalizedText = normalize(trimmed)
        val compactedText = compact(normalizedText)
        val wakeTerms = parseWakeTerms(settings.voiceChannelWakeTerms)
        val matchedWakeTerm = wakeTerms.firstOrNull {
            containsWakeTerm(normalizedText, compactedText, it)
        }
        if (matchedWakeTerm != null) {
            val stripped = stripWakeTerm(trimmed, matchedWakeTerm)
            return VoiceChannelSkillDecision(
                shouldDispatch = true,
                routedText = stripped.ifBlank { trimmed },
                reason = "wake_term",
                matchedWakeTerm = matchedWakeTerm.original,
                isDirectAddress = true,
                secondsSinceDirectAddress = 0L,
                shouldResetConversationContext = true,
                forceVoiceReplyByDefault = true
            )
        }

        if (nowEpochMs <= conversationUntilEpochMs) {
            return VoiceChannelSkillDecision(
                shouldDispatch = true,
                routedText = trimmed,
                reason = "follow_up_window",
                isDirectAddress = true,
                secondsSinceDirectAddress = secondsSince(lastDirectAddressEpochMs, nowEpochMs)
            )
        }

        val secondsSinceDirectAddress = secondsSince(lastDirectAddressEpochMs, nowEpochMs)
        val shouldAskForWakeConfirmation =
            secondsSinceDirectAddress == null ||
                secondsSinceDirectAddress >= settings.voiceChannelIdlePromptSeconds

        return VoiceChannelSkillDecision(
            shouldDispatch = true,
            routedText = trimmed,
            reason = if (shouldAskForWakeConfirmation) {
                "idle_confirmation_window"
            } else {
                "ambient_conversation"
            },
            shouldAskForWakeConfirmation = shouldAskForWakeConfirmation,
            secondsSinceDirectAddress = secondsSinceDirectAddress
        )
    }

    private fun secondsSince(referenceEpochMs: Long, nowEpochMs: Long): Long? {
        if (referenceEpochMs <= 0L || nowEpochMs < referenceEpochMs) {
            return null
        }
        return ((nowEpochMs - referenceEpochMs) / 1000L).coerceAtLeast(0L)
    }

    private fun containsWakeTerm(
        normalizedText: String,
        compactedText: String,
        wakeTerm: WakeTerm
    ): Boolean {
        if (Regex("""\b${Regex.escape(wakeTerm.normalized)}\b""").containsMatchIn(normalizedText)) {
            return true
        }
        if (wakeTerm.flexibleRegex.containsMatchIn(normalizedText)) {
            return true
        }
        return wakeTerm.compacted.length >= MIN_COMPACT_WAKE_TERM_LENGTH &&
            compactedText.contains(wakeTerm.compacted)
    }

    private fun stripWakeTerm(text: String, wakeTerm: WakeTerm): String {
        val candidates = linkedSetOf(wakeTerm.original, wakeTerm.normalized)
        var stripped = text
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            stripped = stripped.replace(
                Regex("""(?i)\b${Regex.escape(candidate)}\b[,:;!\-]*\s*"""),
                ""
            )
        }
        stripped = stripped.replace(
            Regex("""(?i)\b${wakeTerm.flexiblePattern}\b[,:;!\-]*\s*"""),
            ""
        )
        return stripped
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseWakeTerms(raw: String): List<WakeTerm> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                val normalized = normalize(it)
                val compacted = compact(normalized)
                WakeTerm(
                    original = it,
                    normalized = normalized,
                    compacted = compacted,
                    flexiblePattern = buildFlexibleWakePattern(compacted),
                    flexibleRegex = Regex("""\b${buildFlexibleWakePattern(compacted)}\b""")
                )
            }
            .filter { it.normalized.isNotBlank() }
            .distinctBy { it.compacted }
            .toList()
    }

    private fun compact(value: String): String {
        return value.replace(" ", "")
    }

    private fun buildFlexibleWakePattern(compactedValue: String): String {
        return compactedValue
            .map { Regex.escape(it.toString()) }
            .joinToString(separator = "\\s*")
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class WakeTerm(
        val original: String,
        val normalized: String,
        val compacted: String,
        val flexiblePattern: String,
        val flexibleRegex: Regex
    )

    private const val MIN_COMPACT_WAKE_TERM_LENGTH = 4
}
