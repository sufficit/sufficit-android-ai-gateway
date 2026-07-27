package com.sufficit.ai.gateway.audio

/**
 * Politica pura do ganho automatico do microfone.
 *
 * O silencio de uma sala e um estado util: nele o microfone deve ficar no
 * ganho maximo configurado para aumentar o alcance da wake word. Somente um
 * fundo com energia real (ventilador, musica, conversa ou ruido continuo)
 * pode acionar atenuacao. Picos fortes continuam limitados mesmo antes de o
 * estimador de piso de ruido convergir.
 */
internal object AdaptiveMicrophoneGainPolicy {
    private const val QUIET_FRAME_MAX_RMS = 0.009
    private const val QUIET_FRAME_MAX_PEAK = 0.045
    private const val BACKGROUND_ATTENUATION_MIN_RMS = 0.010
    private const val BACKGROUND_ATTENUATION_MIN_PEAK = 0.050
    private const val TARGET_PEAK_NORMALIZED = 0.70
    private const val MIN_PEAK_FOR_LIMITER = 0.02

    fun isQuietFrame(inputRms: Double, inputPeakNormalized: Double): Boolean =
        inputRms <= QUIET_FRAME_MAX_RMS &&
            inputPeakNormalized <= QUIET_FRAME_MAX_PEAK

    fun shouldRaiseGainImmediately(
        currentGain: Double,
        targetGain: Double,
        inputRms: Double,
        inputPeakNormalized: Double,
        speechLikeFrame: Boolean,
        speechActive: Boolean
    ): Boolean =
        targetGain > currentGain &&
            (
                speechLikeFrame ||
                    speechActive ||
                    isQuietFrame(inputRms, inputPeakNormalized)
                )

    fun shouldAttenuateStableBackground(
        environmentStable: Boolean,
        noiseFloorRms: Double,
        inputRms: Double,
        inputPeakNormalized: Double
    ): Boolean {
        if (!environmentStable || isQuietFrame(inputRms, inputPeakNormalized)) {
            return false
        }
        return noiseFloorRms >= BACKGROUND_ATTENUATION_MIN_RMS ||
            inputRms >= BACKGROUND_ATTENUATION_MIN_RMS ||
            inputPeakNormalized >= BACKGROUND_ATTENUATION_MIN_PEAK
    }

    fun resolveTargetGain(
        peakGain: Double,
        minGain: Double,
        noiseFloorRms: Double,
        inputRms: Double,
        inputPeakNormalized: Double,
        speechLikeFrame: Boolean,
        speechActive: Boolean
    ): Double {
        val maxGain = peakGain.coerceAtLeast(minGain)
        val peakLimitedGain = resolveWakeWordGain(
            peakGain = maxGain,
            minGain = minGain,
            inputPeakNormalized = inputPeakNormalized
        )

        if (speechLikeFrame || speechActive) {
            return peakLimitedGain
        }

        // O piso estimado reage devagar de proposito. Um frame realmente
        // silencioso deve recuperar o alcance imediatamente, mesmo quando o
        // piso ainda carrega o valor de um ruido que acabou de cessar.
        if (isQuietFrame(inputRms, inputPeakNormalized)) {
            return maxGain
        }

        // O RMS atual antecipa a chegada de um fundo alto antes de o piso de
        // ruido convergir, mas recebe peso parcial para nao tratar uma fala
        // distante isolada como ruido continuo.
        val controlRms = maxOf(noiseFloorRms, inputRms * 0.65)
        val backgroundFactor = when {
            controlRms <= 0.010 -> 1.00
            controlRms <= 0.015 -> interpolate(controlRms, 0.010, 0.015, 1.00, 0.72)
            controlRms <= 0.025 -> interpolate(controlRms, 0.015, 0.025, 0.72, 0.42)
            controlRms <= 0.040 -> interpolate(controlRms, 0.025, 0.040, 0.42, 0.25)
            else -> 0.22
        }

        return (maxGain * backgroundFactor)
            .coerceIn(minGain, maxGain)
            .coerceAtMost(peakLimitedGain)
    }

    /**
     * Ganho dedicado do monitor permanente de wake word.
     *
     * O detector nao deve herdar a atenuacao de fundo usada pelo VAD e pela
     * transcricao: musica constante pode coexistir com uma chamada distante.
     * Ele recebe sempre o maior ganho configurado e reduz somente quando o
     * pico cru ameaca saturar o sinal.
     */
    fun resolveWakeWordGain(
        peakGain: Double,
        minGain: Double,
        inputPeakNormalized: Double
    ): Double {
        val maxGain = peakGain.coerceAtLeast(minGain)
        return if (inputPeakNormalized > MIN_PEAK_FOR_LIMITER) {
            (TARGET_PEAK_NORMALIZED / inputPeakNormalized).coerceIn(minGain, maxGain)
        } else {
            maxGain
        }
    }

    private fun interpolate(
        value: Double,
        from: Double,
        to: Double,
        outputFrom: Double,
        outputTo: Double
    ): Double {
        val progress = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return outputFrom + ((outputTo - outputFrom) * progress)
    }
}
