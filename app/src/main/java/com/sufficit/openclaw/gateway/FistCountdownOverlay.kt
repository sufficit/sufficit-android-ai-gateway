package com.sufficit.openclaw.gateway

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime
import com.sufficit.openclaw.gateway.vision.GestureCommandIds

/**
 * Contagem regressiva estilo videogame de luta para o punho mantido.
 *
 * Contrato (ver GestureCommandIds.FIST_HOLD_STOP_MS): punho fechado MANTIDO
 * por 5s para a escuta. Nos ULTIMOS 3s este overlay mostra 3.. 2.. 1 no
 * centro da tela — numero gigante com "punch-in" (escala com overshoot de
 * mola), gradiente quente e contorno, sumindo no fim de cada segundo. Ao
 * soltar o punho antes do fim, a contagem desaparece e nada acontece.
 *
 * O relogio vem do MESMO GestureCommand que o reconhecedor usa para disparar
 * o FistHeldStop (sinceEpochMs = inicio da pose continua), entao o "1" do
 * overlay termina exatamente quando a escuta para.
 */
@Composable
fun FistCountdownOverlay(modifier: Modifier = Modifier) {
    val gesture by GatewayRuntime.gestureCommand().collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val fist = gesture?.takeIf { it.gestureId == GestureCommandIds.FIST }

    // Relogio de quadro proprio: o GestureCommand renova no ritmo da camera
    // (~15-30fps, e congela se a camera atrasar); a animacao da contagem
    // precisa de tempo continuo.
    LaunchedEffect(fist?.sinceEpochMs) {
        if (fist == null) return@LaunchedEffect
        while (true) {
            withFrameMillis { nowMs = System.currentTimeMillis() }
        }
    }

    if (fist == null) return
    // Pose precisa estar VIVA: o reconhecedor renova atEpochMs por quadro
    // confirmado; quadro velho = mao sumiu/camera parou, esconde a contagem.
    if (nowMs - fist.atEpochMs > FIST_POSE_STALE_MS) return

    val heldMs = nowMs - fist.sinceEpochMs
    val remainingMs = GestureCommandIds.FIST_HOLD_STOP_MS - heldMs
    if (remainingMs <= 0 || remainingMs > GestureCommandIds.FIST_COUNTDOWN_WINDOW_MS) return

    // 3000..2001 -> 3; 2000..1001 -> 2; 1000..1 -> 1
    val digit = ((remainingMs + 999) / 1000).toInt().coerceIn(1, 3)
    // Progresso DENTRO do segundo corrente (1.0 = acabou de trocar o digito).
    val withinSecond = (remainingMs - (digit - 1) * 1000L) / 1000f
    // Fade de saida no rabo de cada segundo (estilo luta: POW -> esvanece).
    val exitAlpha = (withinSecond / COUNTDOWN_EXIT_FADE_FRACTION).coerceIn(0f, 1f)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(digit) {
            PunchDigit(digit = digit, exitAlpha = exitAlpha)
        }
    }
}

@Composable
private fun PunchDigit(digit: Int, exitAlpha: Float) {
    // Punch-in: o numero "soca" a tela vindo grande demais e assenta com
    // overshoot de mola — a assinatura visual dos rounds de jogo de luta.
    val scale = remember { Animatable(COUNTDOWN_PUNCH_START_SCALE) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    val text = digit.toString()
    val fillStyle = TextStyle(
        fontSize = 200.sp,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFF176), Color(0xFFFFB300), Color(0xFFFF3D00))
        ),
        shadow = Shadow(
            color = Color(0xCC000000),
            offset = Offset(0f, 10f),
            blurRadius = 24f
        )
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            alpha = exitAlpha
            rotationZ = COUNTDOWN_TILT_DEGREES
        }
    ) {
        // Contorno escuro por tras do preenchimento (leitura sobre qualquer
        // fundo, mesmo com a camera/chat claros atras).
        Text(
            text = text,
            style = TextStyle(
                fontSize = 200.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                color = Color(0xFF1A1208),
                drawStyle = Stroke(width = 22f)
            )
        )
        Text(text = text, style = fillStyle)
    }
}

// Pose sem quadro confirmado ha mais que isto = gesto encerrado.
private const val FIST_POSE_STALE_MS = 800L

// Escala inicial do "soco" do numero.
private const val COUNTDOWN_PUNCH_START_SCALE = 2.6f

// Inclinacao leve do numero (dinamismo de tela de luta).
private const val COUNTDOWN_TILT_DEGREES = -6f

// Fracao final de cada segundo usada para o fade de saida do digito.
private const val COUNTDOWN_EXIT_FADE_FRACTION = 0.22f
