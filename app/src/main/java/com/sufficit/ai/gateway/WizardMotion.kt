package com.sufficit.ai.gateway

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Reusable step-progress + step-transition motion for multi-step wizard
 * screens (see SetupWizardPage.kt for the first consumer). Durations/easing
 * follow Material 3 Motion guidance (see .claude/skills/android-native-dev/
 * references/motion-system.md): "short" (100-200ms) for simple state
 * changes, "medium/long" (up to 300ms) for content transitions, standard
 * easing cubic-bezier(0.2, 0.0, 0.0, 1.0) for most transitions.
 */

/** Barra segmentada indicando progresso do assistente (passo atual em destaque). */
@Composable
fun WizardStepIndicator(currentStep: Int, totalSteps: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (stepIndex in 1..totalSteps) {
            val active = stepIndex <= currentStep
            // Duracao curta (M3 "short": 100-200ms) — mudanca de estado simples,
            // nao merece a duracao "medium" usada na transicao de conteudo.
            val color by animateColorAsState(
                targetValue = if (active) ConfigTheme.Accent else ConfigTheme.TextSecondary.copy(alpha = 0.25f),
                animationSpec = tween(durationMillis = 150),
                label = "wizard-step-dot-$stepIndex"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// Curva "standard" do Material 3 Motion (cubic-bezier(0.2, 0.0, 0.0, 1.0)):
// aceleracao rapida, desaceleracao lenta — usada pra a maioria das transicoes.
private val M3StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Transicao entre passos de um assistente: novo passo entra deslizando+aparecendo
 * a partir da direcao do avanco, passo anterior sai na direcao oposta.
 * Duracao 300ms = teto do M3 Motion pra transicoes (evita perceber lentidao).
 */
fun wizardStepTransition(forward: Boolean): ContentTransform {
    val enterSpec = tween<IntOffset>(durationMillis = 300, easing = M3StandardEasing)
    val exitSpec = tween<IntOffset>(durationMillis = 300, easing = M3StandardEasing)
    return (
        slideInHorizontally(animationSpec = enterSpec) { fullWidth -> if (forward) fullWidth else -fullWidth } +
            fadeIn(animationSpec = tween(durationMillis = 300))
        ) togetherWith (
        slideOutHorizontally(animationSpec = exitSpec) { fullWidth -> if (forward) -fullWidth else fullWidth } +
            fadeOut(animationSpec = tween(durationMillis = 150))
        )
}
