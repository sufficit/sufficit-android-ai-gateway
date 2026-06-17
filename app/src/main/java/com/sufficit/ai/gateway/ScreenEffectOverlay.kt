package com.sufficit.ai.gateway

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.runtime.GatewayRuntime

/**
 * Efeito visual de captura (screenshot por API) e de avisos disparados pelo
 * agente: um clarao branco rapido + uma etiqueta com o motivo, que esvanece.
 * O som e tocado pelo servico. Observa GatewayRuntime.screenEffect().
 */
@Composable
fun ScreenEffectOverlay(modifier: Modifier = Modifier) {
    val effect by GatewayRuntime.screenEffect().collectAsState()
    val flash = remember { Animatable(0f) }

    LaunchedEffect(effect?.atEpochMs) {
        if (effect == null) return@LaunchedEffect
        // Clarao: sobe rapido e desce — sensacao de obturador.
        flash.snapTo(0.85f)
        flash.animateTo(0f, androidx.compose.animation.core.tween(420))
    }

    if (flash.value <= 0.001f) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (flash.value > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = flash.value }
                    .background(Color.White)
            )
        }
        effect?.label?.takeIf { it.isNotBlank() && flash.value > 0.02f }?.let { label ->
            Text(
                text = label,
                color = Color(0xFF0F172A),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .graphicsLayer { alpha = (flash.value / 0.85f).coerceIn(0f, 1f) }
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
