package com.sufficit.openclaw.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.config.DeviceModelGuide
import com.sufficit.openclaw.gateway.config.DeviceModelRecommendation
import java.util.Locale

/** Card that shows device guide info and per-recommendation chips. */
@Composable
fun DeviceGuideCard(
    deviceModelLabel: String,
    guide: DeviceModelGuide,
    currentRecommendation: DeviceModelRecommendation?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0x140B1520),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Guia do aparelho",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF111C27)
        )
        MetadataChip("Aparelho detectado", deviceModelLabel)
        MetadataChip("Perfil conhecido", guide.displayName)
        if (guide.notes.isNotBlank()) {
            Text(
                text = guide.notes,
                color = Color(0xFF506070),
                style = MaterialTheme.typography.bodySmall
            )
        }
        currentRecommendation?.let { recommendation ->
            MetadataChip(
                "Selecao atual",
                "${translateGuideStatus(recommendation.status)} | experiencia ${translateExperienceLevel(recommendation.experienceLevel)}"
            )
            if (recommendation.summary.isNotBlank()) {
                Text(
                    text = recommendation.summary,
                    color = Color(0xFF24384D),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        guide.recommendations.take(4).forEach { recommendation ->
            MetadataChip(
                label = buildGuideRecommendationLabel(recommendation),
                value = "${translateGuideStatus(recommendation.status)} | experiencia ${translateExperienceLevel(recommendation.experienceLevel)}"
            )
        }
    }
}

/** Builds a short human-readable label for a device model recommendation. */
fun buildGuideRecommendationLabel(recommendation: DeviceModelRecommendation): String {
    return when (recommendation.transcriptionMode.lowercase(Locale.ROOT)) {
        "local" -> {
            val model = recommendation.localModelId.orEmpty().ifBlank { "modelo local" }
            val execution = recommendation.localExecutionMode.orEmpty().ifBlank { "runtime padrao" }
            "Local: $model / $execution"
        }
        "remote" -> "Remoto: ${recommendation.remoteModel.orEmpty().ifBlank { "modelo remoto" }}"
        else -> "Recomendacao"
    }
}

/**
 * Returns a contextual tooltip string for a specific settings field based on the
 * device guide, or null when no relevant tip is available.
 */
fun resolveFieldGuideTooltip(
    deviceGuide: DeviceModelGuide?,
    transcriptionMode: String,
    localModelName: String,
    localExecutionMode: String,
    remoteModel: String,
    field: String
): String? {
    val guide = deviceGuide ?: return null
    val current = guide.findRecommendation(
        transcriptionMode = transcriptionMode,
        localModelId = localModelName,
        localExecutionMode = localExecutionMode,
        remoteModel = remoteModel
    )
    val bestLocal = guide.recommendations.firstOrNull {
        it.transcriptionMode.equals("local", ignoreCase = true) &&
            it.status.equals("validated", ignoreCase = true)
    }
    val bestRemote = guide.recommendations.firstOrNull {
        it.transcriptionMode.equals("remote", ignoreCase = true) &&
            it.status.equals("validated", ignoreCase = true)
    }

    return when (field) {
        "local_model" -> when {
            current != null && transcriptionMode.equals("local", ignoreCase = true) ->
                "${buildGuideRecommendationLabel(current)}\n${current.summary}"
            transcriptionMode.equals("local", ignoreCase = true) && bestLocal != null &&
                !bestLocal.localModelId.orEmpty().equals(localModelName, ignoreCase = true) ->
                "Neste aparelho, o modelo local mais validado foi ${bestLocal.localModelId} com ${bestLocal.localExecutionMode}. ${bestLocal.summary}"
            else -> null
        }
        "local_execution" -> when {
            current != null && transcriptionMode.equals("local", ignoreCase = true) ->
                "${buildGuideRecommendationLabel(current)}\n${current.summary}"
            transcriptionMode.equals("local", ignoreCase = true) && bestLocal != null &&
                !bestLocal.localExecutionMode.orEmpty().equals(localExecutionMode, ignoreCase = true) ->
                "Neste aparelho, o runtime local mais validado foi ${bestLocal.localExecutionMode} usando ${bestLocal.localModelId}. ${bestLocal.summary}"
            else -> null
        }
        "remote_model" -> when {
            current != null && transcriptionMode.equals("remote", ignoreCase = true) ->
                "${buildGuideRecommendationLabel(current)}\n${current.summary}"
            transcriptionMode.equals("remote", ignoreCase = true) && bestRemote != null &&
                !bestRemote.remoteModel.orEmpty().equals(remoteModel, ignoreCase = true) ->
                "Neste aparelho, o modelo remoto mais validado foi ${bestRemote.remoteModel}. ${bestRemote.summary}"
            else -> null
        }
        else -> null
    }
}

/** Translates guide status codes to Portuguese. */
fun translateGuideStatus(status: String): String {
    return when (status.lowercase(Locale.ROOT)) {
        "validated" -> "validado"
        "functional" -> "funcional"
        "experimental" -> "experimental"
        "broken" -> "problematico"
        else -> status
    }
}

/** Translates experience level codes to Portuguese. */
fun translateExperienceLevel(level: String): String {
    return when (level.lowercase(Locale.ROOT)) {
        "otima" -> "otima"
        "boa" -> "boa"
        "limitada" -> "limitada"
        "fraca" -> "fraca"
        "inviavel" -> "inviavel"
        else -> level
    }
}
