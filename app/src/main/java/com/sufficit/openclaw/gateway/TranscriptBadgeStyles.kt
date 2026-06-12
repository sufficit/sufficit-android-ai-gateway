package com.sufficit.openclaw.gateway

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import java.util.Locale

enum class DetectedGender {
    Male,
    Female,
    Ambiguous
}

enum class DetectedEmotion {
    Happy,
    Sad,
    Angry,
    Calm,
    Energetic,
    Neutral,
    Unknown
}

fun sameVoiceTint(probability: Double?): Color {
    return when {
        probability == null -> Color(0xFF8A939F)
        probability >= 0.80 -> Color(0xFF2EAF62)
        probability >= 0.55 -> Color(0xFFFFA726)
        else -> Color(0xFFD94B4B)
    }
}

fun resolveDetectedGender(value: String?): DetectedGender {
    return when (value?.trim()?.lowercase(Locale.ROOT)) {
        "male", "masculino", "homem" -> DetectedGender.Male
        "female", "feminino", "mulher" -> DetectedGender.Female
        else -> DetectedGender.Ambiguous
    }
}

fun genderBadgeLabel(gender: DetectedGender): String {
    return when (gender) {
        DetectedGender.Male -> "M"
        DetectedGender.Female -> "F"
        DetectedGender.Ambiguous -> "?"
    }
}

fun genderTint(gender: DetectedGender): Color {
    return when (gender) {
        DetectedGender.Male -> Color(0xFF3E7BFA)
        DetectedGender.Female -> Color(0xFFE55B6B)
        DetectedGender.Ambiguous -> Color(0xFF8A939F)
    }
}

fun resolveDetectedEmotion(value: String?): DetectedEmotion {
    return when (value?.trim()?.lowercase(Locale.ROOT)) {
        "feliz", "happy" -> DetectedEmotion.Happy
        "triste", "sad" -> DetectedEmotion.Sad
        "raiva", "angry" -> DetectedEmotion.Angry
        "calma", "calm" -> DetectedEmotion.Calm
        "energica", "energética", "energetic" -> DetectedEmotion.Energetic
        "neutra", "neutral" -> DetectedEmotion.Neutral
        else -> DetectedEmotion.Unknown
    }
}

fun emotionTint(emotion: DetectedEmotion): Color {
    return when (emotion) {
        DetectedEmotion.Happy -> Color(0xFFE55B6B)
        DetectedEmotion.Sad -> Color(0xFF5C7CFA)
        DetectedEmotion.Angry -> Color(0xFFD94B4B)
        DetectedEmotion.Calm -> Color(0xFF2EAF62)
        DetectedEmotion.Energetic -> Color(0xFFFFA726)
        DetectedEmotion.Neutral -> Color(0xFF8A939F)
        DetectedEmotion.Unknown -> Color(0xFF8A939F)
    }
}

fun emotionIcon(emotion: DetectedEmotion): ImageVector {
    return when (emotion) {
        DetectedEmotion.Happy -> Icons.Filled.Favorite
        DetectedEmotion.Sad -> Icons.Filled.KeyboardArrowDown
        DetectedEmotion.Angry -> Icons.Filled.Warning
        DetectedEmotion.Calm -> Icons.Filled.Favorite
        DetectedEmotion.Energetic -> Icons.Filled.Notifications
        DetectedEmotion.Neutral -> Icons.Filled.Info
        DetectedEmotion.Unknown -> Icons.Filled.Info
    }
}

fun emotionContentDescription(emotion: DetectedEmotion): String {
    return when (emotion) {
        DetectedEmotion.Happy -> "Emocao feliz"
        DetectedEmotion.Sad -> "Emocao triste"
        DetectedEmotion.Angry -> "Emocao raiva"
        DetectedEmotion.Calm -> "Emocao calma"
        DetectedEmotion.Energetic -> "Emocao energetica"
        DetectedEmotion.Neutral -> "Emocao neutra"
        DetectedEmotion.Unknown -> "Emocao"
    }
}

fun learningTint(progress: Double?): Color {
    return when {
        progress == null -> Color(0xFF8A939F)
        progress >= 0.80 -> Color(0xFF2EAF62)
        progress >= 0.50 -> Color(0xFF3E7BFA)
        else -> Color(0xFFFFA726)
    }
}

fun sameVoiceTooltip(probability: Double?): String {
    return if (probability == null) {
        "Mesma voz: sem dados ainda."
    } else {
        "Probabilidade de ser a mesma voz: ${(probability * 100).toInt().coerceIn(0, 100)}%."
    }
}

fun learningTooltip(progress: Double?): String {
    return if (progress == null) {
        "Aprendizado da ancora vocal: sem dados ainda."
    } else {
        "Aprendizado da ancora vocal: ${(progress * 100).toInt().coerceIn(0, 100)}%."
    }
}
