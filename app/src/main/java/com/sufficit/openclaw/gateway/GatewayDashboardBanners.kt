package com.sufficit.openclaw.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Blocking announcement banner displayed at the bottom of the dashboard. */
@Composable
fun BlockingAnnouncementBanner(
    message: String,
    modifier: Modifier = Modifier,
    confidence: Double? = null,
    overlap: Boolean = false,
    tags: List<String> = emptyList()
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFFFCE7C8),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Impedimento do OpenClaw",
                tint = Color(0xFFC98322),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Impedimento atual",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8A5A12),
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5E4313),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        val summaryParts = buildList {
            confidence?.let { add("Confianca: ${formatProbabilityPercent(it)}") }
            if (overlap) add("Sobreposicao de vozes")
            if (tags.isNotEmpty()) add("Tags: ${tags.joinToString(", ")}")
        }
        if (summaryParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summaryParts.joinToString(" | "),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A5A12),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Non-blocking info banner displayed above the blocking one when present. */
@Composable
fun InfoAnnouncementBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFFD6EAF8),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = "Info do OpenClaw",
            tint = Color(0xFF1A6496),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF154360),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Formats a probability value [0..1] or [0..100] as a percentage string. */
internal fun formatProbabilityPercent(value: Double): String {
    val normalized = when {
        value.isNaN() -> 0.0
        value <= 1.0 -> value * 100.0
        else -> value
    }
    return String.format(java.util.Locale.US, "%.1f%%", normalized)
}

/** Animated dots placeholder shown while the service is listening but no transcript is available. */
@Composable
fun ListeningDotsPlaceholder(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val dots by produceState(initialValue = "...", active) {
        if (!active) {
            value = "..."
            return@produceState
        }

        val frames = listOf("...", "..", ".", "..")
        var index = 0
        while (true) {
            value = frames[index % frames.size]
            index += 1
            delay(320)
        }
    }

    Text(
        text = dots,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall,
        color = color
    )
}

/** Simple start/stop/interrupt action strip used on the dashboard. */
@Composable
fun ActionStrip(
    listening: Boolean,
    transcribing: Boolean,
    speakingBack: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterruptAssistant: () -> Unit
) {
    val isRunning = listening || transcribing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                if (isRunning) onStop() else onStart()
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isRunning) "Parar" else "Iniciar")
        }
        if (speakingBack) {
            Button(
                onClick = onInterruptAssistant,
                modifier = Modifier.weight(1f)
            ) {
                Text("Interromper voz")
            }
        }
    }
}
