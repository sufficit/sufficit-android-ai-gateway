package com.sufficit.ai.gateway

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.runtime.GatewayUiState
import kotlinx.coroutines.delay

/** Card that shows the last assistant reply and auto-fades when the reading time elapses. */
@Composable
fun OpenClawResponseCard(state: GatewayUiState) {
    val latestReply = state.lastAssistantReply.trim().takeIf {
        it.isNotBlank() && !state.lastAssistantReplyNeedsAttention
    } ?: ""
    var visibleReply by rememberSaveable { mutableStateOf("") }
    var keepVisible by rememberSaveable { mutableStateOf(false) }
    var fadingOut by rememberSaveable { mutableStateOf(false) }
    val cardAlpha by animateFloatAsState(
        targetValue = when {
            visibleReply.isBlank() -> 0f
            fadingOut -> 0f
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (fadingOut) 1800 else 220),
        label = "openclawResponseFade"
    )
    val visibleDurationMs = remember(latestReply) {
        estimateResponseVisibilityMillis(latestReply)
    }

    LaunchedEffect(latestReply) {
        if (latestReply.isBlank()) {
            visibleReply = ""
            keepVisible = false
            fadingOut = false
            return@LaunchedEffect
        }

        visibleReply = latestReply
        keepVisible = true
        fadingOut = false

        delay(visibleDurationMs)
        if (visibleReply != latestReply) {
            return@LaunchedEffect
        }

        fadingOut = true
        delay(1800)
        if (visibleReply == latestReply) {
            visibleReply = ""
            keepVisible = false
            fadingOut = false
        }
    }

    if (visibleReply.isBlank() || !keepVisible) {
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = cardAlpha }
            .background(
                color = Color(0xFFE9F1F7),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(18.dp)
    ) {
        Text(
            text = visibleReply,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF102030),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Estimates how long the reply card should remain visible based on word/char count. */
internal fun estimateResponseVisibilityMillis(replyText: String): Long {
    val normalized = replyText.trim()
    if (normalized.isBlank()) {
        return 10_000L
    }

    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    val charCount = normalized.length
    val estimatedSpeechMs = maxOf(
        wordCount * 430L,
        charCount * 48L
    )

    return (estimatedSpeechMs + 1_800L)
        .coerceAtLeast(10_000L)
        .coerceAtMost(22_000L)
}
