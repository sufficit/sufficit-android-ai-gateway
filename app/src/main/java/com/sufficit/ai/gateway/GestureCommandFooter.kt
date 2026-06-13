package com.sufficit.ai.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.vision.GestureCommandIds
import kotlinx.coroutines.delay

/**
 * Linha colorida no rodape da tela indicando o gesto de comando reconhecido.
 *
 * Cores (uma por gesto, ver contrato em CameraGestureEvent):
 *  - LARANJA: mao aberta — "calma", interrompendo a fala do assistente;
 *  - VERDE:   indicador levantado — "vou falar", gravacao aberta (a linha
 *             permanece acesa enquanto o dedo ficar levantado, espelhando a
 *             regra de manter a gravacao aberta);
 *  - AZUL:    punho fechado — "terminei", enviando para processamento.
 *
 * A linha acende enquanto o gesto esta ativo e permanece por um curto
 * tempo apos soltar (linger), para o feedback nao piscar.
 */
@Composable
fun GestureCommandFooter(modifier: Modifier = Modifier) {
    val command by GatewayRuntime.gestureCommand().collectAsState()

    // Guarda o ultimo gesto para o linger apos o usuario soltar a pose.
    var lastGestureId by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }

    val current = command
    if (current != null) {
        lastGestureId = current.gestureId
        visible = true
    } else if (visible) {
        // Sem gesto ativo: agenda o apagamento apos o tempo de linger.
        androidx.compose.runtime.LaunchedEffect(lastGestureId) {
            delay(LINGER_MS)
            visible = false
        }
    }

    if (!visible) return
    val gestureId = lastGestureId ?: return

    val (color, label) = when (gestureId) {
        GestureCommandIds.OPEN_HAND -> Color(0xFFFF8A50) to "Mao aberta — parando a fala"
        GestureCommandIds.INDEX_UP -> Color(0xFF35D08C) to "Indicador — gravando, pode falar"
        GestureCommandIds.FIST -> Color(0xFF5EA8FF) to "Punho — enviando para processamento"
        else -> return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color(0xB3040A12), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

private const val LINGER_MS = 1_200L
