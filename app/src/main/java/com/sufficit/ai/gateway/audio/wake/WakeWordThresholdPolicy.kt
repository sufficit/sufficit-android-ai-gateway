package com.sufficit.ai.gateway.audio.wake

import kotlin.math.abs

/**
 * Regras da sensibilidade automática do detector.
 *
 * O modo manual permanece soberano. No modo automático, um piso pequeno
 * compensa distância e reverberação sem remover a exigência de confirmações
 * consecutivas do WakeWordDetector.
 */
internal object WakeWordThresholdPolicy {
    const val MIN_AUTOMATIC_THRESHOLD = 0.20
    private const val UPDATE_HYSTERESIS = 0.01

    fun resolveAutomaticUpdate(
        currentThreshold: Double,
        suggestedThreshold: Double?,
        automatic: Boolean
    ): Double? {
        if (!automatic) {
            return null
        }
        val target = (suggestedThreshold ?: currentThreshold)
            .coerceAtLeast(MIN_AUTOMATIC_THRESHOLD)
        return target.takeIf {
            abs(it - currentThreshold) > UPDATE_HYSTERESIS
        }
    }
}
